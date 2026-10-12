package com.example.demo.config;

import com.example.demo.handler.AsrTestHandler;
import com.example.demo.handler.AudioHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioHandler audioHandler;
    private final AsrTestHandler asrTestHandler;

    public WebSocketConfig(AudioHandler audioHandler, AsrTestHandler asrTestHandler) {
        this.audioHandler = audioHandler;
        this.asrTestHandler = asrTestHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 主面试通道
        registry.addHandler(audioHandler, "/ws/audio")
                .setAllowedOrigins("*");
        // 独立 ASR 测试通道（不进 LLM/TTS，纯识别）
        registry.addHandler(asrTestHandler, "/ws/asr-test")
                .setAllowedOrigins("*");
    }
}