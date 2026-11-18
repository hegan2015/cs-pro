package com.qoder.cs.core.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.chat.SseHub;
import com.qoder.cs.facade.dto.LlmCallDTO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.UUID;

/**
 * RocketMQ 事件总线（cs.mq.enabled=true 生效；关闭或 MQ 不可用时行为与无 MQ 完全一致）。
 * 1) SSE 跨实例广播：事件带 origin，本实例即时推送，其他实例消费后补投挂在自己这里的连接；
 * 2) cs_llm_call 审计异步落库：集群消费由任一实例写库，窗口内 token 计入本地 pending 供预算护栏补算；
 * 3) 发送失败审计当场直写兜底，业务不中断。
 */
@Slf4j
@Component
public class MqBridge {

    private static final long PENDING_WINDOW_MS = 5_000;

    private final SseHub sse;
    private final LlmCallMapper llmCalls;
    private final ObjectMapper json = new ObjectMapper();
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    private final boolean enabled;
    private final String nameServer;
    private final String sseTopic;
    private final String auditTopic;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer sseConsumer;
    private DefaultMQPushConsumer auditConsumer;
    private final Map<String, ConcurrentLinkedDeque<long[]>> pendingAudit = new ConcurrentHashMap<>();

    public MqBridge(SseHub sse, LlmCallMapper llmCalls,
                    @Value("${cs.mq.enabled:false}") boolean enabled,
                    @Value("${cs.mq.name-server:127.0.0.1:9876}") String nameServer,
                    @Value("${cs.mq.sse-topic:cs_sse_event}") String sseTopic,
                    @Value("${cs.mq.audit-topic:cs_llm_audit}") String auditTopic) {
        this.sse = sse;
        this.llmCalls = llmCalls;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.sseTopic = sseTopic;
        this.auditTopic = auditTopic;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            return;
        }
        try {
            producer = new DefaultMQProducer("cs-producer-" + instanceId);
            producer.setNamesrvAddr(nameServer);
            producer.setSendMsgTimeout(2000);
            producer.start();

            DefaultMQPushConsumer sseC = new DefaultMQPushConsumer("cs-sse-broadcast");
            this.sseConsumer = sseC;
            sseC.setNamesrvAddr(nameServer);
            sseC.setMessageModel(MessageModel.BROADCASTING);
            sseC.subscribe(sseTopic, "*");
            sseC.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
                for (MessageExt m : msgs) {
                    onSseMessage(new String(m.getBody(), StandardCharsets.UTF_8));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            sseC.start();

            DefaultMQPushConsumer auditC = new DefaultMQPushConsumer("cs-llm-audit-persist");
            this.auditConsumer = auditC;
            auditC.setNamesrvAddr(nameServer);
            auditC.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            auditC.subscribe(auditTopic, "*");
            auditC.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
                for (MessageExt m : msgs) {
                    try {
                        llmCalls.insert(json.readValue(m.getBody(), LlmCallDTO.class));
                    } catch (Exception e) {
                        log.warn("审计消息落库失败，稍后重投: {}", e.getMessage());
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            auditC.start();
            log.info("MqBridge 就绪 instance={} namesrv={} topics={}/{}",
                    instanceId, nameServer, sseTopic, auditTopic);
        } catch (Exception e) {
            if (producer != null) {
                producer.shutdown();
                producer = null;
            }
            log.error("RocketMQ 不可用，自动降级为本地直写/本地推送: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        // 消费者线程非守护：不显式关闭会让启动失败的 JVM 僵而不退
        if (sseConsumer != null) {
            sseConsumer.shutdown();
        }
        if (auditConsumer != null) {
            auditConsumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }

    public boolean active() {
        return enabled && producer != null;
    }

    /** 结果类事件（message/done）：本实例即时推送 + 跨实例广播。token/tool 流不广播。 */
    public void broadcast(String sessionId, String event, Object data) {
        sse.send(sessionId, event, data);
        if (producer == null) {
            return;
        }
        try {
            String body = json.writeValueAsString(Map.of(
                    "origin", instanceId, "sessionId", sessionId, "event", event,
                    "data", json.writeValueAsString(data)));
            producer.send(new Message(sseTopic, event, sessionId + "#" + event,
                    body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("SSE 广播失败（本实例已即时推送，忽略）: {}", e.getMessage());
        }
    }

    private void onSseMessage(String body) {
        try {
            Map<?, ?> env = json.readValue(body, Map.class);
            if (instanceId.equals(env.get("origin"))) {
                return; // 本实例已即时推送，去重
            }
            Object data = json.readValue(String.valueOf(env.get("data")), Object.class);
            sse.send(String.valueOf(env.get("sessionId")), String.valueOf(env.get("event")), data);
        } catch (Exception e) {
            log.debug("SSE 广播消费异常: {}", e.getMessage());
        }
    }

    /** 审计落库：MQ 就绪走异步集群消费（token 进 pending 窗口），否则/失败直写。 */
    public void writeAudit(LlmCallDTO call, String tenantCode) {
        if (producer == null) {
            llmCalls.insert(call);
            return;
        }
        try {
            producer.send(new Message(auditTopic, String.valueOf(call.getStatus()), call.getId(),
                    json.writeValueAsBytes(call)));
            if ("OK".equals(call.getStatus()) && tenantCode != null) {
                long tokens = nz(call.getPromptTokens()) + nz(call.getCompletionTokens());
                if (tokens > 0) {
                    pendingAudit.computeIfAbsent(tenantCode, k -> new ConcurrentLinkedDeque<>())
                            .addLast(new long[]{tokens, System.currentTimeMillis()});
                }
            }
        } catch (Exception e) {
            log.warn("审计消息发送失败，直写兜底: {}", e.getMessage());
            llmCalls.insert(call);
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    /** 本租户 5s 异步窗口内已发送未确认落库的 token 量（预算护栏补算，宁可多算不少算）。 */
    public long pendingTokens(String tenantCode) {
        ConcurrentLinkedDeque<long[]> q = pendingAudit.get(tenantCode);
        if (q == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - PENDING_WINDOW_MS;
        q.removeIf(e -> e[1] < cutoff);
        long sum = 0;
        for (long[] e : q) {
            sum += e[0];
        }
        return sum;
    }
}
