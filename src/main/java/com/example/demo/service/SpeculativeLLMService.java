package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Speculative LLM 引擎：
 *   - onPartial(sid, text) 收到稳定 partial 时 debounce 触发 attempt；
 *   - attempt 内部调 {@link LLMService#streamInterviewerResponse}（persistToMemory=false），
 *     并把每一句提前合成为 TTS 音频缓存；
 *   - tryCommit(sid, finalText) 在真实 VAD endpoint 抵达时被 AudioHandler 调用；
 *     若缓存命中，返回 CachedResult 供 AudioHandler 直接播；
 *   - 若 partial 又变化，前一个 attempt 被 cancel，新起一份。
 *
 * 命中判定采用宽松前缀比较（大小写不敏感、去空格，长度差 ≤ commitLenTolerance），
 * 匹配保守，未命中则走 AudioHandler 原有 fallback（跟旧逻辑无差别）。
 */
@Service
public class SpeculativeLLMService {

    private final LLMService llmService;
    private final TTSService ttsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${speculative.enabled:true}")
    private boolean enabled;

    @Value("${speculative.min-partial-len:8}")
    private int minPartialLen;

    @Value("${speculative.debounce-ms:400}")
    private long debounceMs;

    @Value("${speculative.commit-len-tolerance:25}")
    private int commitLenTolerance;

    /** 只在 attempt 已经跑完（有完整 chunks）时才算命中；未跑完的一律 miss。 */
    private final Map<String, Attempt> attemptMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "spec-scheduler");
        t.setDaemon(true);
        return t;
    });

    public SpeculativeLLMService(LLMService llmService, TTSService ttsService) {
        this.llmService = llmService;
        this.ttsService = ttsService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 收到 ASR partial 时调用。同一 session 内如果 text 与当前 attempt 一致就忽略；
     * 否则取消旧 attempt 并 debounce 起新的。
     */
    public void onPartial(String sessionId, String partialText) {
        if (!enabled) return;
        if (partialText == null) return;
        String normalized = partialText.trim();
        if (normalized.length() < minPartialLen) return;

        Attempt current = attemptMap.get(sessionId);
        if (current != null && normalized.equals(current.basedOnText)) {
            return; // partial 没变，让在飞的 attempt 继续跑
        }

        // partial 已变：先干掉旧的
        cancel(sessionId, "partial-changed");

        Attempt attempt = new Attempt(sessionId, normalized);
        attemptMap.put(sessionId, attempt);
        attempt.debounceFuture = scheduler.schedule(
                () -> startAttempt(attempt), debounceMs, TimeUnit.MILLISECONDS);
    }

    /**
     * VAD endpoint 抵达。命中返回缓存（AudioHandler 直接播），未命中返回 null 走 fallback。
     */
    public CachedResult tryCommit(String sessionId, String finalText) {
        if (!enabled) return null;
        Attempt attempt = attemptMap.remove(sessionId);
        if (attempt == null) return null;

        if (!matches(attempt.basedOnText, finalText)) {
            System.out.println("⚡ [Spec] MISS mismatch spec=\"" + attempt.basedOnText + "\" final=\"" + finalText + "\"");
            attempt.cancel();
            return null;
        }

        // partial 匹配，但 attempt 可能还没跑完 → 保守判：只有 done 才算命中
        if (!attempt.done) {
            System.out.println("⚡ [Spec] MISS not-ready spec=\"" + attempt.basedOnText + "\" chunks=" + attempt.chunks.size());
            attempt.cancel();
            return null;
        }

        if (attempt.failed) {
            System.out.println("⚡ [Spec] MISS failed spec=\"" + attempt.basedOnText + "\"");
            return null;
        }

        System.out.println("⚡ [Spec] HIT spec=\"" + attempt.basedOnText + "\" chunks=" + attempt.chunks.size()
                + " speaker=" + attempt.speaker + " action=" + attempt.action);
        return new CachedResult(attempt.speaker, attempt.fullJson, attempt.action,
                new ArrayList<>(attempt.chunks), attempt.basedOnText);
    }

    /** 用户断连或异常时清 attempt。 */
    public void clear(String sessionId) {
        cancel(sessionId, "clear");
    }

    private void cancel(String sessionId, String reason) {
        Attempt attempt = attemptMap.remove(sessionId);
        if (attempt != null) {
            attempt.cancel();
            System.out.println("⚡ [Spec] cancel reason=" + reason + " sid=" + sessionId
                    + " basedOn=\"" + attempt.basedOnText + "\"");
        }
    }

    private void startAttempt(Attempt attempt) {
        if (attempt.cancelled.get()) return;
        attempt.started = true;
        System.out.println("⚡ [Spec] start sid=" + attempt.sessionId + " text=\"" + attempt.basedOnText + "\"");

        try {
            llmService.streamInterviewerResponse(attempt.sessionId, attempt.basedOnText,
                    new LLMService.StreamCallback() {
                        @Override
                        public void onSpeakerResolved(String speaker) {
                            attempt.speaker = speaker;
                        }

                        @Override
                        public void onSentence(String sentence, int seq) {
                            if (attempt.cancelled.get()) return;
                            String speaker = attempt.speaker != null ? attempt.speaker : "ProfA";
                            byte[] audioBytes = ttsService.generateSpeech(sentence, speaker);
                            if (audioBytes == null || audioBytes.length == 0) return;
                            String base64 = Base64.getEncoder().encodeToString(audioBytes);
                            synchronized (attempt.chunks) {
                                attempt.chunks.add(new CachedChunk(speaker, seq, sentence, base64));
                            }
                        }

                        @Override
                        public void onComplete(String fullJson, String action) {
                            attempt.fullJson = fullJson;
                            attempt.action = action;
                            if (attempt.speaker == null) {
                                try {
                                    JsonNode j = objectMapper.readTree(fullJson);
                                    if (j.has("speaker")) attempt.speaker = j.get("speaker").asText();
                                } catch (Exception ignore) {
                                }
                            }
                            attempt.done = true;
                            System.out.println("⚡ [Spec] done sid=" + attempt.sessionId
                                    + " chunks=" + attempt.chunks.size() + " action=" + action);
                        }

                        @Override
                        public void onError(Throwable error) {
                            attempt.failed = true;
                            attempt.done = true;
                            System.err.println("⚡ [Spec] error sid=" + attempt.sessionId + ": " + error.getMessage());
                        }
                    }, attempt.cancelled, false);
        } catch (Throwable t) {
            attempt.failed = true;
            attempt.done = true;
            System.err.println("⚡ [Spec] startAttempt 异常: " + t.getMessage());
        }
    }

    private boolean matches(String specText, String finalText) {
        if (specText == null || finalText == null) return false;
        String a = specText.trim().toLowerCase();
        String b = finalText.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty()) return false;
        if (b.startsWith(a) && (b.length() - a.length()) <= commitLenTolerance) return true;
        if (a.startsWith(b) && (a.length() - b.length()) <= commitLenTolerance) return true;
        return false;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** 每一份 attempt 都独立持有 cancel token；SpeculativeLLMService 只做路由。 */
    static final class Attempt {
        final String sessionId;
        final String basedOnText;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final List<CachedChunk> chunks = new ArrayList<>();
        volatile ScheduledFuture<?> debounceFuture;
        volatile boolean started = false;
        volatile boolean done = false;
        volatile boolean failed = false;
        volatile String speaker;
        volatile String fullJson;
        volatile String action = "continue";

        Attempt(String sessionId, String basedOnText) {
            this.sessionId = sessionId;
            this.basedOnText = basedOnText;
        }

        void cancel() {
            cancelled.set(true);
            if (debounceFuture != null) debounceFuture.cancel(false);
        }
    }

    public static final class CachedChunk {
        public final String speaker;
        public final int seq;
        public final String sentence;
        public final String base64Audio;

        public CachedChunk(String speaker, int seq, String sentence, String base64Audio) {
            this.speaker = speaker;
            this.seq = seq;
            this.sentence = sentence;
            this.base64Audio = base64Audio;
        }
    }

    public static final class CachedResult {
        public final String speaker;
        public final String fullJson;
        public final String action;
        public final List<CachedChunk> chunks;
        public final String basedOnText;

        public CachedResult(String speaker, String fullJson, String action,
                            List<CachedChunk> chunks, String basedOnText) {
            this.speaker = speaker;
            this.fullJson = fullJson;
            this.action = action;
            this.chunks = chunks;
            this.basedOnText = basedOnText;
        }
    }
}
