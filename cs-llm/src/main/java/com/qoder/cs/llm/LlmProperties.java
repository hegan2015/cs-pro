package com.qoder.cs.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** LLM 配置：config/llm.properties（gitignore）。未配置 apiKey 时系统走 FAQ 降级模式。 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.llm")
public class LlmProperties {
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private double temperature = 0.3;
    private int maxTokens = 1024;
    private int timeoutMs = 60000;
    /** 连续失败熔断阈值与熔断时长 */
    private int circuitFailureThreshold = 5;
    private long circuitOpenMs = 60000;

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}