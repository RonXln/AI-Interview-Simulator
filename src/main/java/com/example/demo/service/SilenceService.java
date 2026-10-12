package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 沉默检测：进入 USER_TURN 后按阈值排一个/多个回调；ASR 一有 partial 或切回 AI_SPEAKING 就取消。
 *
 * 单面（1v1 / 2v1）：连续沉默递进
 *   1 次 → "你还在吗 / 听不见你"
 *   2 次 → "这个问题有点难吗？我们换一个"
 *   3 次 → 允许 LLM 判定直接结束面试
 *
 * 群面：只排一次短沉默；到时让 bundle 走"AI 候选人自我推进"路线，不做单独 pool。
 *
 * 与 turn 状态的耦合点全部由 AudioHandler 负责：
 *   - enterUserTurn 后 armAfterUserTurn(...)
 *   - onAsrPartial / triggerXxxReply 起头 → cancel(...)
 *   - USER_END / afterConnectionClosed → clear(...)
 */
@Service
public class SilenceService {

    @Value("${silence.enabled:true}")
    private boolean enabled;

    // 单面三档阈值（毫秒）
    @Value("${silence.single.level1-ms:12000}")
    private long singleLevel1Ms;
    @Value("${silence.single.level2-ms:12000}")
    private long singleLevel2Ms;
    @Value("${silence.single.level3-ms:15000}")
    private long singleLevel3Ms;

    // 群面单次阈值
    @Value("${silence.group.ms:10000}")
    private long groupMs;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "silence-scheduler");
        t.setDaemon(true);
        return t;
    });

    private static final class SessionState {
        int silenceCount;          // 已连续沉默次数（用户一说话就清零）
        ScheduledFuture<?> future; // 当前挂着的定时任务
    }

    private final Map<String, SessionState> stateMap = new ConcurrentHashMap<>();

    public interface SilenceCallback {
        /**
         * @param silenceCount 从 1 开始的沉默次数
         * @param mayEnd       是否允许 LLM 主动结束面试（仅单面第 3 次）
         */
        void onSilence(int silenceCount, boolean mayEnd);
    }

    /** 进入 USER_TURN 时调用；根据当前 silenceCount 排下一档定时。 */
    public void armAfterUserTurn(String sid, boolean isGroup, SilenceCallback cb) {
        if (!enabled) return;
        SessionState s = stateMap.computeIfAbsent(sid, k -> new SessionState());
        cancelFuture(s);

        long delay;
        boolean mayEnd = false;
        int nextCount = s.silenceCount + 1;
        if (isGroup) {
            // 群面：只排一次，不看轮次，交由回调走"AI 自我推进"
            delay = groupMs;
        } else {
            switch (nextCount) {
                case 1:  delay = singleLevel1Ms; break;
                case 2:  delay = singleLevel2Ms; break;
                default: delay = singleLevel3Ms; mayEnd = true; break;
            }
        }

        final boolean mayEndFinal = mayEnd;
        s.future = scheduler.schedule(() -> {
            // 双检：也许在 delay 期间已经被 cancel 掉了但任务已排队
            SessionState cur = stateMap.get(sid);
            if (cur == null || cur.future == null) return;
            cur.silenceCount = nextCount;
            cur.future = null;
            try {
                cb.onSilence(nextCount, mayEndFinal);
            } catch (Exception e) {
                System.err.println("[Silence] 回调异常 sid=" + sid + " : " + e.getMessage());
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** 用户说话（ASR partial 或 endpoint）→ 清计数 + 取消挂起任务。 */
    public void onUserSpoke(String sid) {
        SessionState s = stateMap.get(sid);
        if (s == null) return;
        s.silenceCount = 0;
        cancelFuture(s);
    }

    /** 只取消挂起任务，不清计数（比如 AI 主动发起沉默应对时）。 */
    public void cancel(String sid) {
        SessionState s = stateMap.get(sid);
        if (s == null) return;
        cancelFuture(s);
    }

    /** 会话彻底结束时调用。 */
    public void clear(String sid) {
        SessionState s = stateMap.remove(sid);
        if (s != null) cancelFuture(s);
    }

    public int currentSilenceCount(String sid) {
        SessionState s = stateMap.get(sid);
        return s == null ? 0 : s.silenceCount;
    }

    private void cancelFuture(SessionState s) {
        ScheduledFuture<?> f = s.future;
        if (f != null) {
            f.cancel(false);
            s.future = null;
        }
    }
}
