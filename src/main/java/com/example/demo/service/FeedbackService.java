package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 单一职责：把一份完整 transcript JSON 喂给 LLM，
 * 让它产出结构化的"面试反馈"JSON。
 *
 * 不负责：
 *   - 维护 transcript（TranscriptService 的事）
 *   - WebSocket 推送（AudioHandler 的事）
 *   - 反馈 UI 渲染（前端 EndPage 的事）
 *
 * 设计取舍：
 *   - 反馈生成可能耗时 5~15s，必须在 LLM 上单独发一次请求，
 *     而且不能阻塞 WebSocket 主线程 —— 由调用方包到 CompletableFuture 里。
 *   - 输出格式严格约束为 JSON，让前端可以直接结构化渲染。
 *   - System Prompt 强调"必须引用 transcript 里的具体 relativeMs / speaker"，
 *     这样 improvements 部分才能给出'某时刻 X 说了 Y 之后你应该怎样'的精准建议。
 */
@Service
public class FeedbackService {

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.url}")
    private String apiUrl;

    @Value("${deepseek.model}")
    private String modelName;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FeedbackService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 同步调用 LLM 生成反馈。调用方应在独立线程里跑。
     *
     * @param transcriptJson TranscriptService.snapshotAsJson 的输出
     * @return 严格的反馈 JSON 字符串。失败时返回一份"降级"JSON，
     *         保证前端永远能渲染（不是空白）。
     */
    public String generate(String sessionId, String transcriptJson) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.5);
            requestBody.putObject("response_format").put("type", "json_object");

            ArrayNode messages = requestBody.putArray("messages");

            messages.addObject()
                    .put("role", "system")
                    .put("content", buildSystemPrompt());

            messages.addObject()
                    .put("role", "user")
                    .put("content",
                            "Here is the full interview transcript and pace stats. " +
                            "Produce the feedback JSON now.\n\n" +
                            transcriptJson);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                System.err.println("❌ [Feedback] LLM 失败: " + resp.statusCode() + " body=" + resp.body());
                return fallback("LLM returned status " + resp.statusCode());
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.get("choices").get(0).get("message").get("content").asText();

            // 校验一下是不是合法 JSON，省得前端炸
            try {
                objectMapper.readTree(content);
            } catch (Exception parseErr) {
                System.err.println("❌ [Feedback] LLM 返回的不是合法 JSON: " + parseErr.getMessage());
                return fallback("LLM produced invalid JSON");
            }

            System.out.println("✅ [Feedback] 反馈生成完成 (session=" + sessionId
                    + ", 长度=" + content.length() + ")");
            return content;

        } catch (Exception e) {
            e.printStackTrace();
            return fallback(e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        // 把 prompt 抽到方法里，方便后续从配置文件/数据库读
        return ""
            + "You are a top-tier admissions interview coach. You receive a structured JSON transcript "
            + "of a mock interview (with relativeMs timestamps, speakers, roles, and pace statistics) "
            + "and must produce a **strictly structured feedback JSON** in Simplified Chinese.\n\n"

            + "## REQUIRED OUTPUT SHAPE (no markdown, no extra prose)\n"
            + "{\n"
            + "  \"recap\": \"整场面试的复盘叙述。按阶段（开场→深入→压力测试→收尾）描述每一段发生了什么，候选人表现如何\",\n"
            + "  \"overallPerformance\": {\n"
            + "    \"score\": 0-100 的整数,\n"
            + "    \"summary\": \"一句话总评\",\n"
            + "    \"strengths\": [\"亮点1\", \"亮点2\", ...],\n"
            + "    \"weaknesses\": [\"短板1\", \"短板2\", ...]\n"
            + "  },\n"
            + "  \"languagePerformance\": {\n"
            + "    \"language\": \"对用词、语法、表达精确度、专业术语使用的点评\",\n"
            + "    \"pace\": \"基于 userStats.wordsPerMinute 和 paceLabel 的语速分析\",\n"
            + "    \"rhythm\": \"基于 userStats.avgThinkingDelayMs 和实际段落分布的节奏分析（停顿是否得当、抢答还是拖沓）\",\n"
            + "    \"fillerWords\": \"口头禅观察（'um', 'like', 'you know' 等，如果没有则写'未发现明显口头禅'\"\n"
            + "  },\n"
            + "  \"improvements\": [\n"
            + "    {\n"
            + "      \"stage\": \"该建议针对的面试阶段（如 开场自我介绍 / 项目深挖 / 群面冲突点 等）\",\n"
            + "      \"atMs\": 触发时刻的 relativeMs（来自 transcript.entries）,\n"
            + "      \"trigger\": \"在这个时刻，谁说了什么，原话节选（≤30词，可英可中）\",\n"
            + "      \"yourReply\": \"候选人当时的回答节选（≤30词）\",\n"
            + "      \"betterApproach\": \"你建议的更好回答或表达策略（具体到能直接照着说的程度）\",\n"
            + "      \"rationale\": \"为什么这样更好（1-2句）\"\n"
            + "    }\n"
            + "  ]\n"
            + "}\n\n"

            + "## CRITICAL RULES\n"
            + "1. improvements 至少 3 条，至多 6 条。每条必须挂在一个真实存在的 relativeMs 上（从 transcript.entries 里挑）。\n"
            + "2. improvements 必须覆盖多个不同 stage，不要全堆在一处。\n"
            + "3. 候选人是 role=USER 的发言；考官是 INTERVIEWER；同辈 AI 考生是 PEER_CANDIDATE。\n"
            + "4. 如果候选人发言极少（< 3 条），仍要生成反馈，但要在 weaknesses 里指出'参与度不足'，"
            + "且 improvements 必须给出'如何主动抢话'的具体策略。\n"
            + "5. 严禁输出 markdown 代码块包裹、严禁加 ```json、严禁说任何额外的话。仅输出合法 JSON。\n"
            + "6. 所有用户可见文字（recap、summary、strengths、weaknesses、language、pace、rhythm、fillerWords、"
            + "stage、betterApproach、rationale）使用简体中文。trigger 和 yourReply 保留原文（通常是英文）。\n"
            + "7. 教学彩蛋：transcript.entries 里 duringAiTurn=true 的用户发言 = 候选人在 AI 说话时抢话了。"
            + "遇到这种条目必须至少产出 1 条专门的 improvements 项，stage 写作'抢话时机'，"
            + "betterApproach 里点评抢话时机是否合适（是否打断了考官关键陈述、是否错过了 AI 考生留下的破绽），"
            + "并给出'什么时候可以抢、怎么礼貌地抢'的具体话术示例。若整场没有 duringAiTurn=true 条目，"
            + "则在 improvements 里增补 1 条 stage='抢话时机'的教学建议，"
            + "指出候选人全程被动等待、缺少主动 push 的时机，给一个下次可以尝试的具体切入点。\n";
    }

    /**
     * 兜底反馈：永远是合法 JSON，让前端始终能渲染。
     */
    private String fallback(String reason) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("recap", "反馈生成暂时不可用（" + reason + "），但你的对话记录已完整保存在本页。");
            ObjectNode overall = root.putObject("overallPerformance");
            overall.put("score", 0);
            overall.put("summary", "反馈生成失败，请稍后重试或联系管理员。");
            overall.putArray("strengths");
            overall.putArray("weaknesses");
            ObjectNode lang = root.putObject("languagePerformance");
            lang.put("language", "暂无");
            lang.put("pace", "暂无");
            lang.put("rhythm", "暂无");
            lang.put("fillerWords", "暂无");
            root.putArray("improvements");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // 最终兜底：手写 JSON 字面量
            return "{\"recap\":\"反馈生成失败\",\"overallPerformance\":{\"score\":0,"
                 + "\"summary\":\"\",\"strengths\":[],\"weaknesses\":[]},"
                 + "\"languagePerformance\":{\"language\":\"\",\"pace\":\"\","
                 + "\"rhythm\":\"\",\"fillerWords\":\"\"},\"improvements\":[]}";
        }
    }
}
