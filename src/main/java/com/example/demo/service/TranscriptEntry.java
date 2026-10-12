package com.example.demo.service;

/**
 * 一条对话记录。整场面试由一个有序 List<TranscriptEntry> 组成。
 *
 * relativeMs：相对会议开始时刻的毫秒数。用于：
 *   - 评估候选人的语速、节奏（与相邻条目时差）
 *   - EndPage 上做时间线回放
 * 不存绝对时间戳是因为：相对值天然便于"会议时长"等推算，
 * 也避免不同时区/服务器时间漂移带来的二次换算。
 *
 * type：SPEECH 表示真实"开口说话"；SYSTEM_EVENT 表示导播层事件
 *      （如 INTERVIEW_STARTED / INTERVIEW_ENDED / USER_LEFT_EARLY），
 *      它们没有"说话"语义，但能为反馈生成提供上下文。
 */
public record TranscriptEntry(
        long relativeMs,
        String speaker,        // "USER" / "ProfA" / "Alice" ... / "SYSTEM"
        SpeakerRole role,      // 粗分类，方便统计
        String content,
        EntryType type,
        boolean duringAiTurn   // 用户发言时 AI 是否正在说话（AI 发言 / 系统事件固定为 false）
) {

    public enum SpeakerRole {
        USER,             // 候选人本人
        INTERVIEWER,      // ProfA / ProfB
        PEER_CANDIDATE,   // 群面里的 AI 考生 Alice/Bob/Charlie/David/Emma
        SYSTEM            // 系统事件
    }

    public enum EntryType {
        SPEECH,
        SYSTEM_EVENT
    }
}
