package com.example.demo.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turn State Machine 底座。
 *
 * 三态：USER_TURN / AI_PLANNING / AI_SPEAKING。
 *   - USER_TURN：等用户说话，正常走 speculative & endpoint。
 *   - AI_PLANNING：LLM 生成 + 首个 TTS 尚未开播，用户此时开口 = 抢占（cancel 掉整个 AI 任务）。
 *   - AI_SPEAKING：至少一条 utterance 已开播，用户开口 = 走 interrupt judge。
 *
 * 兼容旧代码：{@link #isAiSpeaking} 沿用旧语义（PLANNING/SPEAKING 都算 AI 侧），
 * 需要精确判断时改用 {@link #getPhase}。
 */
@Service
public class TurnStateService {

    public enum Phase {
        USER_TURN,
        AI_PLANNING,
        AI_SPEAKING
    }

    /** 兼容旧调用点，等价于 Phase 的粗粒度视图。 */
    public enum State {
        USER_TURN,
        AI_SPEAKING
    }

    private final Map<String, Phase> phaseMap = new ConcurrentHashMap<>();

    public Phase getPhase(String sessionId) {
        return phaseMap.getOrDefault(sessionId, Phase.USER_TURN);
    }

    public State get(String sessionId) {
        return getPhase(sessionId) == Phase.USER_TURN ? State.USER_TURN : State.AI_SPEAKING;
    }

    public boolean isAiSpeaking(String sessionId) {
        Phase p = getPhase(sessionId);
        return p == Phase.AI_PLANNING || p == Phase.AI_SPEAKING;
    }

    public boolean isAiPlanning(String sessionId) {
        return getPhase(sessionId) == Phase.AI_PLANNING;
    }

    public void enterAiPlanning(String sessionId) {
        phaseMap.put(sessionId, Phase.AI_PLANNING);
        System.out.println("🧠 [Turn] " + sessionId + " → AI_PLANNING");
    }

    public void enterAiSpeaking(String sessionId) {
        phaseMap.put(sessionId, Phase.AI_SPEAKING);
        System.out.println("🎬 [Turn] " + sessionId + " → AI_SPEAKING");
    }

    public void enterUserTurn(String sessionId) {
        phaseMap.put(sessionId, Phase.USER_TURN);
        System.out.println("🎤 [Turn] " + sessionId + " → USER_TURN");
    }

    public void clear(String sessionId) {
        phaseMap.remove(sessionId);
    }
}
