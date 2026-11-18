package com.qoder.cs.core.proactive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.core.biz.RideClient;
import com.qoder.cs.core.chat.ChatService;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.facade.dto.ProactiveEventDTO;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.facade.request.SessionCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 主动服务：轮询上游异常事件（ride 超时关闭订单 / payment-pro 失败支付单），
 * 对受影响用户自动开/续会话推送 BOT 提醒。上游未启动时静默跳过。
 * 幂等：event_key 主键先插入后推送，多实例并发轮询仅一方成功；只关注 lookback 窗口内事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveService {

    private final RideClient ride;
    private final ProactiveEventMapper events;
    private final SessionMapper sessions;
    private final ChatService chat;
    private final CsProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    /** payment-pro 内部只读 API（区别于 HMAC OpenAPI）；留空=禁用该事件源 */
    @Value("${cs.pp.poll-base-url:}")
    private String ppPollBaseUrl;

    @Scheduled(fixedDelayString = "${cs.proactive.interval-ms:60000}", initialDelay = 20_000)
    public void poll() {
        if (!props.getProactive().isEnabled()) {
            return;
        }
        try {
            pollRideExpired();
        } catch (Exception e) {
            log.debug("主动服务 ride 事件源跳过: {}", e.getMessage());
        }
        try {
            pollPaymentFailed();
        } catch (Exception e) {
            log.debug("主动服务 payment 事件源跳过: {}", e.getMessage());
        }
    }

    /** ride-pro 无人接单超时关闭（EXPIRED）的订单 → 提醒乘客 */
    private void pollRideExpired() {
        JsonNode page = ride.get("/api/orders/query?status=EXPIRED&page=1&size=20");
        long cutoff = cutoff();
        int sent = 0;
        for (JsonNode o : page.path("items")) {
            if (sent >= props.getProactive().getMaxPerPoll()) {
                break;
            }
            if (o.path("createdAt").asLong(0) < cutoff) {
                continue;
            }
            String orderId = o.path("id").asText();
            String text = "【主动服务】检测到您的打车订单 " + orderId + " 因超时无人接单已关闭。"
                    + "如仍需用车请重新下单；有疑问可直接回复本会话，或点击「转人工」。";
            if (notify("ride-expired:" + orderId, "ride-pro",
                    o.path("passengerId").asText(), "PASSENGER", text, orderId)) {
                sent++;
            }
        }
    }

    /** payment-pro 失败支付单 → 提醒付款方（payerAccountId 即商户用户） */
    private void pollPaymentFailed() throws Exception {
        if (ppPollBaseUrl == null || ppPollBaseUrl.isBlank()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ppPollBaseUrl.replaceAll("/+$", "") + "/api/payments"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return;
        }
        JsonNode list = json.readTree(resp.body());
        long cutoff = cutoff();
        int sent = 0;
        for (JsonNode p : list) {
            if (sent >= props.getProactive().getMaxPerPoll()) {
                break;
            }
            if (!"FAILED".equals(p.path("status").asText())
                    || p.path("createdAt").asLong(0) < cutoff) {
                continue;
            }
            String payId = p.path("id").asText();
            String reason = p.path("failReason").asText("未知原因");
            String text = "【主动服务】检测到您的支付单 " + payId + "（订单 "
                    + p.path("orderId").asText("-") + "）支付失败：" + reason
                    + "。可更换支付方式重试；有疑问可直接回复本会话，或点击「转人工」。";
            if (notify("pp-failed:" + payId, "payment-pro",
                    p.path("payerAccountId").asText(), "MERCHANT", text, payId)) {
                sent++;
            }
        }
    }

    /** 先占幂等键再推送：重复事件/多实例竞争返回 false；会话复用用户最新 ACTIVE，无则新建。 */
    public boolean notify(String eventKey, String tenantCode, String userId,
                          String userRole, String text, String payload) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        ProactiveEventDTO ev = new ProactiveEventDTO();
        ev.setEventKey(eventKey);
        ev.setTenantId(tenantCode);
        ev.setUserId(userId);
        ev.setPayload(payload);
        ev.setStatus("SENT");
        ev.setCreatedAt(System.currentTimeMillis());
        try {
            events.insert(ev);
        } catch (DuplicateKeyException e) {
            return false;
        }
        try {
            SessionDTO s = sessions.latestActiveByUser(userId, tenantCode);
            if (s == null) {
                SessionCreateRequest req = new SessionCreateRequest();
                req.setUserRole(userRole);
                req.setUserId(userId);
                req.setTenantCode(tenantCode);
                s = chat.createSession(req);
            }
            events.updateSession(eventKey, s.getId());
            chat.pushProactiveMessage(s.getId(), text);
            log.info("主动服务推送 event={} session={} user={}", eventKey, s.getId(), userId);
            return true;
        } catch (Exception e) {
            // 推送失败不回收幂等键：避免上游持续异常时每轮重试刷屏，留痕人工排查
            log.warn("主动服务推送失败 event={} err={}", eventKey, e.getMessage());
            return false;
        }
    }

    private long cutoff() {
        return System.currentTimeMillis()
                - props.getProactive().getLookbackHours() * 3_600_000L;
    }
}