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
    /** 分级 max-tokens：查询类意图/工具轮用小额度，投诉/开放类最终回答用大额度 */
    private int maxTokensQuery = 512;
    private int maxTokensReport = 2048;
    private int timeoutMs = 60000;
    /** 备用供应商端点（主端点重试耗尽后路由；留空=不启用）。model/api-key 留空沿用主端点 */
    private String baseUrlBackup = "";
    private String modelBackup = "";
    private String apiKeyBackup = "";
    /** 视觉模型（含图片的消息改用此模型，同端点；留空=沿用主模型，需主模型本身支持视觉） */
    private String visionModel = "";
    /** 系统提示版本号，随审计落库，用于对比不同提示词的效果 */
    private String promptVersion = "v1.2.0";
    /** 输出侧 PII 打码（手机号/身份证/银行卡号），仅作用于 LLM 生成内容 */
    private boolean maskPii = true;
    /** 连续失败熔断阈值与熔断时长 */
    private int circuitFailureThreshold = 5;
    private long circuitOpenMs = 60000;
    /** 可重试错误的额外重试次数与退避基数（429/5xx/网络超时；4xx 与已流式输出的不重试） */
    private int retryMax = 2;
    private long retryBackoffMs = 1000;
    /** 租户日 token 预算（prompt+completion，0=不限）；超限当日该租户降级 FAQ */
    private long dailyTokenBudgetPerTenant = 0;

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}