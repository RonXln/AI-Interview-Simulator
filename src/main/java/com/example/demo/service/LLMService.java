package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LLMService {

    // 从 .env / application.properties 注入，不再硬编码
    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.model}")
    private String modelName;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 1 新增：会话记忆库 (SessionId -> 消息历史列表),这样每个连进来的用户都有自己独立的上下文记录
    private final Map<String, ArrayNode> sessionMemory = new ConcurrentHashMap<>();
    
    // 2. 新增：保存每个用户的《面试大纲》和当前进度
    private final Map<String, String> sessionOutline = new ConcurrentHashMap<>();
    
    // 3. 新增：保存当前套餐在场的角色名单 (比如: "ProfA, ProfB, Alice")
    private final Map<String, String> sessionCast = new ConcurrentHashMap<>();

    public LLMService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    



       /**
     * 调用大模型获取回复（注入了动态大纲和动态演员）
     */
    public String getInterviewerResponse(String sessionId, String userText) {
        try {
            System.out.println("🤖 正在思考 (Session: " + sessionId + "): " + userText);

            // 1. 获取或初始化这个用户的聊天历史
            ArrayNode messages = ensureSessionMessages(sessionId);

            // 2. 把用户最新的话加入历史记录
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);

            // 3. 构建发给 DeepSeek 的请求体
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.7); 
            requestBody.putObject("response_format").put("type", "json_object"); 
            requestBody.set("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 4. 发送 HTTP 请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // 解析返回结果
                JsonNode jsonResponse = objectMapper.readTree(response.body());
                String aiReplyJson = jsonResponse.get("choices").get(0).get("message").get("content").asText();
                
                // 5. 把 AI 的回复也存入记忆库，形成完整上下文
                ObjectNode aiMsg = messages.addObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiReplyJson);
                
                // ✂️ 记忆修剪机制（保留最近 6 条）
                if (messages.size() > 7) {
                    ArrayNode trimmedMessages = objectMapper.createArrayNode();
                    trimmedMessages.add(messages.get(0)); // 死死保住 System Prompt
                    for (int i = messages.size() - 6; i < messages.size(); i++) {
                        trimmedMessages.add(messages.get(i));
                    }
                    sessionMemory.put(sessionId, trimmedMessages); 
                }

                return aiReplyJson;
            } else {
                System.err.println("❌ API 报错: " + response.statusCode() + " body: " + response.body());
                return "{\"speaker\":\"ProfA\", \"text\":\"Sorry, I encountered a network issue.\", \"action\":\"error\"}";
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"speaker\":\"System\", \"text\":\"System error occurred.\", \"action\":\"error\"}";
        }
    }
    



    /**
     * 当用户断开连接时，清除他的记忆
     */
    public void clearSession(String sessionId) {
        sessionMemory.remove(sessionId);
    }

    /**
     * Speculative 命中 commit 后调用：把用户提问 + AI 回复补写进 sessionMemory，
     * 让后续轮次的上下文与正常路径完全一致。
     */
    public void promoteToMemory(String sessionId, String userText, String assistantFullJson) {
        ArrayNode messages = ensureSessionMessages(sessionId);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userText);
        ObjectNode aiMsg = messages.addObject();
        aiMsg.put("role", "assistant");
        aiMsg.put("content", assistantFullJson);
        if (messages.size() > 7) {
            ArrayNode trimmed = objectMapper.createArrayNode();
            trimmed.add(messages.get(0));
            for (int i = messages.size() - 6; i < messages.size(); i++) {
                trimmed.add(messages.get(i));
            }
            sessionMemory.put(sessionId, trimmed);
        }
    }

    /** 判断该会话是否群面（cast 里 AI 考生 ≥ 2 人）。 */
    public boolean isGroupInterview(String sessionId) {
        String cast = sessionCast.get(sessionId);
        if (cast == null) return false;
        // 群面 cast 里必有 Alice + Bob（见 initInterviewOutline）
        return cast.contains("Alice") && cast.contains("Bob");
    }

    /** 群面 utterance bundle 里的一条附加反应。 */
    public static final class Reaction {
        public final String speaker;
        public final String text;
        public final int delayMs;

        public Reaction(String speaker, String text, int delayMs) {
            this.speaker = speaker;
            this.text = text;
            this.delayMs = delayMs;
        }
    }

    /** 群面主 utterance + reactions 的一次性组合结果。 */
    public static final class BundleResult {
        public final String primarySpeaker;
        public final String primaryText;
        public final String action;
        public final List<Reaction> reactions;
        public final String fullJson;

        public BundleResult(String primarySpeaker, String primaryText, String action,
                            List<Reaction> reactions, String fullJson) {
            this.primarySpeaker = primarySpeaker;
            this.primaryText = primaryText;
            this.action = action;
            this.reactions = reactions;
            this.fullJson = fullJson;
        }
    }

    /**
     * 群面专用：非流式一次拿完整 JSON，返回 primary + reactions。
     * 首字延迟由 primary 音频到达时机决定；reactions 由 AudioHandler 按 delayMs 排队送帧。
     */
    public BundleResult getBundleResponse(String sessionId, String userText) {
        try {
            System.out.println("🎬 [Bundle] 群面思考中 (Session: " + sessionId + "): " + userText);

            ArrayNode messages = ensureSessionMessages(sessionId);
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.8);
            requestBody.putObject("response_format").put("type", "json_object");
            requestBody.set("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("❌ [Bundle] LLM 非 200：" + response.statusCode() + " body=" + response.body());
                return null;
            }

            JsonNode top = objectMapper.readTree(response.body());
            String content = top.get("choices").get(0).get("message").get("content").asText();

            // 写入 assistant 记忆
            ObjectNode aiMsg = messages.addObject();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", content);
            if (messages.size() > 7) {
                ArrayNode trimmed = objectMapper.createArrayNode();
                trimmed.add(messages.get(0));
                for (int i = messages.size() - 6; i < messages.size(); i++) trimmed.add(messages.get(i));
                sessionMemory.put(sessionId, trimmed);
            }

            JsonNode j = objectMapper.readTree(content);
            String speaker = j.path("speaker").asText("ProfA");
            String text = j.path("text").asText("");
            String action = j.path("action").asText("continue");

            List<Reaction> reactions = new ArrayList<>();
            JsonNode reactionsNode = j.path("reactions");
            if (reactionsNode.isArray()) {
                for (JsonNode r : reactionsNode) {
                    String rs = r.path("speaker").asText("");
                    String rt = r.path("text").asText("");
                    int rd = r.path("delay_ms").asInt(300);
                    if (rs.isEmpty() || rt.isEmpty()) continue;
                    reactions.add(new Reaction(rs, rt, Math.max(0, rd)));
                }
            }
            System.out.println("🎬 [Bundle] primary=" + speaker + " reactions=" + reactions.size()
                    + " action=" + action);
            return new BundleResult(speaker, text, action, reactions, content);
        } catch (Exception e) {
            System.err.println("❌ [Bundle] 异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** LLM 打断判定结果。interrupt=false 时其他字段忽略。 */
    public static final class InterruptDecision {
        public final boolean interrupt;
        public final String speaker;
        public final String text;
        public final String reason;
        public InterruptDecision(boolean interrupt, String speaker, String text, String reason) {
            this.interrupt = interrupt;
            this.speaker = speaker;
            this.text = text;
            this.reason = reason;
        }
        public static InterruptDecision noop(String reason) {
            return new InterruptDecision(false, null, null, reason);
        }
    }

    /**
     * 用 mini LLM 判定"现在该不该打断用户"。同步阻塞，调用方负责放到 executor 里。
     * 独立 messages 上下文，不写入 sessionMemory，避免污染主对话。
     * 群面时 speaker 可以是 Alice/Bob/Charlie 等 AI 考生；单面时固定 ProfA。
     */
    public InterruptDecision judgeInterrupt(String sessionId, String userPartial, long turnElapsedMs) {
        try {
            boolean group = isGroupInterview(sessionId);
            String cast = sessionCast.getOrDefault(sessionId, "ProfA");

            String sys = "You are the moderator of a mock interview. Decide whether an interviewer should CUT IN right now to interrupt the candidate.\n"
                    + "Cast on stage: " + cast + "\n"
                    + "Interview mode: " + (group ? "GROUP (multiple AI candidates + interviewers)" : "1-on-1 (only interviewer speaks)") + "\n"
                    + "Rules:\n"
                    + "1. Interrupt ONLY if the candidate is: rambling >30s of filler, going way off-topic, factually wrong, or giving a shallow answer that begs a challenge.\n"
                    + "2. If the answer is fine or still developing → do NOT interrupt.\n"
                    + "3. In GROUP mode, prefer AI candidates (Alice/Bob/Charlie...) as the interrupter to push back / challenge. Interviewers (ProfA/ProfB) only cut in for redirect.\n"
                    + "4. In 1-on-1 mode, speaker MUST be ProfA.\n"
                    + "5. The interrupt line must be ONE short English sentence (≤15 words), natural spoken tone, no filler like 'um'.\n\n"
                    + "Return strict JSON:\n"
                    + "{\"interrupt\": true|false, \"speaker\": \"<name>\", \"text\": \"<one short sentence>\", \"reason\": \"<why in 5 words>\"}\n"
                    + "When interrupt=false, still fill speaker/text/reason with empty strings.";

            ArrayNode msgs = objectMapper.createArrayNode();
            ObjectNode sysMsg = msgs.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", sys);
            ObjectNode userMsg = msgs.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", "Candidate has been speaking for " + turnElapsedMs + " ms. Live partial transcript:\n\"" + userPartial + "\"\n\nShould we interrupt?");

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 120);
            requestBody.putObject("response_format").put("type", "json_object");
            requestBody.set("messages", msgs);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("❌ [InterruptJudge] non-200: " + resp.statusCode());
                return InterruptDecision.noop("http-" + resp.statusCode());
            }

            JsonNode top = objectMapper.readTree(resp.body());
            String content = top.get("choices").get(0).get("message").get("content").asText();
            JsonNode j = objectMapper.readTree(content);
            boolean itr = j.path("interrupt").asBoolean(false);
            String sp = j.path("speaker").asText("");
            String tx = j.path("text").asText("");
            String rs = j.path("reason").asText("");

            if (itr && (sp.isEmpty() || tx.isEmpty())) {
                return InterruptDecision.noop("empty-fields");
            }
            // 单面 speaker 强制回落 ProfA
            if (itr && !group) sp = "ProfA";
            System.out.println("🎯 [InterruptJudge] itr=" + itr + " sp=" + sp + " reason=" + rs);
            return new InterruptDecision(itr, sp, tx, rs);
        } catch (Exception e) {
            System.err.println("❌ [InterruptJudge] 异常: " + e.getMessage());
            return InterruptDecision.noop("exception");
        }
    }

    // ================================================================
    // 🌟 开场白 + 首个问题（非流式，主对话记忆里落地为 ProfA 首帧）
    // ================================================================

    /**
     * 基于备课好的 outline 生成 "自然欢迎语 + 第一个真问题" 的完整开场白。
     * 备课未完成时用兜底文本。生成的文本会写入主对话记忆，避免后续 LLM 忘了自己问过什么。
     */
    public String generateOpening(String sessionId) {
        String outline = sessionOutline.get(sessionId);
        String cast = sessionCast.getOrDefault(sessionId, "ProfA (主考官)");
        boolean group = isGroupInterview(sessionId);

        String fallback = group
            ? "Welcome everyone. Today's group discussion topic is a challenging real-world scenario. Let's start by hearing your initial thoughts."
            : "Hello, welcome to your interview. I've reviewed your materials. Let's begin with this: tell me about the moment in your background that pushed you toward this field.";

        if (outline == null || outline.isBlank()) {
            System.out.println("⚠️ [Opening] outline 未就绪，使用兜底开场白");
            appendAssistant(sessionId, fallback);
            return fallback;
        }

        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", modelName);
            req.put("temperature", 0.7);
            req.put("max_tokens", 200);

            ArrayNode messages = req.putArray("messages");
            String sys = group
                ? "You are ProfA, chair of a group interview panel. You have the following pre-planned case outline:\n"
                    + outline + "\n\n"
                    + "Cast in this session: " + cast + ".\n"
                    + "Speak ONE short opening turn (2-3 sentences, English):\n"
                    + "1. Briefly welcome the group to the discussion.\n"
                    + "2. State the case scenario in 1 concise sentence.\n"
                    + "3. Invite anyone to open with their first thought.\n"
                    + "Do NOT list phases. Do NOT introduce yourself by name. Speak naturally as a human professor would."
                : "You are ProfA, a warm but sharp admissions interviewer. You have the following pre-planned probe points:\n"
                    + outline + "\n\n"
                    + "Speak ONE short opening turn (2-3 sentences, English):\n"
                    + "1. Briefly welcome the candidate.\n"
                    + "2. Ask the FIRST real probe question based on point 1 of the outline — make it specific to the candidate's background, not generic.\n"
                    + "Do NOT list all your questions. Do NOT say 'let's start the first question' without actually asking one. Speak naturally.";
            messages.addObject().put("role", "system").put("content", sys);
            messages.addObject().put("role", "user").put("content", "Please deliver the opening turn now.");

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("❌ [Opening] http-" + resp.statusCode() + " -> fallback");
                appendAssistant(sessionId, fallback);
                return fallback;
            }
            JsonNode top = objectMapper.readTree(resp.body());
            String text = top.get("choices").get(0).get("message").get("content").asText().trim();
            if (text.isEmpty()) {
                appendAssistant(sessionId, fallback);
                return fallback;
            }
            appendAssistant(sessionId, text);
            System.out.println("✅ [Opening] 生成完成 (" + text.length() + " chars)");
            return text;
        } catch (Exception e) {
            System.err.println("❌ [Opening] 异常: " + e.getMessage() + " -> fallback");
            appendAssistant(sessionId, fallback);
            return fallback;
        }
    }

    private void appendAssistant(String sessionId, String text) {
        ObjectNode msg = ensureSessionMessages(sessionId).addObject();
        msg.put("role", "assistant");
        msg.put("content", text);
    }

    // ================================================================
    // 🌟 沉默应对（单面专用，非流式，落主记忆）
    // ================================================================

    /** 沉默应对结果：ProfA 的一句台词 + 是否宣告面试结束。 */
    public static final class SilenceResponse {
        public final String text;
        public final boolean endInterview;
        public SilenceResponse(String text, boolean endInterview) {
            this.text = text;
            this.endInterview = endInterview;
        }
    }

    /**
     * 用户连续沉默 N 次时让 ProfA 生成一句真实反应。
     * silenceCount：1=试探/听不见；2=换问题；3=允许 LLM 判断结束面试。
     * 返回值总是可用（异常/超时走兜底文案）。
     */
    public SilenceResponse generateSilenceResponse(String sessionId, int silenceCount, boolean mayEnd) {
        String outline = sessionOutline.getOrDefault(sessionId, "");
        String fallbackText;
        boolean fallbackEnd = false;
        switch (silenceCount) {
            case 1:  fallbackText = "Can you hear me okay? Take your time — I'm listening."; break;
            case 2:  fallbackText = "This one seems tough. Let's move on to a different question."; break;
            default: fallbackText = "It seems you're not able to respond right now. I'll wrap up here — thank you for your time today.";
                     fallbackEnd = true;
                     break;
        }

        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("model", modelName);
            req.put("temperature", 0.6);
            req.put("max_tokens", 220);
            req.put("response_format", objectMapper.createObjectNode().put("type", "json_object"));

            ArrayNode messages = req.putArray("messages");
            String sys = "You are ProfA, a warm but sharp admissions interviewer. "
                + "The candidate has been SILENT for " + silenceCount + " consecutive prompt cycle(s). "
                + "React the way a real human interviewer would.\n\n"
                + "Silence level rules:\n"
                + " - level 1: gently check if the candidate is still there / heard the question. Do NOT change the question yet.\n"
                + " - level 2: acknowledge the difficulty, move on to a DIFFERENT probe from your outline.\n"
                + " - level 3: it is acceptable to end the interview politely. Only end if it feels natural — otherwise try one final specific rescue question.\n\n"
                + "Outline you had prepared:\n" + outline + "\n\n"
                + "Return STRICT JSON only, no prose, no code fences:\n"
                + "{\n"
                + "  \"text\": \"one short natural English utterance ProfA speaks (max 2 sentences)\",\n"
                + "  \"endInterview\": " + (mayEnd ? "true or false" : "false") + "\n"
                + "}";
            messages.addObject().put("role", "system").put("content", sys);
            messages.addObject().put("role", "user")
                    .put("content", "Deliver ProfA's silence response now for level " + silenceCount + ".");

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(12))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("❌ [Silence] http-" + resp.statusCode() + " -> fallback");
                appendAssistant(sessionId, fallbackText);
                return new SilenceResponse(fallbackText, fallbackEnd && mayEnd);
            }
            JsonNode top = objectMapper.readTree(resp.body());
            String raw = top.get("choices").get(0).get("message").get("content").asText();
            JsonNode j = objectMapper.readTree(raw);
            String text = j.has("text") ? j.get("text").asText("").trim() : "";
            boolean end = mayEnd && j.has("endInterview") && j.get("endInterview").asBoolean(false);
            if (text.isEmpty()) {
                appendAssistant(sessionId, fallbackText);
                return new SilenceResponse(fallbackText, fallbackEnd && mayEnd);
            }
            appendAssistant(sessionId, text);
            System.out.println("✅ [Silence] level=" + silenceCount + " end=" + end + " text=" + text);
            return new SilenceResponse(text, end);
        } catch (Exception e) {
            System.err.println("❌ [Silence] 异常: " + e.getMessage() + " -> fallback");
            appendAssistant(sessionId, fallbackText);
            return new SilenceResponse(fallbackText, fallbackEnd && mayEnd);
        }
    }

    // ================================================================
    // 🌟 流式推理：LLM SSE边讲、后端边抽句、前端边听
    // ================================================================

    /**
     * 流式回调。不强制实现四个方法都非空，调用方自己负责线程安全。
     */
    public interface StreamCallback {
        /** speaker 解析出来后只会回调一次。 */
        default void onSpeakerResolved(String speaker) {}

        /** 每抽到一句就回调，seq 从 0 递增，同一条回复内全局唯一。 */
        void onSentence(String sentence, int seq);

        /** 全部完成时回调，fullJson 是拼接后的完整回复，action 为 "continue" / "end" / "error"。 */
        void onComplete(String fullJson, String action);

        /** 流中断、HTTP 失败、冗余异常均回调到这里。 */
        default void onError(Throwable error) {}
    }

    /**
     * 与 {@link #getInterviewerResponse} 语义一致但采用 SSE 流式。
     *
     * 主要目的：把“等待整句 JSON”的 1.5-4s 延迟，拆成“首句 ≈ 500ms”，
     * 演练时只要首句抽出来就可以交给 TTS，后续句子与 LLM 生成并行。
     */
    public void streamInterviewerResponse(String sessionId, String userText, StreamCallback callback) {
        streamInterviewerResponse(sessionId, userText, callback, null, true);
    }

    /**
     * 高级入口：支持中途取消（cancel token）与可选跳过写入 sessionMemory。
     * - persistToMemory=false 用于 speculative：先跑不落地，命中后再由 promoteToMemory 补写。
     * - cancelToken 由外部持有；置 true 后当前流会尽快退出且不写记忆。
     */
    public void streamInterviewerResponse(String sessionId, String userText, StreamCallback callback,
                                          AtomicBoolean cancelToken, boolean persistToMemory) {
        try {
            System.out.println("🤖 [Stream] 思考中 (Session: " + sessionId + ", persist=" + persistToMemory + "): " + userText);

            ArrayNode messages = ensureSessionMessages(sessionId);

            // 推测模式：不写入共享 messages，而是在本次调用范围内 clone 一份，避免污染上下文
            ArrayNode workingMessages;
            if (persistToMemory) {
                workingMessages = messages;
                ObjectNode userMsg = workingMessages.addObject();
                userMsg.put("role", "user");
                userMsg.put("content", userText);
            } else {
                workingMessages = messages.deepCopy();
                ObjectNode userMsg = workingMessages.addObject();
                userMsg.put("role", "user");
                userMsg.put("content", userText);
            }

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.7);
            requestBody.put("stream", true);
            requestBody.putObject("response_format").put("type", "json_object");
            requestBody.set("messages", workingMessages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                System.err.println("❌ [Stream] LLM 非 200：" + response.statusCode() + " body=" + errBody);
                callback.onError(new RuntimeException("LLM stream status " + response.statusCode()));
                return;
            }

            StreamJsonExtractor extractor = new StreamJsonExtractor();
            StringBuilder rawJson = new StringBuilder();
            int seq = 0;
            boolean speakerNotified = false;
            boolean aborted = false;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelToken != null && cancelToken.get()) {
                        aborted = true;
                        System.out.println("⏹️ [Stream] 已取消 (Session: " + sessionId + ")");
                        break;
                    }
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;

                    try {
                        JsonNode chunk = objectMapper.readTree(data);
                        JsonNode contentNode = chunk.path("choices").path(0).path("delta").path("content");
                        if (contentNode.isMissingNode() || contentNode.isNull()) continue;
                        String piece = contentNode.asText();
                        if (piece.isEmpty()) continue;
                        rawJson.append(piece);

                        List<String> newSentences = extractor.feed(piece);

                        if (!speakerNotified && extractor.getSpeaker() != null) {
                            callback.onSpeakerResolved(extractor.getSpeaker());
                            speakerNotified = true;
                        }
                        for (String sentence : newSentences) {
                            if (cancelToken != null && cancelToken.get()) { aborted = true; break; }
                            callback.onSentence(sentence, seq++);
                        }
                        if (aborted) break;
                    } catch (Exception parseErr) {
                        // 单行解析失败不影响后续，继续向下读
                        System.err.println("⚠️ [Stream] 丢弃一行：" + parseErr.getMessage());
                    }
                }
            }

            if (aborted) {
                // 取消场景：不写记忆、不 flush、不通知 onComplete
                return;
            }

            String fullContent = rawJson.toString();

            if (persistToMemory) {
                ObjectNode aiMsg = messages.addObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", fullContent);

                if (messages.size() > 7) {
                    ArrayNode trimmed = objectMapper.createArrayNode();
                    trimmed.add(messages.get(0));
                    for (int i = messages.size() - 6; i < messages.size(); i++) {
                        trimmed.add(messages.get(i));
                    }
                    sessionMemory.put(sessionId, trimmed);
                }
            }

            // 解析 action（位于 JSON 尾部，这时才能拿到）
            String action = "continue";
            String finalSpeaker = extractor.getSpeaker();
            try {
                JsonNode full = objectMapper.readTree(fullContent);
                if (full.has("action")) action = full.get("action").asText("continue");
                if (finalSpeaker == null && full.has("speaker")) {
                    finalSpeaker = full.get("speaker").asText();
                    callback.onSpeakerResolved(finalSpeaker);
                }
            } catch (Exception ignore) {
                // 流中非法 JSON 也先不堵断，后面 tail sentence 会养底
            }

            // flush 存留的“未碰到句尾可末尾就断了”的那句
            for (String tail : extractor.flush()) {
                callback.onSentence(tail, seq++);
            }

            callback.onComplete(fullContent, action);
        } catch (Exception e) {
            e.printStackTrace();
            callback.onError(e);
        }
    }

    /** 抽取后供两个入口共享。 */
    private ArrayNode ensureSessionMessages(String sessionId) {
        return sessionMemory.computeIfAbsent(sessionId, id -> {
            ArrayNode initMessages = objectMapper.createArrayNode();
            String currentCast = sessionCast.getOrDefault(sessionId, "ProfA (主考官)");
            String currentOutline = sessionOutline.getOrDefault(sessionId, "1. 询问候选人的基本背景。");
            boolean isGroup = currentCast.contains("Alice") && currentCast.contains("Bob");
            ObjectNode systemMsg = initMessages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", buildSystemPrompt(currentCast, currentOutline, isGroup));
            return initMessages;
        });
    }

    private static String buildSystemPrompt(String currentCast, String currentOutline, boolean groupMode) {
        String common =
                "你是一个高级AI虚拟面试系统的核心导播引擎。\n\n" +
                "【当前房间内的角色阵容】\n" + currentCast + "\n\n" +
                "【本场面试大纲 (基于候选人真实资料生成)】\n" + currentOutline + "\n\n" +
                "【你的核心任务】\n" +
                "0. 仔细阅读上下文，推进面试流程。根据《面试大纲》，决定现在该由哪个角色发言或对候选人进行提问或回应。\n" +
                "1. ⚠️ 深度追问机制 (Drill-down)：绝对不要急于进入大纲的下一个问题！在进入下一题前，必须针对候选人的回答，安排 ProfB (追问考官) 或急躁的 AI 考生进行至少 1 到 2 轮的尖锐反驳或细节追问。必须逼问出具体的例子或真实想法。\n" +
                "3. 发言必须符合该角色的性格设定。\n" +
                "4. ⚠️ 强制长度限制：每人每次发言尽可能只说1到3句话，绝不可以说废话！\n" +
                "5. ⚠️ 强制语言限制：所有角色的台词 (text 字段) 必须使用纯英文 (English) 输出！\n" +
                "6. ⚠️ 结束机制：如果你觉得《面试大纲》上的所有问题都已经问完了，或者候选人表现出了结束的意愿，请让 ProfA 说结束语（如 'Thank you for your time today. We will be in touch.'），并且必须在 JSON 中输出 \"action\": \"end\"。\n\n";

        if (!groupMode) {
            return common +
                "【强制输出格式】(你只能输出合法的 JSON，不要有任何 Markdown 标记)：\n" +
                "{\n" +
                "  \"speaker\": \"填入发言的角色代号(例如 ProfA, ProfB)\",\n" +
                "  \"text\": \"角色的纯英文台词\",\n" +
                "  \"action\": \"continue\"\n" +
                "}";
        }

        return common +
            "2. ⚠️ 群面抢话机制：这是一场群面！primary 发言人说话时，其他 AI 考生会打断、抢话、反驳。你必须在 reactions 数组里安排 1-3 个其他 AI 考生（不能与 primary speaker 重复）说话，每人 1 句英文短话，语气必须有互动感（反驳、附和、抢话、补刀）。delay_ms 表示这条反应相对上一条 utterance 结束的延迟毫秒，60% 的 reactions 必须 delay_ms ≤ 150（表示强抢话）。ProfA/ProfB 是考官不加入 reactions。\n" +
            "7. reactions 里的 speaker 只能是 AI 考生（Alice/Bob/Charlie/David 等），且必须与 primary speaker 不同。\n\n" +
            "【强制输出格式】(你只能输出合法的 JSON，不要有任何 Markdown 标记)：\n" +
            "{\n" +
            "  \"speaker\": \"primary 发言者代号 (ProfA/ProfB/Alice/Bob/...)\",\n" +
            "  \"text\": \"primary 的纯英文台词\",\n" +
            "  \"action\": \"continue\",\n" +
            "  \"reactions\": [\n" +
            "    {\"speaker\": \"另一个AI考生\", \"text\": \"英文短话\", \"delay_ms\": 120},\n" +
            "    {\"speaker\": \"再一个AI考生\", \"text\": \"英文短话\", \"delay_ms\": 200}\n" +
            "  ]\n" +
            "}";
    }

    /**
     * 从 SSE 均匀到达的拼接 JSON 中抽句。
     *
     * 设计要点：
     *  - 不先等 JSON 完整（那里面 action 在尾部，会抜高首句延迟）；
     *  - 同时维护 "字符重组态”与 "JSON 转义态"，避免在 \\\" 里面断句；
     *  - 遇到 . ! ? ; 且当前句已够长（MIN_SENTENCE_LEN） 时切一刀；
     *  - text 字段 value 结束的 " 上时，也给尾句切一刀。
     */
    static final class StreamJsonExtractor {
        private static final Pattern TEXT_KEY = Pattern.compile("\"text\"\\s*:\\s*\"");
        private static final Pattern SPEAKER_VALUE = Pattern.compile("\"speaker\"\\s*:\\s*\"([^\"]+)\"");
        private static final int MIN_SENTENCE_LEN = 4;

        private final StringBuilder raw = new StringBuilder();
        private final StringBuilder current = new StringBuilder();
        private String speaker = null;
        private int textValueStart = -1;
        private int scanPos = 0;
        private boolean textEnded = false;

        List<String> feed(String piece) {
            raw.append(piece);
            List<String> out = new ArrayList<>();

            if (speaker == null) {
                Matcher m = SPEAKER_VALUE.matcher(raw);
                if (m.find()) speaker = m.group(1);
            }

            if (textEnded) return out;

            if (textValueStart == -1) {
                Matcher m = TEXT_KEY.matcher(raw);
                if (!m.find()) return out;
                textValueStart = m.end();
                scanPos = textValueStart;
            }

            while (scanPos < raw.length()) {
                char c = raw.charAt(scanPos);

                if (c == '\\') {
                    // 处理 JSON 转义。若 raw 未拉完整个转义序列，就等下一个 piece。
                    if (scanPos + 1 >= raw.length()) break;
                    char next = raw.charAt(scanPos + 1);
                    if (next == 'u') {
                        if (scanPos + 6 > raw.length()) break;
                        try {
                            int cp = Integer.parseInt(raw.substring(scanPos + 2, scanPos + 6), 16);
                            current.append((char) cp);
                        } catch (NumberFormatException ignore) {
                            // 非法转义，直接跳过
                        }
                        scanPos += 6;
                    } else {
                        current.append(decodeEscape(next));
                        scanPos += 2;
                    }
                    continue;
                }

                if (c == '"') {
                    // text value 结束：把尾句也 flush 出去
                    String tail = current.toString().trim();
                    if (!tail.isEmpty()) out.add(tail);
                    current.setLength(0);
                    textEnded = true;
                    break;
                }

                current.append(c);
                scanPos++;

                if (isSentenceBoundary(c) && current.length() >= MIN_SENTENCE_LEN) {
                    String s = current.toString().trim();
                    if (!s.isEmpty()) out.add(s);
                    current.setLength(0);
                }
            }
            return out;
        }

        /** 流结束后拿回没碰到断句符的尾句。 */
        List<String> flush() {
            List<String> out = new ArrayList<>();
            if (current.length() > 0) {
                String s = current.toString().trim();
                if (!s.isEmpty()) out.add(s);
                current.setLength(0);
            }
            return out;
        }

        String getSpeaker() { return speaker; }

        private static char decodeEscape(char c) {
            switch (c) {
                case 'n': return '\n';
                case 't': return '\t';
                case 'r': return '\r';
                case 'b': return '\b';
                case 'f': return '\f';
                case '"': return '"';
                case '\\': return '\\';
                case '/': return '/';
                default: return c;
            }
        }

        private static boolean isSentenceBoundary(char c) {
            return c == '.' || c == '!' || c == '?' || c == ';';
        }
    }
         /**
     * 🎬 导播台备课：根据文书生成面试大纲/群面案例，并设定演员阵容
     */
    public void initInterviewOutline(String sessionId, String interviewType, String personalStatement, String activityList) {
        System.out.println("🎬 [导播台] 开始为 Session " + sessionId + " 备课...");

        // 1. 确定卡司阵容
        String cast;
        if ("2v6".equals(interviewType)) {
            cast = "ProfA (主考官), ProfB (追问考官), Alice (AI考生-温和), Bob (AI考生-急躁), Charlie (AI考生-数据控), David (AI考生-英伦逻辑派), Emma (AI考生-和事佬)";
        } else if ("2v1".equals(interviewType)) {
            cast = "ProfA (主考官), ProfB (追问考官)";
        } else {
            cast = "ProfA (主考官)";
        }
        sessionCast.put(sessionId, cast);

        // 2. 调用大模型进行备课
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.8); // 稍微提高一点温度，让群面案例更有创意

            ArrayNode messages = requestBody.putArray("messages");
             String userInfo = "Personal Statement:\n" + personalStatement + "\n\nActivity List:\n" + activityList;
            // 🌟 核心业务分流：单面挖简历，群面给案例
            if ("2v6".equals(interviewType)) {
                 // 【群面逻辑】根据文书推断专业，生成该专业的群面 Case
                messages.addObject().put("role", "system")
                        .put("content", "你是顶尖大学的招生办教授。请先根据申请者的文书，推断出他想申请的【专业领域】（如：人工智能、金融商业、公共卫生、环境保护等）。\n" +
                                        "然后，为接下来的无领导小组讨论(Group Discussion)生成一个与该【专业领域】紧密相关的、具有高度争议性的情景案例（必须包含利益冲突或伦理困境）。\n" +
                                        "请简短地输出：1. 案例背景（50字以内）。 2. 讨论的三个核心推进阶段。不要说废话，直接输出大纲。");
                messages.addObject().put("role", "user").put("content", userInfo);
                
            } else {
                // 【单面逻辑】深挖个人文书
                messages.addObject().put("role", "system")
                        .put("content", "你是招生办主任。请根据申请者的 Personal Statement 和 Activity List，提取出 5 个最值得在面试中深挖的考察点（必须简短且一针见血）。不要寒暄，直接输出1,2,3点。");
                messages.addObject().put("role", "user").put("content", userInfo);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(response.body());
                String outline = jsonResponse.get("choices").get(0).get("message").get("content").asText();
                
                sessionOutline.put(sessionId, outline);
                System.out.println("✅ [导播台] 备课完成！大纲/案例为：\n" + outline);
            } else {
                System.err.println("❌ 备课失败: " + response.body());
                sessionOutline.put(sessionId, "1. Discuss a controversial ethical topic. 2. Reach a consensus.");
            }
        } catch (Exception e) {
            System.err.println("❌ 备课时发生异常: " + e.getMessage());
            sessionOutline.put(sessionId, "1. Discuss a controversial ethical topic. 2. Reach a consensus.");
        }
    }

}