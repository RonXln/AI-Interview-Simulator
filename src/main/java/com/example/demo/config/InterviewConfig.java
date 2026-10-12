package com.example.demo.config;

import org.springframework.stereotype.Component;

/**
 * 面试配置类 - 存储面试相关的配置变量
 * 后期可以扩展支持多场景的开场白
 */
@Component
public class InterviewConfig {
    
    /**
     * 默认的开场白（英文）
     * 后期可以改为从数据库或配置文件读取，支持不同场景
     */
    public static final String DEFAULT_GREETING = 
        "Hello, welcome to our AI interview service. If you hear this voice message, " +
        "it means we are currently in the testing phase. Please start speaking when you're ready.";
    
    /**
     * 获取开场白
     * @return 开场白文本
     */
    public String getGreeting() {
        // MVP 阶段返回默认开场白
        return DEFAULT_GREETING;
    }
    
    /**
     * 获取开场白（支持场景参数，后期使用）
     * @param scenario 场景名称
     * @return 对应场景的开场白
     */
    public String getGreeting(String scenario) {
        // TODO: 后期实现支持不同场景的开场白
        // 目前只返回默认开场白
        return DEFAULT_GREETING;
    }
}
