package com.qoder.cs.gateway.web;

import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.subscription.SubscriptionPayService;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.facade.dto.SubOrderDTO;
import com.qoder.cs.facade.dto.SubscriptionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 订阅用户端：状态查询 / 激活码兑换 / 余额支付开通（可选通道）。 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final SubscriptionPayService pay;

    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam String tenantCode,
                                              @RequestParam(required = false) String userId) {
        Map<String, Object> out = subscriptions.status(tenantCode, userId);
        out.put("payEnabled", pay.payEnabled());
        return Result.ok(out);
    }

    @PostMapping("/redeem")
    public Result<SubscriptionDTO> redeem(@RequestBody Map<String, String> body) {
        return Result.ok(subscriptions.redeem(body.get("tenantCode"), body.get("code")));
    }

    @PostMapping("/orders")
    public Result<SubOrderDTO> createOrder(@RequestBody Map<String, String> body) {
        return Result.ok(pay.createOrder(body.get("tenantCode"), body.get("payerAccountId")));
    }

    @GetMapping("/orders/{id}")
    public Result<SubOrderDTO> getOrder(@PathVariable String id) {
        return Result.ok(pay.get(id));
    }
}