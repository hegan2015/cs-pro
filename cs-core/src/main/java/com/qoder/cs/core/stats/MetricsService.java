package com.qoder.cs.core.stats;

import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.chat.MessageMapper;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.core.chat.SseHub;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.core.mq.MqBridge;
import com.qoder.cs.core.ticket.TicketMapper;
import com.qoder.cs.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Prometheus 文本指标（零依赖手写，exposition format 0.0.4）。
 * 会话/消息/工单/LLM 调用与 token/熔断状态/活跃 SSE/MQ/主动服务开关。
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final SessionMapper sessions;
    private final MessageMapper messages;
    private final TicketMapper tickets;
    private final LlmCallMapper llmCalls;
    private final SseHub sse;
    private final LlmClient llm;
    private final MqBridge mq;
    private final CsProperties props;

    public String render() {
        StringBuilder sb = new StringBuilder();
        gauge(sb, "cs_sessions_total", "会话总数", sessions.count(null, null, null));
        gauge(sb, "cs_sessions_active", "进行中会话数", sessions.count("ACTIVE", null, null));
        gauge(sb, "cs_messages_total", "消息总数", messages.countAll());
        gauge(sb, "cs_tickets_total", "工单总数", tickets.count(null, null, null));
        gauge(sb, "cs_llm_calls_total", "LLM 调用总数", llmCalls.countAll());
        for (String status : new String[]{"OK", "FALLBACK", "BUDGET", "ABORTED"}) {
            labeled(sb, "cs_llm_calls", "按状态计 LLM 调用", "status", status, llmCalls.countByStatus(status));
        }
        gauge(sb, "cs_llm_tokens_total", "LLM 累计 token（prompt+completion）", llmCalls.sumTokens());
        gauge(sb, "cs_sse_connections_active", "活跃 SSE 连接数", sse.activeCount());
        gauge(sb, "cs_llm_configured", "LLM 是否已配置 Key（1/0）", llm.configured() ? 1 : 0);
        gauge(sb, "cs_llm_available", "LLM 是否可用（含熔断判定，1/0）", llm.available() ? 1 : 0);
        for (Map.Entry<String, Map<String, Object>> e : llm.breakerStates().entrySet()) {
            labeled(sb, "cs_llm_breaker_open", "端点熔断中（1/0）", "endpoint", e.getKey(),
                    Boolean.TRUE.equals(e.getValue().get("open")) ? 1 : 0);
            labeled(sb, "cs_llm_breaker_fails", "端点连续失败计数", "endpoint", e.getKey(),
                    ((Number) e.getValue().get("fails")).longValue());
        }
        gauge(sb, "cs_mq_active", "RocketMQ 事件总线是否激活（1/0）", mq.active() ? 1 : 0);
        gauge(sb, "cs_proactive_enabled", "主动服务轮询开关（1/0）", props.getProactive().isEnabled() ? 1 : 0);
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void labeled(StringBuilder sb, String name, String help, String label, String value, long v) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append('{').append(label).append("=\"").append(esc(value)).append("\"} ").append(v).append('\n');
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}