package com.example.demo.handler;

import com.example.demo.service.ASRService;
import com.example.demo.config.InterviewConfig;
import com.example.demo.service.LLMService;
import com.example.demo.service.SpeculativeLLMService;
import com.example.demo.service.TTSService;
import com.example.demo.service.TranscriptService;
import com.example.demo.service.TurnStateService;
import com.example.demo.service.FeedbackService;
import com.example.demo.service.InterruptDetectorService;
import com.example.demo.service.SilenceService;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AudioHandler extends BinaryWebSocketHandler {

    private final ASRService asrService;
    private final LLMService llmService;
    private final TTSService ttsService;
    private final InterviewConfig interviewConfig;
    private final TranscriptService transcriptService;
    private final FeedbackService feedbackService;
    private final TurnStateService turnStateService;
    private final SpeculativeLLMService speculativeLLMService;
    private final InterruptDetectorService interruptDetector;
    private final SilenceService silenceService;

    /** 反馈生成是 5~15s 的慢 LLM 调用，丢到独立线程池里跑，绝不阻塞 WebSocket 主线程。 */
    private final ExecutorService feedbackExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "feedback-worker");
        t.setDaemon(true);
        return t;
    });

    /** LLM 流式拉取 + 按句 TTS 均在这个池里跑，主线程只负责接音频。 */
    private final ExecutorService dialogExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dialog-worker");
        t.setDaemon(true);
        return t;
    });

    /** 每会话一个 DashScope Recognition（长连接）。 */
    private final Map<String, Recognition> recognitionMap = new ConcurrentHashMap<>();
    /** 当前轮 ASR 累计到的中间文本，供前端字幕实时刷新。 */
    private final Map<String, StringBuilder> asrRollingText = new ConcurrentHashMap<>();
    /** 当前会话的 AI 任务取消令牌：cancelled=true 意味着"生成/播放到某个安全点后请中止"。 */
    private final Map<String, AtomicBoolean> aiTaskTokens = new ConcurrentHashMap<>();
    /** AI 当前发声预计最早播完的绝对时刻（System.nanoTime）；早于此时刻的 USER_TURN_READY 一律延迟重时。 */
    private final Map<String, AtomicLong> aiPlaybackEndAtNanos = new ConcurrentHashMap<>();

    /** 用于将早到的 USER_TURN_READY 或 end=true 清理延迟到预计播完时刻。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "audio-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AudioHandler(ASRService asrService,
                        LLMService llmService,
                        TTSService ttsService,
                        InterviewConfig interviewConfig,
                        TranscriptService transcriptService,
                        FeedbackService feedbackService,
                        TurnStateService turnStateService,
                        SpeculativeLLMService speculativeLLMService,
                        InterruptDetectorService interruptDetector,
                        SilenceService silenceService) {
        this.asrService = asrService;
        this.llmService = llmService;
        this.ttsService = ttsService;
        this.interviewConfig = interviewConfig;
        this.transcriptService = transcriptService;
        this.feedbackService = feedbackService;
        this.turnStateService = turnStateService;
        this.speculativeLLMService = speculativeLLMService;
        this.interruptDetector = interruptDetector;
        this.silenceService = silenceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        System.out.println("🔗 用户接入: " + session.getId());
        if (asrService.isReady()) {
            startRecognition(session);
        } else {
            System.err.println("⚠️ [ASR] DashScope 未就绪，该会话将无法识别语音: " + session.getId());
        }
    }

    /** 为该会话启动一条 DashScope 双向流式连接，变化直接回回调写 asrRollingText。 */
    private void startRecognition(WebSocketSession session) {
        final String sid = session.getId();
        asrRollingText.put(sid, new StringBuilder());

        Recognition recognition = new Recognition();
        ResultCallback<RecognitionResult> callback = new ResultCallback<RecognitionResult>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result == null || result.getSentence() == null) return;
                String text = result.getSentence().getText();
                if (text == null || text.isEmpty()) return;

                // 中间结果→字幕实时刷；句尾结果→触发 endpoint 逻辑
                if (result.isSentenceEnd()) {
                    onAsrEndpoint(session, text);
                } else {
                    onAsrPartial(session, text);
                }
            }

            @Override
            public void onComplete() {
                System.out.println("🔇 [ASR] onComplete sid=" + sid);
            }

            @Override
            public void onError(Exception e) {
                System.err.println("❌ [ASR] onError sid=" + sid + " : " + e.getMessage());
            }
        };

        try {
            recognition.call(asrService.buildParam(), callback);
            recognitionMap.put(sid, recognition);
            System.out.println("🎧 [ASR] 已建立 DashScope 流式连接 sid=" + sid);
        } catch (Exception e) {
            System.err.println("❌ [ASR] 启动 DashScope 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 字幕中间结果：发回前端，同时喂给 speculative 引擎（仅 USER_TURN 期间才推测）。 */
    private void onAsrPartial(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage("[ASR]:" + text));
                }
            }
        } catch (IOException ignore) {
        }
        String sid = session.getId();
        silenceService.onUserSpoke(sid);

        promoteToUserTurnIfPlaybackDone(session);
        TurnStateService.Phase phase = turnStateService.getPhase(sid);
        if (phase == TurnStateService.Phase.AI_PLANNING) {
            // 装填期用户开口 = 直接抢占：cancel 令牌置位、砍掉 speculative、状态回 USER_TURN。
            // 起播前的音频还未下发，前端不需要 stop 指令；只是新一轮生成会盖过去。
            if (!isValidUserSpeech(text)) return;
            AtomicBoolean tok = aiTaskTokens.get(sid);
            if (tok != null) tok.set(true);
            speculativeLLMService.clear(sid);
            turnStateService.enterUserTurn(sid);
            System.out.println("⚡ [Preempt] AI_PLANNING 期间用户抢话，取消当前生成 sid=" + sid);
            // 继续走 USER_TURN 的常规处理（speculative + interrupt gate 都不适用于抢占本身）
            speculativeLLMService.onPartial(sid, text);
            return;
        }
        if (phase == TurnStateService.Phase.USER_TURN) {
            speculativeLLMService.onPartial(sid, text);
            if (interruptDetector.shouldCallJudge(sid, text)) {
                maybeJudgeInterrupt(session, text);
            }
        }
        // AI_SPEAKING 分支：什么也不做，让 interrupt judge 走异步（当前 partial 会在下一轮再触发 judge）
    }

    /** 简单启发式判断 ASR partial 是否像"用户在说英语"，抵御一部分噪声/单字符误触发。 */
    private boolean isValidUserSpeech(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 3) return false;
        int letters = 0;
        int spaces = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 128 && Character.isLetter(c)) letters++;
            if (c == ' ') spaces++;
        }
        // 至少一半字符是 ASCII 字母
        if (letters * 2 < t.length()) return false;
        // 单个短词（<6 且无空格）视为可疑
        if (spaces < 1 && t.length() < 6) return false;
        return true;
    }

    /** Gate 通过后异步 call LLM 判定；判定后决定是否真打断。 */
    private void maybeJudgeInterrupt(WebSocketSession session, String partial) {
        final String sid = session.getId();
        if (!interruptDetector.markJudgeStart(sid)) return;
        dialogExecutor.submit(() -> {
            long elapsed = interruptDetector.turnElapsedMs(sid);
            LLMService.InterruptDecision decision = llmService.judgeInterrupt(sid, partial, elapsed);
            boolean fire = decision != null && decision.interrupt;
            if (!fire) {
                interruptDetector.markJudgeEnd(sid, false);
                return;
            }
            // 二次校验：如果这期间已经进入 AI_SPEAKING（例如用户 endpoint 已到）就作废
            if (turnStateService.isAiSpeaking(sid)) {
                interruptDetector.markJudgeEnd(sid, false);
                return;
            }
            interruptDetector.markJudgeEnd(sid, true);
            triggerInterrupt(session, decision.speaker, decision.text);
        });
    }

    /** LLM 判定要打断了：走标准 UTTER 三帧协议插播这句话。 */
    private void triggerInterrupt(WebSocketSession session, String speaker, String text) {
        final String sid = session.getId();
        try {
            speculativeLLMService.clear(sid);
            turnStateService.enterAiSpeaking(sid);
            sendUtterBegin(session, speaker, 0, text);
            byte[] audio = ttsService.generateSpeech(text, speaker);
            if (audio != null && audio.length > 0) {
                String base64 = Base64.getEncoder().encodeToString(audio);
                synchronized (session) {
                    session.sendMessage(new TextMessage(
                            "[AUDIO_CHUNK]:" + speaker + ":0:" + base64));
                }
                sendUtterEnd(session, speaker, 1);
                noteAiPlaybackDuration(sid, estimatePlaybackMs(audio));
                transcriptService.recordAi(sid, speaker, "[INTERRUPT] " + text);
                System.out.println("✂ [Interrupt] 已插播 sid=" + sid + " speaker=" + speaker);
            } else {
                turnStateService.enterUserTurn(sid);
                armSilenceAfterUserTurn(session);
            }
        } catch (Exception e) {
            System.err.println("❌ [Interrupt] 触发失败: " + e.getMessage());
            turnStateService.enterUserTurn(sid);
            armSilenceAfterUserTurn(session);
        }
    }

    /** 句尾：写 transcript，如 USER_TURN 则触发 LLM；否则仅记录抢话。 */
    private void onAsrEndpoint(WebSocketSession session, String finalText) {
        String sid = session.getId();
        promoteToUserTurnIfPlaybackDone(session);
        TurnStateService.Phase phase = turnStateService.getPhase(sid);

        // AI_PLANNING 期间收到 endpoint：视为抢占（final 一般比 partial 更可信，不再过 isValidUserSpeech gate）。
        // cancel 掉当前 AI 任务后，把 phase 拉回 USER_TURN，让下方继续走正常 LLM 路径。
        if (phase == TurnStateService.Phase.AI_PLANNING) {
            AtomicBoolean tok = aiTaskTokens.get(sid);
            if (tok != null) tok.set(true);
            speculativeLLMService.clear(sid);
            turnStateService.enterUserTurn(sid);
            phase = TurnStateService.Phase.USER_TURN;
            System.out.println("⚡ [Preempt] AI_PLANNING 期间用户 final，抢占取消 sid=" + sid);
        }

        boolean aiSpeaking = phase == TurnStateService.Phase.AI_SPEAKING;
        System.out.println("🛑 [VAD] 用户说完" + (aiSpeaking ? " (AI_SPEAKING抑制)" : "") + ": " + finalText);

        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage("[ASR]:" + finalText));
                }
            }
        } catch (IOException ignore) {
        }

        transcriptService.recordUser(sid, finalText, aiSpeaking);
        interruptDetector.resetTurn(sid);
        silenceService.onUserSpoke(sid);
        StringBuilder buf = asrRollingText.get(sid);
        if (buf != null) buf.setLength(0);

        if (aiSpeaking) {
            // 用户插嘴，把在飞的 speculative 也一并砍掉（否则 attempt 会滞留污染下一轮）
            speculativeLLMService.clear(sid);
            return;
        }

        if (llmService.isGroupInterview(sid)) {
            // 群面：spec 目前不覆盖 bundle 路径，直接走 bundle
            speculativeLLMService.clear(sid);
            triggerBundleReply(session, finalText);
            return;
        }

        SpeculativeLLMService.CachedResult cached = speculativeLLMService.tryCommit(sid, finalText);
        if (cached != null) {
            playCachedReply(session, finalText, cached);
            return;
        }
        triggerStreamingReply(session, finalText);
    }

    /**
     * Speculative 命中时的播放路径：直接把提前缓存好的 chunks 走一次完整的 UTTER_BEGIN/AUDIO_CHUNK/UTTER_END 协议，
     * 且把用户/AI 两条补写进 LLMService 的会话记忆，保持后续轮次上下文一致。
     */
    private void playCachedReply(WebSocketSession session, String userText,
                                 SpeculativeLLMService.CachedResult cached) {
        final String sid = session.getId();
        // Speculative 命中已经有全套音频缓存，装填近乎瞬时 → 直接进 AI_SPEAKING 即可，跳过 PLANNING。
        turnStateService.enterAiSpeaking(sid);
        final AtomicBoolean cancelToken = newAiTaskToken(sid);
        dialogExecutor.submit(() -> {
            String speaker = cached.speaker != null ? cached.speaker : "ProfA";
            int sent = 0;
            try {
                sendUtterBegin(session, speaker, 0, "");
                long specTotalMs = 0;
                for (SpeculativeLLMService.CachedChunk chunk : cached.chunks) {
                    if (cancelToken.get()) {
                        System.out.println("⚡ [SpecPlay] cancelled sid=" + sid);
                        return;
                    }
                    long sendTs = System.currentTimeMillis();
                    synchronized (session) {
                        session.sendMessage(new TextMessage(
                                "[AUDIO_CHUNK]:" + chunk.speaker + ":" + chunk.seq + ":" + chunk.base64Audio));
                    }
                    // base64 → 原始字节数近似（base64 每 4 字符 = 3 字节），估播放时长累加
                    long rawBytes = Math.round(chunk.base64Audio.length() * 0.75);
                    specTotalMs += Math.round(rawBytes * 0.167);
                    sent++;
                    System.out.println("⚡ [SpecPlay] sendTs=" + sendTs + " seq=" + chunk.seq
                            + " " + chunk.speaker + " sentence=" + chunk.sentence);
                }
                if (specTotalMs > 0) noteAiPlaybackDuration(sid, specTotalMs + 200);

                // 抽 finalText 用于 transcript / [AI] 字幕帧
                StringBuilder textAgg = new StringBuilder();
                for (SpeculativeLLMService.CachedChunk c : cached.chunks) {
                    textAgg.append(c.sentence).append(' ');
                }
                String finalText = textAgg.toString().trim();
                if (finalText.isEmpty() && cached.fullJson != null) {
                    try {
                        JsonNode j = objectMapper.readTree(cached.fullJson);
                        if (j.has("text")) finalText = j.get("text").asText();
                    } catch (Exception ignore) {
                        finalText = cached.fullJson;
                    }
                }

                transcriptService.recordAi(sid, speaker, finalText);
                synchronized (session) {
                    session.sendMessage(new TextMessage("[AI]:" + speaker + ":" + finalText));
                    session.sendMessage(new TextMessage("[UTTER_END]:" + speaker + ":" + sent));
                }

                if (cached.fullJson != null) {
                    llmService.promoteToMemory(sid, userText, cached.fullJson);
                }

                if ("end".equalsIgnoreCase(cached.action)) {
                    System.out.println("🎬 [导播台] AI 宣告面试结束！(spec)");
                    sendTranscript(session, "AI_ENDED");
                    synchronized (session) {
                        session.sendMessage(new TextMessage("[COMMAND]:END_INTERVIEW"));
                    }
                    cleanupAfterEnd(session);
                }
            } catch (IOException e) {
                System.err.println("❌ [SpecPlay] 发送失败: " + e.getMessage());
                turnStateService.enterUserTurn(sid);
                armSilenceAfterUserTurn(session);
            }
        });
    }

    /**
     * 面试通过 [COMMAND]:END_INTERVIEW 结束后，主动关掉 ASR 长连接 + 清各服务状态，
     * 避免用户走到反馈页时后端还在跑识别 / 沉默定时 / 令牌残留。
     * 与 afterConnectionClosed 的区别：这里不 close WebSocket 本身（前端还要接 [FEEDBACK] 帧）。
     */
    private void cleanupAfterEnd(WebSocketSession session) {
        final String sid = session.getId();
        System.out.println("🧹 [Cleanup] 面试结束，停 ASR/沉默/令牌 sid=" + sid);
        silenceService.clear(sid);
        speculativeLLMService.clear(sid);
        turnStateService.clear(sid);
        interruptDetector.clear(sid);
        AtomicBoolean tok = aiTaskTokens.remove(sid);
        if (tok != null) tok.set(true);
        aiPlaybackEndAtNanos.remove(sid);
        Recognition rec = recognitionMap.remove(sid);
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignore) {
            }
            try {
                rec.getDuplexApi().close(1000, "interview-ended");
            } catch (Exception ignore) {
            }
        }
        asrRollingText.remove(sid);
    }

    /** edge-tts 输出为 24kHz mono ~48kbps mp3，粗估播放时长（ms）。保守多算 200ms 兜底解码。 */
    private long estimatePlaybackMs(byte[] mp3Bytes) {
        if (mp3Bytes == null || mp3Bytes.length == 0) return 0L;
        // 48kbps ≈ 6 KB/s → 每字节 ~0.167ms
        long ms = Math.round(mp3Bytes.length * 0.167);
        return ms + 200;
    }

    /** 登记新的 AI 任务令牌，同时把上一个还未收尾的任务显式作废，防止旧任务继续下发音频叠音。 */
    private AtomicBoolean newAiTaskToken(String sid) {
        AtomicBoolean fresh = new AtomicBoolean(false);
        AtomicBoolean prev = aiTaskTokens.put(sid, fresh);
        if (prev != null && prev.compareAndSet(false, true)) {
            System.out.println("⚡ [Token] 作废上一次 AI 任务 sid=" + sid);
        }
        return fresh;
    }

    /**
     * 若状态还是 AI_SPEAKING 但预计播完时刻已过（前端 USER_TURN_READY 姗姗未到 / 丢帧 / 网络卡）
     * → 主动切回 USER_TURN 并 arm 沉默，避免用户说话被"AI_SPEAKING 抑制"分支静默丢弃。
     */
    private void promoteToUserTurnIfPlaybackDone(WebSocketSession session) {
        final String sid = session.getId();
        if (!turnStateService.isAiSpeaking(sid)) return;
        AtomicLong endAt = aiPlaybackEndAtNanos.get(sid);
        if (endAt == null) return;
        if (System.nanoTime() < endAt.get()) return;
        System.out.println("🎬 [Turn] AI 播完但前端 READY 未到，backend 自动切 USER_TURN sid=" + sid);
        turnStateService.enterUserTurn(sid);
        armSilenceAfterUserTurn(session);
    }

    /**
     * 记录本次 AI 发声的预计最早播完绝对时刻。多句流式发声按"接力"累加：
     *   若已有的 endAt 还没过（AI 还在播），新句从 endAt 起继续加时长；
     *   若已过（这是新一段发声的首句），从 now 起加。
     */
    private void noteAiPlaybackDuration(String sid, long durationMs) {
        long nowNs = System.nanoTime();
        long addNs = TimeUnit.MILLISECONDS.toNanos(durationMs);
        aiPlaybackEndAtNanos.compute(sid, (k, prev) -> {
            if (prev == null) return new AtomicLong(nowNs + addNs);
            long cur = prev.get();
            long base = cur > nowNs ? cur : nowNs;
            prev.set(base + addNs);
            return prev;
        });
    }

    /**
     * 处理前端 USER_TURN_READY：若晚于 AI 预计播完时刻则立即切 USER_TURN + arm 沉默；
     * 若前端提前发（decode race / 缓存旧 html / 恶意伪造），把切换延后到预计播完时刻再执行。
     */
    private void handleUserTurnReadyFromClient(WebSocketSession session) {
        final String sid = session.getId();
        AtomicLong endAt = aiPlaybackEndAtNanos.get(sid);
        long now = System.nanoTime();
        if (endAt == null || now >= endAt.get()) {
            turnStateService.enterUserTurn(sid);
            armSilenceAfterUserTurn(session);
            return;
        }
        long deferMs = TimeUnit.NANOSECONDS.toMillis(endAt.get() - now);
        System.out.println("⏳ [USER_TURN_READY] 前端提前 " + deferMs + "ms 发送，延后切换 sid=" + sid);
        scheduler.schedule(() -> {
            if (!session.isOpen()) return;
            // 若这期间 phase 已被其他路径改成 USER_TURN 或 AI_PLANNING（例如用户抢话），无需再切
            if (turnStateService.getPhase(sid) != TurnStateService.Phase.AI_SPEAKING) return;
            turnStateService.enterUserTurn(sid);
            armSilenceAfterUserTurn(session);
        }, deferMs, TimeUnit.MILLISECONDS);
    }
    
        /**
     * 发送开场白给用户
     * @param session 当前的 WebSocket 会话
     * @param interviewType 面试套餐类型 (如 "1v1", "2v1", "2v6")
     */
    private void sendGreeting(WebSocketSession session, String interviewType) {
        try {
            // 让 LLM 基于 outline 生成 "欢迎语 + 第一个真问题"；异常/未就绪时内部自动兜底
            String greeting = llmService.generateOpening(session.getId());
            System.out.println("🎤 [开场白] 正在生成语音: " + greeting);

            // 开场白也走“单句分段”协议（后面会把所有对话都统一到这个协议）。
            // 默认让 ProfA（主考官）来说开场白
            byte[] audioBytes = ttsService.generateSpeech(greeting, "ProfA");
            if (audioBytes != null && audioBytes.length > 0) {
                String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
                // 开场白开声即进入 AI_SPEAKING，等前端播完发 USER_TURN_READY 才切回
                turnStateService.enterAiSpeaking(session.getId());
                sendUtterBegin(session, "ProfA", 0, greeting);
                session.sendMessage(new TextMessage("[AUDIO_CHUNK]:ProfA:0:" + base64Audio));
                sendUtterEnd(session, "ProfA", 1);
                noteAiPlaybackDuration(session.getId(), estimatePlaybackMs(audioBytes));
                transcriptService.recordAi(session.getId(), "ProfA", greeting);
                // 开场白发完，等前端 USER_TURN_READY 到时 arm；不主动 arm 以免早于播完
            }
        } catch (Exception e) {
            System.err.println("❌ [开场白] 发送失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 进入 USER_TURN 后 arm 沉默定时器；到时按当前面试模式触发不同的沉默应对。 */
    private void armSilenceAfterUserTurn(WebSocketSession session) {
        final String sid = session.getId();
        final boolean isGroup = llmService.isGroupInterview(sid);
        silenceService.armAfterUserTurn(sid, isGroup, (silenceCount, mayEnd) -> {
            // 到时前二次校验：如果这期间 AI 又开始说话（例如打断/推测已提交）就作废
            if (turnStateService.isAiSpeaking(sid)) {
                System.out.println("⏰ [Silence] 到时但已 AI_SPEAKING，作废 sid=" + sid);
                return;
            }
            System.out.println("⏰ [Silence] 触发 sid=" + sid + " count=" + silenceCount
                    + " group=" + isGroup + " mayEnd=" + mayEnd);
            if (isGroup) {
                handleGroupSilence(session);
            } else {
                handleSingleSilence(session, silenceCount, mayEnd);
            }
        });
    }

    /** 单面沉默：LLM 生成 ProfA 一句台词，走 TTS 单帧发出；level 3 时可能顺带结束面试。 */
    private void handleSingleSilence(WebSocketSession session, int silenceCount, boolean mayEnd) {
        final String sid = session.getId();
        // PLANNING 立即置位，令牌登记；如果用户在 LLM/TTS 期间开口，会置 token 并把状态拉回 USER_TURN。
        turnStateService.enterAiPlanning(sid);
        final AtomicBoolean cancelToken = newAiTaskToken(sid);
        dialogExecutor.submit(() -> {
            try {
                LLMService.SilenceResponse resp = llmService.generateSilenceResponse(sid, silenceCount, mayEnd);
                if (cancelToken.get()) {
                    System.out.println("⚡ [Silence] cancelled before TTS sid=" + sid);
                    armSilenceAfterUserTurn(session);
                    return;
                }
                byte[] audio = ttsService.generateSpeech(resp.text, "ProfA");
                if (cancelToken.get()) {
                    System.out.println("⚡ [Silence] cancelled after TTS sid=" + sid);
                    armSilenceAfterUserTurn(session);
                    return;
                }
                if (audio == null || audio.length == 0) {
                    System.err.println("❌ [Silence] TTS 空，回退 USER_TURN");
                    turnStateService.enterUserTurn(sid);
                    armSilenceAfterUserTurn(session);
                    return;
                }
                String base64 = Base64.getEncoder().encodeToString(audio);
                // 起播前最后一次令牌检查；确认无抢占后 → SPEAKING。
                if (cancelToken.get()) {
                    armSilenceAfterUserTurn(session);
                    return;
                }
                turnStateService.enterAiSpeaking(sid);
                sendUtterBegin(session, "ProfA", 0, resp.text);
                synchronized (session) {
                    session.sendMessage(new TextMessage("[AUDIO_CHUNK]:ProfA:0:" + base64));
                }
                sendUtterEnd(session, "ProfA", 1);
                long playMs = estimatePlaybackMs(audio);
                noteAiPlaybackDuration(sid, playMs);
                transcriptService.recordAi(sid, "ProfA", "[SILENCE-L" + silenceCount + "] " + resp.text);

                if (resp.endInterview) {
                    System.out.println("🎬 [Silence] LLM 判定连续沉默 → 结束面试，" + playMs + "ms 后 cleanup");
                    // end=true 收尾必须等前端播完，否则最后一句会被 [COMMAND]:END_INTERVIEW 打断
                    scheduler.schedule(() -> {
                        try {
                            if (session.isOpen()) {
                                sendTranscript(session, "AI_ENDED");
                                synchronized (session) {
                                    session.sendMessage(new TextMessage("[COMMAND]:END_INTERVIEW"));
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("❌ [Silence] 结束消息发送失败: " + e.getMessage());
                        } finally {
                            cleanupAfterEnd(session);
                        }
                    }, playMs, TimeUnit.MILLISECONDS);
                }
                // 不结束则等前端 USER_TURN_READY 再切回，并重新 arm 下一档沉默
            } catch (Exception e) {
                System.err.println("❌ [Silence] 处理异常: " + e.getMessage());
                turnStateService.enterUserTurn(sid);
                armSilenceAfterUserTurn(session);
            }
        });
    }

    /** 群面沉默：不发单帧，直接触发 bundle 让 AI 候选人自我推进对话。 */
    private void handleGroupSilence(WebSocketSession session) {
        transcriptService.recordSystem(session.getId(), "SILENCE_GROUP_ADVANCE");
        triggerBundleReply(session, "[SILENCE] The candidate has not spoken. Continue the discussion naturally.");
    }

    /**
     * 一句 AI 回复开始。协议：`[UTTER_BEGIN]:speaker:delayMs:fullText`。
     * delayMs = 0 时前端接力播放（等前一条播完），> 0 时前端按 delayMs 相对上一条起点起播，允许 overlap。
     */
    private void sendUtterBegin(WebSocketSession session, String speaker, int delayMs, String textOrEmpty) throws IOException {
        String safeText = textOrEmpty == null ? "" : textOrEmpty;
        synchronized (session) {
            session.sendMessage(new TextMessage(
                    "[UTTER_BEGIN]:" + speaker + ":" + delayMs + ":" + safeText));
        }
    }

    /** 一句 AI 回复结束，前端可据此确认不会再有后续 chunk。totalChunks 仅供前端对账，不强制。 */
    private void sendUtterEnd(WebSocketSession session, String speaker, int totalChunks) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage("[UTTER_END]:" + speaker + ":" + totalChunks));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        Recognition recognition = recognitionMap.get(session.getId());
        if (recognition == null) return;

        // DashScope 接受 PCM 16-bit LE 16kHz 单声道字节流，导良直接丢进去
        ByteBuffer payload = message.getPayload();
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload);
        copy.flip();
        try {
            recognition.sendAudioFrame(copy);
        } catch (Exception e) {
            System.err.println("❌ [ASR] sendAudioFrame 失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sid = session.getId();
        System.out.println("👋 用户断开: " + sid);
        Recognition recognition = recognitionMap.remove(sid);
        if (recognition != null) {
            try {
                recognition.stop();
            } catch (Exception ignore) {
            }
            try {
                recognition.getDuplexApi().close(1000, "bye");
            } catch (Exception ignore) {
            }
        }
        asrRollingText.remove(sid);
        llmService.clearSession(sid);
        transcriptService.clear(sid);
        turnStateService.clear(sid);
        speculativeLLMService.clear(sid);
        interruptDetector.clear(sid);
        silenceService.clear(sid);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        System.out.println("📩 收到前端文本指令: " + payload.substring(0, Math.min(payload.length(), 50)) + "...");
        
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            
            // 判断是不是初始化指令
            if (jsonNode.has("event") && "INIT".equals(jsonNode.get("event").asText())) {
                String interviewType = jsonNode.get("interviewType").asText();
                String ps = jsonNode.get("personalStatement").asText();
                String al = jsonNode.get("activityList").asText();
                
                System.out.println("🎬 [导播台] 收到初始化请求。套餐: " + interviewType);

                // 启动对话记录（作为整场面试 t=0）
                transcriptService.start(session.getId());
                
                // 💡 这里是关键：我们调用 LLMService 进行“备课”
                llmService.initInterviewOutline(session.getId(), interviewType, ps, al);
                
                // 备课完成后，再发送开场白
                sendGreeting(session, interviewType);
            }
            // 用户主动点"结束会议"时前端会发这个事件，
            // 后端需要在 WebSocket 还活着时把 transcript 推过去（关掉就来不及了）。
            else if (jsonNode.has("event") && "USER_END".equals(jsonNode.get("event").asText())) {
                System.out.println("🚪 [导播台] 用户主动结束面试: " + session.getId());
                sendTranscript(session, "USER_ENDED");
            }
            // 前端把当前 utterance 的所有 chunk 都播完后发来这个，后端据此切回 USER_TURN。
            // 但如果前端因为 decode race / 旧缓存而"提前"发送，会在 AI 音频还没播完时进 USER_TURN 并 arm 沉默
            // ——这会导致沉默计时器在 AI 说话过程中触发，跟当前发声重合。所以走 handleUserTurnReadyFromClient 判早到。
            else if (jsonNode.has("event") && "USER_TURN_READY".equals(jsonNode.get("event").asText())) {
                handleUserTurnReadyFromClient(session);
            }
        } catch (Exception e) {
            System.err.println("❌ 解析前端指令失败: " + e.getMessage());
        }
    }

    /**
     * 流式 AI 回复：后端边接 LLM SSE、边抽句、边交 TTS、边推前端。
     *
     * 与前端的契约：
     *   [UTTER_BEGIN]:speaker:fullText   → 一个发言开始（fullText 在首句才知道时可为空）
     *   [AUDIO_CHUNK]:speaker:seq:base64 → 一句语音（seq 从 0 递增，前端严格按 seq 顺序播）
     *   [UTTER_END]:speaker:totalChunks  → 一个发言结束
     *
     * 参考的流式斶序（单个回复）：
     *   T0        用户说完
     *   T0+200ms  LLM 首 chunk，speaker 解析出来 → [UTTER_BEGIN]
     *   T0+600ms  首句 . 碰到→ TTS 开始合成
     *   T0+1000ms 首句 audio 到达→ [AUDIO_CHUNK]:seq=0 (前端开声)
     *   T0+1600ms 二句合成好 + LLM 已结束 → [AUDIO_CHUNK]:seq=1
     *   T0+1600ms [UTTER_END]（若后面没句子）
     */
    private void triggerStreamingReply(WebSocketSession session, String userText) {
        final String sid = session.getId();
        // 装填期：LLM 首 chunk + 首句 TTS 都还没出来时，用户开口应该能抢占；进 PLANNING。
        turnStateService.enterAiPlanning(sid);
        final AtomicBoolean cancelToken = newAiTaskToken(sid);
        dialogExecutor.submit(() -> {
            final AtomicInteger totalChunks = new AtomicInteger(0);
            final Object beginLock = new Object();
            final boolean[] beginSent = {false};
            final StringBuilder textAggregator = new StringBuilder();
            final String[] speakerRef = {null};

            llmService.streamInterviewerResponse(sid, userText, new LLMService.StreamCallback() {
                @Override
                public void onSpeakerResolved(String speaker) {
                    if (cancelToken.get()) return;
                    speakerRef[0] = speaker;
                    // 注意：这里还没有音频，仍保持 PLANNING；只有真正下第一帧 AUDIO 时才切 SPEAKING。
                }

                @Override
                public void onSentence(String sentence, int seq) {
                    if (cancelToken.get()) return;
                    textAggregator.append(sentence).append(' ');
                    String speaker = speakerRef[0] != null ? speakerRef[0] : "ProfA";

                    long t0 = System.currentTimeMillis();
                    byte[] audioBytes = ttsService.generateSpeech(sentence, speaker);
                    long ttsMs = System.currentTimeMillis() - t0;
                    if (cancelToken.get()) {
                        System.out.println("⚡ [Stream] cancelled after TTS seq=" + seq + " sid=" + sid);
                        return;
                    }
                    if (audioBytes == null || audioBytes.length == 0) {
                        System.err.println("⚠️ [TTS] 第 " + seq + " 句返回空音频，跳过：" + sentence);
                        return;
                    }
                    String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
                    try {
                        // 首个 audio 发送前：切 SPEAKING + 补发 UTTER_BEGIN（合成一次原子事件）
                        synchronized (beginLock) {
                            if (!beginSent[0]) {
                                if (cancelToken.get()) return;
                                turnStateService.enterAiSpeaking(sid);
                                sendUtterBegin(session, speaker, 0, "");
                                beginSent[0] = true;
                            }
                        }
                        long sendTs = System.currentTimeMillis();
                        synchronized (session) {
                            session.sendMessage(new TextMessage(
                                    "[AUDIO_CHUNK]:" + speaker + ":" + seq + ":" + base64Audio));
                        }
                        totalChunks.incrementAndGet();
                        noteAiPlaybackDuration(sid, estimatePlaybackMs(audioBytes));
                        System.out.println("🎤 [Stream] sendTs=" + sendTs + " seq=" + seq + " " + speaker
                                + " ttsMs=" + ttsMs + " audioBytes=" + audioBytes.length
                                + " sentence=" + sentence);
                    } catch (IOException e) {
                        System.err.println("❌ [Stream] 发送 AUDIO_CHUNK 失败: " + e.getMessage());
                    }
                }

                @Override
                public void onComplete(String fullJson, String action) {
                    if (cancelToken.get()) return;
                    String speaker = speakerRef[0] != null ? speakerRef[0] : "ProfA";
                    String finalText = textAggregator.toString().trim();
                    if (finalText.isEmpty()) {
                        try {
                            JsonNode j = objectMapper.readTree(fullJson);
                            if (j.has("text")) finalText = j.get("text").asText();
                            if (j.has("speaker")) speaker = j.get("speaker").asText();
                        } catch (Exception ignore) {
                            finalText = fullJson;
                        }
                    }
                    transcriptService.recordAi(sid, speaker, finalText);
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage("[AI]:" + speaker + ":" + finalText));
                            session.sendMessage(new TextMessage(
                                    "[UTTER_END]:" + speaker + ":" + totalChunks.get()));
                        }
                    } catch (IOException e) {
                        System.err.println("❌ [Stream] 发送 UTTER_END 失败: " + e.getMessage());
                    }

                    if ("end".equalsIgnoreCase(action)) {
                        System.out.println("🎬 [导播台] AI 宣告面试结束！");
                        sendTranscript(session, "AI_ENDED");
                        try {
                            synchronized (session) {
                                session.sendMessage(new TextMessage("[COMMAND]:END_INTERVIEW"));
                            }
                        } catch (IOException e) {
                            System.err.println("❌ [Stream] 发送 END_INTERVIEW 失败: " + e.getMessage());
                        }
                        cleanupAfterEnd(session);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("❌ [Stream] 会话异常: " + error.getMessage());
                    if (cancelToken.get()) return;
                    String speaker = speakerRef[0] != null ? speakerRef[0] : "ProfA";
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(
                                    "[AI]:" + speaker + ":Sorry, I encountered a network issue."));
                            session.sendMessage(new TextMessage(
                                    "[UTTER_END]:" + speaker + ":" + totalChunks.get()));
                        }
                    } catch (IOException ignore) {
                    }
                    turnStateService.enterUserTurn(sid);
                    armSilenceAfterUserTurn(session);
                }
            });
        });
    }

    /**
     * 群面 Utterance Bundle：一次 LLM 拿 primary+reactions，并行合成 TTS，按 delay_ms 顺序发帧。
     * 5.5b：前端 Web Audio 多轨按 delayMs 排 start 时刻，允许 overlap（抢话）。
     */
    private void triggerBundleReply(WebSocketSession session, String userText) {
        final String sid = session.getId();
        // 群面 bundle 整体装填耗时更大（LLM 全量 + 并行 TTS），装填期必须允许抢占。
        turnStateService.enterAiPlanning(sid);
        final AtomicBoolean cancelToken = newAiTaskToken(sid);
        dialogExecutor.submit(() -> {
            try {
                LLMService.BundleResult bundle = llmService.getBundleResponse(sid, userText);
                if (cancelToken.get()) {
                    System.out.println("⚡ [Bundle] cancelled after LLM sid=" + sid);
                    armSilenceAfterUserTurn(session);
                    return;
                }
                if (bundle == null || bundle.primaryText == null || bundle.primaryText.isEmpty()) {
                    System.err.println("❌ [Bundle] LLM 未返回可用结果，回退到 stream 单轨");
                    triggerStreamingReply(session, userText);
                    return;
                }

                List<BundleItem> items = new ArrayList<>();
                items.add(new BundleItem(bundle.primarySpeaker, bundle.primaryText, 0));
                for (LLMService.Reaction r : bundle.reactions) {
                    items.add(new BundleItem(r.speaker, r.text, r.delayMs));
                }

                long t0 = System.currentTimeMillis();
                List<java.util.concurrent.Future<byte[]>> futures = new ArrayList<>();
                for (BundleItem it : items) {
                    futures.add(dialogExecutor.submit(() -> ttsService.generateSpeech(it.text, it.speaker)));
                }
                boolean firstFrameSent = false;
                // 前一条 TTS 的估算播放时长，用来把"抢话"重写成"话尾抢话"，防止多人齐声开口。
                int prevEstMs = 0;
                for (int i = 0; i < items.size(); i++) {
                    if (cancelToken.get()) {
                        System.out.println("⚡ [Bundle] cancelled mid-send sid=" + sid + " sentIdx=" + i);
                        break;
                    }
                    BundleItem it = items.get(i);
                    byte[] audio;
                    try {
                        audio = futures.get(i).get();
                    } catch (Exception e) {
                        System.err.println("❌ [Bundle] TTS 失败 speaker=" + it.speaker + " err=" + e.getMessage());
                        continue;
                    }
                    if (cancelToken.get()) break;
                    if (audio == null || audio.length == 0) {
                        System.err.println("⚠️ [Bundle] TTS 空音频 speaker=" + it.speaker + " text=" + it.text);
                        continue;
                    }
                    // 默认接力：等前一条播完再起，避免多人齐声。仅当 LLM 明确要求"话尾抢话"
                    // (delayMs 落在 prevEstMs 前 100ms 内) 才允许小幅 overlap；否则一律抬到 prevEstMs 之后 200ms。
                    int effectiveDelayMs = it.delayMs;
                    if (i > 0 && prevEstMs > 0) {
                        int overlapMs = 100;
                        int gapMs = 200;
                        int tailStartMs = Math.max(0, prevEstMs - overlapMs);
                        if (effectiveDelayMs < tailStartMs) {
                            effectiveDelayMs = prevEstMs + gapMs;
                        }
                    }
                    String base64 = Base64.getEncoder().encodeToString(audio);
                    try {
                        // 首条真正下发前切 SPEAKING（此前保持 PLANNING 以便被抢占）
                        if (!firstFrameSent) {
                            if (cancelToken.get()) break;
                            turnStateService.enterAiSpeaking(sid);
                            firstFrameSent = true;
                        }
                        sendUtterBegin(session, it.speaker, effectiveDelayMs, it.text);
                        synchronized (session) {
                            session.sendMessage(new TextMessage(
                                    "[AUDIO_CHUNK]:" + it.speaker + ":0:" + base64));
                            session.sendMessage(new TextMessage("[AI]:" + it.speaker + ":" + it.text));
                            session.sendMessage(new TextMessage("[UTTER_END]:" + it.speaker + ":1"));
                        }
                        long estMs = estimatePlaybackMs(audio);
                        noteAiPlaybackDuration(sid, estMs);
                        prevEstMs = (int) estMs;
                        transcriptService.recordAi(sid, it.speaker, it.text);
                        System.out.println("🎬 [Bundle] " + it.speaker + " delayMs=" + it.delayMs
                                + " → effective=" + effectiveDelayMs + " estMs=" + estMs
                                + " text=" + it.text);
                    } catch (IOException e) {
                        System.err.println("❌ [Bundle] 发送帧失败: " + e.getMessage());
                    }
                }
                long totalMs = System.currentTimeMillis() - t0;
                System.out.println("🎭 [Bundle] 完成 primary+" + bundle.reactions.size()
                        + " reactions 并行 TTS 总耗时=" + totalMs + "ms");

                if (!cancelToken.get() && "end".equalsIgnoreCase(bundle.action)) {
                    System.out.println("🎬 [Bundle] AI 宣告面试结束！");
                    sendTranscript(session, "AI_ENDED");
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage("[COMMAND]:END_INTERVIEW"));
                        }
                    } catch (IOException ignore) {
                    }
                    cleanupAfterEnd(session);
                }
            } catch (Exception e) {
                System.err.println("❌ [Bundle] 异常: " + e.getMessage());
                e.printStackTrace();
                turnStateService.enterUserTurn(sid);
                armSilenceAfterUserTurn(session);
            }
        });
    }

    /** Bundle 内部小结构：一条发言 + 它相对上一条的 delayMs。 */
    private static final class BundleItem {
        final String speaker;
        final String text;
        final int delayMs;
        BundleItem(String speaker, String text, int delayMs) {
            this.speaker = speaker;
            this.text = text;
            this.delayMs = delayMs;
        }
    }

    /**
     * 把整段对话记录 + 节奏统计推给前端，便于 EndPage 渲染和后续反馈生成。
     */
    private void sendTranscript(WebSocketSession session, String reason) {
        try {
            transcriptService.recordSystem(session.getId(), "INTERVIEW_ENDED:" + reason);
            String json = transcriptService.snapshotAsJson(session.getId());
            session.sendMessage(new TextMessage("[TRANSCRIPT]:" + json));
            System.out.println("📜 [Transcript] 已推送 " + json.length() + " 字符给前端 (" + reason + ")");

            // 异步生成反馈：不能让 WebSocket 主线程等 LLM 5-15 秒
            triggerFeedbackAsync(session, json);
        } catch (Exception e) {
            System.err.println("❌ 推送 transcript 失败: " + e.getMessage());
        }
    }

    /**
     * 在独立线程里调 LLM 生成反馈，完成后通过 WebSocket 推 [FEEDBACK]: 帧。
     * 注意：客户端有可能在这之前就主动关掉了 WS（用户等不及直接 cleanupAndExit）—
     * 那种情况下推送会失败、被 catch 掉，不影响系统稳定。
     */
    private void triggerFeedbackAsync(WebSocketSession session, String transcriptJson) {
        final String sid = session.getId();
        feedbackExecutor.submit(() -> {
            try {
                System.out.println("🧠 [Feedback] 开始为 " + sid + " 生成反馈...");
                String feedbackJson = feedbackService.generate(sid, transcriptJson);

                // 推送前再检查一次 session 是否还活着
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage("[FEEDBACK]:" + feedbackJson));
                    }
                    System.out.println("📨 [Feedback] 已推送给前端 " + sid);
                } else {
                    System.out.println("⚠️ [Feedback] session 已关闭，丢弃: " + sid);
                }
            } catch (Exception ex) {
                System.err.println("❌ [Feedback] 异步任务异常: " + ex.getMessage());
            }
        });
    }

}