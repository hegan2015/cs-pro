package com.qoder.cs.core.stats;

import com.qoder.cs.core.biz.PaymentProClient;
import com.qoder.cs.core.biz.RideClient;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.core.mq.MqBridge;
import com.qoder.cs.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 组件健康详情：DB 模式/MQ/LLM/上游 ride·payment/主动服务/运行时长，供健康页与运维巡检。 */
@Service
@RequiredArgsConstructor
public class HealthService {

    private final RideClient ride;
    private final PaymentProClient pp;
    private final LlmClient llm;
    private final MqBridge mq;
    private final CsProperties props;
    private final Environment env;
    private final com.qoder.cs.kb.vector.VectorIndex vector;

    public Map<String, Object> detail() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("service", "cs-pro");
        out.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        boolean mysql = Arrays.asList(env.getActiveProfiles()).contains("mysql");
        out.put("db", Map.of("mode", mysql ? "mysql" : "h2"));
        out.put("mq", Map.of("active", mq.active()));
        out.put("llm", Map.of("configured", llm.configured(), "available", llm.available(), "model", llm.model()));
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("ridePro", ride.healthy());
        upstream.put("paymentPro", pp.enabled());
        out.put("upstream", upstream);
        out.put("proactive", Map.of("enabled", props.getProactive().isEnabled()));
        var vs = vector.status();
        out.put("vector", Map.of("enabled", vs.enabled(), "embeddingUp", vs.embeddingUp(),
                "qdrantUp", vs.qdrantUp(), "indexed", vs.indexed()));
        return out;
    }
}