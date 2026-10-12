package com.example.demo.service;

import com.example.demo.service.TranscriptEntry.EntryType;
import com.example.demo.service.TranscriptEntry.SpeakerRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单一职责：记录整场面试中所有"谁在什么时刻说了什么"。
 *
 * 不负责：
 *   - 反馈生成（那是 FeedbackService 的事）
 *   - 与 LLM 通信
 *   - 与 WebSocket 通信
 *
 * 并发模型：
 *   - 顶层用 ConcurrentHashMap 隔离不同 session
 *   - 单 session 内用 CopyOnWriteArrayList，因为"写入只在 ASR endpoint /
 *     LLM 完成回调里发生（频率不高），读取则是结束时一次性整段读"，
 *     是典型的 CopyOnWrite 适用场景。
 */
@Service
public class TranscriptService {

    /** 候选人语速分类，便于 LLM 直接读取分类，不用自己去算阈值。 */
    public enum PaceLabel { TOO_FAST, NORMAL, TOO_SLOW, UNKNOWN }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Long> startTimeMap = new ConcurrentHashMap<>();
    private final Map<String, List<TranscriptEntry>> transcriptMap = new ConcurrentHashMap<>();

    /** 哪些 speaker 算"考官"。其它非 USER 的视作"AI 同辈考生"。 */
    private static final Set<String> INTERVIEWERS = Set.of("ProfA", "ProfB");

    // ====================================================================
    // 写入接口
    // ====================================================================

    /** 在 INIT 收到时调用，作为整场面试的 t=0 基准。 */
    public void start(String sessionId) {
        startTimeMap.put(sessionId, System.currentTimeMillis());
        transcriptMap.put(sessionId, new CopyOnWriteArrayList<>());
        recordSystem(sessionId, "INTERVIEW_STARTED");
    }

    /** 兼容旧调用方：默认 duringAiTurn=false（用户在 USER_TURN 中正常发言）。 */
    public void recordUser(String sessionId, String content) {
        recordUser(sessionId, content, false);
    }

    /** 带 duringAiTurn 标记的重载。true 表示 AI 正在说话时用户抢了话，供反馈引用。 */
    public void recordUser(String sessionId, String content, boolean duringAiTurn) {
        if (content == null || content.isBlank()) return;
        append(sessionId, "USER", SpeakerRole.USER, content, EntryType.SPEECH, duringAiTurn);
    }

    public void recordAi(String sessionId, String speaker, String content) {
        if (content == null || content.isBlank() || speaker == null) return;
        SpeakerRole role = INTERVIEWERS.contains(speaker)
                ? SpeakerRole.INTERVIEWER
                : SpeakerRole.PEER_CANDIDATE;
        append(sessionId, speaker, role, content, EntryType.SPEECH, false);
    }

    public void recordSystem(String sessionId, String eventTag) {
        append(sessionId, "SYSTEM", SpeakerRole.SYSTEM, eventTag, EntryType.SYSTEM_EVENT, false);
    }

    private void append(String sessionId, String speaker, SpeakerRole role,
                        String content, EntryType type, boolean duringAiTurn) {
        List<TranscriptEntry> list = transcriptMap.get(sessionId);
        if (list == null) {
            // 防御：如果 INIT 漏发，也不至于丢数据
            start(sessionId);
            list = transcriptMap.get(sessionId);
        }
        long t = System.currentTimeMillis() - startTimeMap.getOrDefault(sessionId, System.currentTimeMillis());
        list.add(new TranscriptEntry(t, speaker, role, content, type, duringAiTurn));
    }

    // ====================================================================
    // 读取接口
    // ====================================================================

    public List<TranscriptEntry> get(String sessionId) {
        return transcriptMap.getOrDefault(sessionId, List.of());
    }

    /**
     * 把整段 transcript + 简要统计打包成 JSON 字符串，
     * 给前端的 [TRANSCRIPT]: 帧使用，也可直接喂给 FeedbackService。
     */
    public String snapshotAsJson(String sessionId) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("sessionId", sessionId);
            root.put("totalDurationMs", durationMs(sessionId));

