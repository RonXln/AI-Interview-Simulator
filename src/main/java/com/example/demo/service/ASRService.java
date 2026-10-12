package com.example.demo.service;

import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * 阿里云百炼 Paraformer-Realtime-v2 ASR 的参数工厂。
 * 每个 WebSocket 会话在 AudioHandler 里独立 new Recognition()，本类只负责统一注入 apiKey/模型/断句阈值。
 */
@Service
public class ASRService {

    static {
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    @Value("${dashscope.api.key:}")
    private String apiKey;

    @Value("${dashscope.ws.url:wss://dashscope.aliyuncs.com/api-ws/v1/inference}")
    private String wsUrl;

    @Value("${asr.model:paraformer-realtime-v2}")
    private String model;

    @Value("${asr.max-sentence-silence:600}")
    private int maxSentenceSilence;

    // 单语默认 en；改成 zh、en,zh 等均可
    @Value("${asr.language:en}")
    private String language;

    private boolean ready = false;

    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }

    @PostConstruct
    public void init() {
        System.out.println("========== 正在初始化 DashScope ASR ==========");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ [ASR] DASHSCOPE_API_KEY 未配置，ASR 将无法工作。请在 .env 中填入。");
            return;
        }
        Constants.baseWebsocketApiUrl = wsUrl;
        this.ready = true;
        System.out.println("========== ✅ DashScope ASR 就绪：" + model
                + "  silence=" + maxSentenceSilence + "ms ==========");
        System.out.println("           WS: " + wsUrl);
    }

    public boolean isReady() {
        return ready;
    }

    /** 每次为新会话构建一份识别参数（apiKey 已注入，无需外部传）。 */
    public RecognitionParam buildParam() {
        String[] hints = language == null || language.isBlank()
                ? new String[]{"en"}
                : java.util.Arrays.stream(language.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return RecognitionParam.builder()
                .apiKey(apiKey)
                .model(model)
                .format("pcm")
                .sampleRate(16000)
                .parameter("language_hints", hints)
                .parameter("max_sentence_silence", maxSentenceSilence)
                .build();
    }
}
