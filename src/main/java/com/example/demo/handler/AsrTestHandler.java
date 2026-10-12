package com.example.demo.handler;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.example.demo.service.ASRService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 纯 ASR 测试端点：只跑 DashScope 识别并广播 [ASR-PARTIAL]/[ASR-FINAL]，不进 LLM/TTS
@Component
public class AsrTestHandler extends BinaryWebSocketHandler {

    private final ASRService asrService;
    private final Map<String, Recognition> recognitionMap = new ConcurrentHashMap<>();
    private final Map<String, Long> connectedAtMap = new ConcurrentHashMap<>();

    public AsrTestHandler(ASRService asrService) {
        this.asrService = asrService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        final String sid = session.getId();
        System.out.println("🎤 [AsrTest] 新连接 sid=" + sid);

        if (!asrService.isReady()) {
            sendText(session, "[ERROR]:ASR service not ready (missing DASHSCOPE_API_KEY?)");
            return;
        }

        connectedAtMap.put(sid, System.currentTimeMillis());
        Recognition recognition = new Recognition();

        ResultCallback<RecognitionResult> cb = new ResultCallback<RecognitionResult>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result == null || result.getSentence() == null) return;
                String text = result.getSentence().getText();
                long start = connectedAtMap.getOrDefault(sid, System.currentTimeMillis());
                long elapsed = System.currentTimeMillis() - start;
                String tag = result.isSentenceEnd() ? "[ASR-FINAL]" : "[ASR-PARTIAL]";
                sendText(session, tag + ":" + elapsed + ":" + (text == null ? "" : text));
            }

            @Override
            public void onComplete() {
                sendText(session, "[ASR-STREAM-END]");
            }

            @Override
            public void onError(Exception e) {
                sendText(session, "[ERROR]:" + e.getMessage());
                System.err.println("❌ [AsrTest] onError sid=" + sid + " : " + e.getMessage());
            }
        };

        try {
            recognition.call(asrService.buildParam(), cb);
            recognitionMap.put(sid, recognition);
            sendText(session, "[READY]:" + asrService.getModel() + ":16000:pcm");
        } catch (Exception e) {
            sendText(session, "[ERROR]:" + e.getMessage());
            System.err.println("❌ [AsrTest] 启动失败: " + e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Recognition rec = recognitionMap.get(session.getId());
        if (rec == null) return;
        ByteBuffer payload = message.getPayload();
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload);
        copy.flip();
        try {
            rec.sendAudioFrame(copy);
        } catch (Exception e) {
            sendText(session, "[ERROR]:sendAudioFrame failed: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sid = session.getId();
        System.out.println("👋 [AsrTest] 断开 sid=" + sid);
        Recognition rec = recognitionMap.remove(sid);
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignore) {}
            try { rec.getDuplexApi().close(1000, "bye"); } catch (Exception ignore) {}
        }
        connectedAtMap.remove(sid);
    }

    private void sendText(WebSocketSession s, String msg) {
        try {
            synchronized (s) {
                if (s.isOpen()) s.sendMessage(new TextMessage(msg));
            }
        } catch (Exception ignore) {}
    }
}