            ArrayNode arr = root.putArray("entries");
            for (TranscriptEntry e : get(sessionId)) {
                ObjectNode n = arr.addObject();
                n.put("relativeMs", e.relativeMs());
                n.put("speaker", e.speaker());
                n.put("role", e.role().name());
                n.put("content", e.content());
                n.put("type", e.type().name());
                if (e.duringAiTurn()) n.put("duringAiTurn", true);
            }

            // 附加一份基础节奏统计，FeedbackService 可以直接拿来用
            ObjectNode stats = root.putObject("userStats");
            UserPaceStats ps = computeUserPace(sessionId);
            stats.put("totalUtterances", ps.utterances);
            stats.put("totalWords", ps.totalWords);
            stats.put("speakingTimeMs", ps.speakingTimeMs);
            stats.put("wordsPerMinute", ps.wpm);
            stats.put("paceLabel", ps.label.name());
            stats.put("avgThinkingDelayMs", ps.avgThinkingDelayMs);

            return MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            ex.printStackTrace();
            return "{\"error\":\"snapshot failed\"}";
        }
    }

    public long durationMs(String sessionId) {
        Long start = startTimeMap.get(sessionId);
        if (start == null) return 0L;
        return System.currentTimeMillis() - start;
    }

    public void clear(String sessionId) {
        startTimeMap.remove(sessionId);
        transcriptMap.remove(sessionId);
    }

    // ====================================================================
    // 简单的语速 / 思考延迟统计
    // 故意保持简单：只算最直接的英文按"空白分词"近似词数。
    // 复杂分析（填词率、重复率、语义流畅度等）应由 FeedbackService 用 LLM 做。
    // ====================================================================

    private record UserPaceStats(
            int utterances,
            int totalWords,
            long speakingTimeMs,
            int wpm,
            PaceLabel label,
            long avgThinkingDelayMs
    ) {}

    private UserPaceStats computeUserPace(String sessionId) {
        List<TranscriptEntry> entries = get(sessionId);
        int utterances = 0;
        int totalWords = 0;
        long speakingTimeMs = 0;
        long thinkingDelaySum = 0;
        int thinkingDelaySamples = 0;

        for (int i = 0; i < entries.size(); i++) {
            TranscriptEntry e = entries.get(i);
            if (e.role() != SpeakerRole.USER) continue;

            utterances++;
            int words = countWords(e.content());
            totalWords += words;

            // 用户开始说话时刻 = 当前条；用户说完时刻 = 下一条的 relativeMs（任何说话人均可）
            long endMs = (i + 1 < entries.size()) ? entries.get(i + 1).relativeMs() : e.relativeMs();
            speakingTimeMs += Math.max(0, endMs - e.relativeMs());

            // 找前一条非用户发言作为"被问到"的时刻，差值即思考延迟
            for (int j = i - 1; j >= 0; j--) {
                TranscriptEntry prev = entries.get(j);
                if (prev.role() == SpeakerRole.USER) break;
                if (prev.type() == EntryType.SPEECH) {
                    thinkingDelaySum += Math.max(0, e.relativeMs() - prev.relativeMs());
                    thinkingDelaySamples++;
                    break;
                }
            }
        }

        int wpm = (speakingTimeMs > 0)
                ? (int) Math.round(totalWords * 60_000.0 / speakingTimeMs)
                : 0;

        PaceLabel label;
        if (wpm == 0) label = PaceLabel.UNKNOWN;
        else if (wpm < 110) label = PaceLabel.TOO_SLOW;     // 英文面试参考阈值
        else if (wpm > 180) label = PaceLabel.TOO_FAST;
        else label = PaceLabel.NORMAL;

        long avgDelay = thinkingDelaySamples > 0
                ? thinkingDelaySum / thinkingDelaySamples
                : 0;

        return new UserPaceStats(utterances, totalWords, speakingTimeMs, wpm, label, avgDelay);
    }

    private static int countWords(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }
}
