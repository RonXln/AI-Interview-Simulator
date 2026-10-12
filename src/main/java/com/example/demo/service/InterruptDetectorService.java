package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 打断检测 Gate：决定何时把 partial 交给 LLM 做二次判定。
 *
 * 单面 & 群面统一走这条路。真正"是否打断 + 说什么"由 LLMService.judgeInterrupt 拍板。
 *
 * Gate 通过条件（都必须满足）：
 *   1. enabled=true
 *   2. partial 词数 ≥ minWordsForCheck
 *   3. 本轮说话时长 ≥ turnMinMsForCheck
 *   4. 距上次 LLM 判定 ≥ checkCooldownMs（防每 partial 都 call）
 *   5. 本 session 打断次数 &lt; budget
 *   6. 距上次真打断 ≥ interruptCooldownMs
 *   7. 当前没有正在跑的 LLM 判定（互斥）
 */
@Service
public class InterruptDetectorService {

    @Value("${interrupt.enabled:true}")
    private boolean enabled;

    @Value("${interrupt.min-words-for-check:8}")
    private int minWordsForCheck;

    @Value("${interrupt.turn-min-ms-for-check:8000}")
    private long turnMinMsForCheck;

    @Value("${interrupt.check-cooldown-ms:6000}")
    private long checkCooldownMs;

    @Value("${interrupt.interrupt-cooldown-ms:15000}")
    private long interruptCooldownMs;

    @Value("${interrupt.budget-per-session:3}")
    private int budgetPerSession;

    private final Map<String, TurnState> perSession = new ConcurrentHashMap<>();

    private static final class TurnState {
        volatile long turnStartMs;
        volatile long lastCheckMs;
        volatile long lastInterruptMs;
        volatile int interruptCount;
        final AtomicBoolean judging = new AtomicBoolean(false);
    }

    /**
     * Gate 判定：true 表示应把当前 partial 交给 LLM。
     * 调用方随后必须 {@link #markJudgeStart} 抢占；LLM 返回后调 {@link #markJudgeEnd}。
     */
    public boolean shouldCallJudge(String sessionId, String partial) {
        if (!enabled) return false;
        if (partial == null || partial.isBlank()) return false;

        TurnState ts = perSession.computeIfAbsent(sessionId, k -> {
            TurnState s = new TurnState();
            s.turnStartMs = System.currentTimeMillis();
            return s;
        });

        long now = System.currentTimeMillis();
        int words = partial.trim().split("\\s+").length;
        long turnElapsed = now - ts.turnStartMs;

        if (words < minWordsForCheck) return false;
        if (turnElapsed < turnMinMsForCheck) return false;
        if (ts.lastCheckMs > 0 && now - ts.lastCheckMs < checkCooldownMs) return false;
        if (ts.interruptCount >= budgetPerSession) return false;
        if (ts.lastInterruptMs > 0 && now - ts.lastInterruptMs < interruptCooldownMs) return false;
        if (ts.judging.get()) return false;
        return true;
    }

    /** 抢占互斥锁，防止多个 partial 同时触发 LLM 判定。 */
    public boolean markJudgeStart(String sessionId) {
        TurnState ts = perSession.get(sessionId);
        if (ts == null) return false;
        if (!ts.judging.compareAndSet(false, true)) return false;
        ts.lastCheckMs = System.currentTimeMillis();
        return true;
    }

    /** LLM 判定完成后调用；decidedToInterrupt=true 时更新 budget 与冷却。 */
    public void markJudgeEnd(String sessionId, boolean decidedToInterrupt) {
        TurnState ts = perSession.get(sessionId);
        if (ts == null) return;
        ts.judging.set(false);
        if (decidedToInterrupt) {
            ts.lastInterruptMs = System.currentTimeMillis();
            ts.interruptCount++;
        }
    }

    public long turnElapsedMs(String sessionId) {
        TurnState ts = perSession.get(sessionId);
        if (ts == null) return 0;
        return System.currentTimeMillis() - ts.turnStartMs;
    }

    /** 用户 endpoint 触发时清 turn 状态。 */
    public void resetTurn(String sessionId) {
        TurnState ts = perSession.get(sessionId);
        if (ts == null) return;
        ts.turnStartMs = System.currentTimeMillis();
    }

    public void clear(String sessionId) {
        perSession.remove(sessionId);
    }
}
