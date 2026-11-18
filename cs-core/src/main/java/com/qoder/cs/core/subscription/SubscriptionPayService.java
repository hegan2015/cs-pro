package com.qoder.cs.core.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.biz.PaymentProClient;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.facade.dto.SubOrderDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅支付开通（可选通道）：pp trade.create/trade.pay 下单，
 * 服务端 5s 调度回查终态并同事务开通订阅——不依赖前端轮询（§3A.5）。
 * 金额/商户/商品全部服务端常量；开通 CAS 幂等，重放不重复加天数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionPayService {

    private static final long ORDER_TTL_MS = 30 * 60_000L;

    private final SubOrderMapper orderMapper;
    private final com.qoder.cs.common.id.IdGenerator ids;
    private final TenantMapper tenantMapper;
    private final SubscriptionService subscriptionService;
    private final PaymentProClient pp;
    private final CsProperties props;
    private final TransactionTemplate tx;

    public boolean payEnabled() {
        return pp.enabled();
    }

    public SubOrderDTO createOrder(String tenantCode, String payerAccountId) {
        if (!pp.enabled()) {
            throw BizException.biz("支付通道未配置，请使用激活码开通");
        }
        TenantDTO tenant = subscriptionService.requireTenant(tenantCode);
        if (payerAccountId == null || payerAccountId.isBlank()) {
            throw BizException.param("payerAccountId 必填（payment-pro 付款账户）");
        }
        SubOrderDTO order = new SubOrderDTO();
        order.setId("SO" + ids.next("seq_sub_order"));
        order.setTenantId(tenant.getId());
        order.setAmount(props.getPlan().getPrice());
        order.setDays(props.getPlan().getDays());
        order.setRequestId("SUB-" + order.getId());
        order.setStatus("PENDING");
        order.setCreatedAt(System.currentTimeMillis());
        orderMapper.insert(order);
        try {
            JsonNode ppOrder = pp.callSubscriptionPay("trade.create", Map.of(
                    "subject", "智能客服订阅-" + tenant.getCode() + "-" + props.getPlan().getName(),
                    "amount", order.getAmount(),
                    "outTradeNo", order.getRequestId()));
            String ppOrderId = ppOrder.path("id").asText();
            JsonNode payResult = pp.callSubscriptionPay("trade.pay", Map.of(
                    "orderId", ppOrderId,
                    "method", "BALANCE",
                    "accountId", payerAccountId,
                    "outTradeNo", order.getRequestId()));
            String ppPaymentId = payResult.path("payment").path("id").asText(null);
            orderMapper.updatePpIds(order.getId(), ppOrderId, ppPaymentId);
            order.setPpOrderId(ppOrderId);
            order.setPpPaymentId(ppPaymentId);
            log.info("订阅单创建 subOrder={} tenant={} ppOrder={}", order.getId(), tenant.getCode(), ppOrderId);
            return order;
        } catch (Exception e) {
            orderMapper.casStatus(order.getId(), "PENDING", "FAILED", null);
            throw e instanceof BizException be ? be : BizException.downstream("订阅下单失败: " + e.getMessage());
        }
    }

    public SubOrderDTO get(String id) {
        SubOrderDTO order = orderMapper.selectById(id);
        if (order == null) {
            throw BizException.notFound("订阅单不存在: " + id);
        }
        if ("PENDING".equals(order.getStatus()) && order.getPpOrderId() != null) {
            settle(order);
            order = orderMapper.selectById(id);
        }
        return order;
    }

    public List<SubOrderDTO> list() {
        return orderMapper.selectAll();
    }

    @Scheduled(fixedDelay = 5000)
    public void drivePending() {
        if (!pp.enabled()) {
            return;
        }
        for (SubOrderDTO order : orderMapper.selectPending()) {
            try {
                settle(order);
            } catch (Exception e) {
                log.warn("订阅单回查失败 subOrder={} err={}", order.getId(), e.getMessage());
            }
        }
    }

    /** 回查 pp 终态 → CAS 推进 → 同事务开通订阅。幂等：仅 PENDING→PAID 胜者开通。 */
    private void settle(SubOrderDTO order) {
        long now = System.currentTimeMillis();
        if (now - order.getCreatedAt() > ORDER_TTL_MS) {
            orderMapper.casStatus(order.getId(), "PENDING", "CLOSED", null);
            return;
        }
        JsonNode query;
        try {
            query = pp.callRead("trade.query", Map.of("orderId", order.getPpOrderId()));
        } catch (Exception e) {
            return; // pp 暂不可达，下轮再试
        }
        String payStatus = query.path("payment").path("status").asText("");
        if ("SUCCESS".equals(payStatus)) {
            tx.executeWithoutResult(s -> {
                if (orderMapper.casStatus(order.getId(), "PENDING", "PAID", now) == 0) {
                    return; // 已被其他路径推进
                }
                subscriptionService.extend(order.getTenantId(), order.getDays(), "PAY");
                orderMapper.casStatus(order.getId(), "PAID", "ACTIVATED", now);
            });
            log.info("订阅支付开通 subOrder={} tenant={}", order.getId(), order.getTenantId());
        } else if ("FAILED".equals(payStatus)) {
            orderMapper.casStatus(order.getId(), "PENDING", "FAILED", null);
        }
        // PROCESSING：保持 PENDING，下轮继续
    }
}