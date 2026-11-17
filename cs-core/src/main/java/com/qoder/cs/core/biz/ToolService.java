package com.qoder.cs.core.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.llm.LlmModel.ToolSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * LLM 工具注册表：白名单硬编码、全只读（create_ticket 仅产草稿）。
 * 身份参数（userId/passengerId/driverId）由会话上下文服务端注入，LLM 不可覆盖；
 * 订单详情做归属校验，防越权查他人订单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final RideClient ride;
    private final PaymentProClient pp;
    private final ObjectMapper json = new ObjectMapper();

    public List<ToolSpec> specsFor(SessionDTO session) {
        List<ToolSpec> specs = new ArrayList<>();
        String role = session.getUserRole();
        if ("PASSENGER".equals(role) || "DRIVER".equals(role)) {
            specs.add(spec("query_ride_order", "按订单号查询打车订单详情（状态/金额/司机/时间）。仅限当前用户自己的订单。",
                    Map.of("type", "object", "properties",
                            Map.of("orderId", Map.of("type", "string", "description", "订单号，如 O12")),
                            "required", List.of("orderId"))));
            specs.add(spec("list_ride_orders", "查询当前用户的打车订单列表，可按状态过滤。",
                    Map.of("type", "object", "properties", Map.of(
                            "status", Map.of("type", "string", "description", "可选：DISPATCHING/ACCEPTED/ARRIVED/IN_PROGRESS/COMPLETED/PAID/CANCELLED/EXPIRED/REFUNDED"),
                            "page", Map.of("type", "integer", "description", "页码，默认 1"),
                            "size", Map.of("type", "integer", "description", "每页条数，默认 5")))));
            specs.add(spec("query_wallet", "查询当前用户钱包余额（含冻结与可用余额，单位分）。",
                    Map.of("type", "object", "properties", Map.of())));
            specs.add(spec("query_ledger", "查询当前用户钱包流水（充值/支付/退款/提现等），可按类型过滤。",
                    Map.of("type", "object", "properties", Map.of(
                            "bizType", Map.of("type", "string", "description", "可选：TOPUP/WITHDRAW/PAY_DEBIT/PAY_CREDIT/REFUND_CREDIT 等"),
                            "page", Map.of("type", "integer"), "size", Map.of("type", "integer")))));
            specs.add(spec("query_withdraws", "查询当前用户提现单列表与状态（受理/处理中/成功/失败）。",
                    Map.of("type", "object", "properties", Map.of())));
            specs.add(spec("pricing_rules", "查询打车计价规则（分车型费率/高峰夜间系数/远途费/最低消费）。",
                    Map.of("type", "object", "properties", Map.of())));
        }
        if ("MERCHANT".equals(role) && pp.enabled()) {
            specs.add(spec("query_pp_trade", "按 payment-pro 订单号（O 开头）查询交易订单与支付单状态。",
                    Map.of("type", "object", "properties",
                            Map.of("orderId", Map.of("type", "string", "description", "payment-pro 订单号")),
                            "required", List.of("orderId"))));
            specs.add(spec("query_pp_balance", "按 payment-pro 账户 ID 查询余额（单位分）。",
                    Map.of("type", "object", "properties",
                            Map.of("accountId", Map.of("type", "string")),
                            "required", List.of("accountId"))));
        }
        specs.add(spec("create_ticket", "当问题无法通过查询与知识库解决、或用户明确要求人工处理时，生成工单草稿（需用户确认后才真正创建）。不要重复调用。",
                Map.of("type", "object", "properties", Map.of(
                        "category", Map.of("type", "string", "description", "分类：计价/支付/退款/提现/账号/投诉/其他"),
                        "summary", Map.of("type", "string", "description", "问题摘要（一句话）"),
                        "orderId", Map.of("type", "string", "description", "关联订单号（可选）")),
                        "required", List.of("category", "summary"))));
        return specs;
    }

    /** 执行工具，返回 JSON 字符串回填 LLM；失败返回 {"error": ...} 而非抛异常（对话不中断）。 */
    public String execute(SessionDTO session, String name, String argumentsJson) {
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                    ? json.createObjectNode() : json.readTree(argumentsJson);
            JsonNode data = dispatch(session, name, args);
            return json.writeValueAsString(mask(data));
        } catch (Exception e) {
            log.warn("工具执行失败 tool={} session={} err={}", name, session.getId(), e.getMessage());
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private JsonNode dispatch(SessionDTO session, String name, JsonNode args) {
        String uid = session.getUserId();
        String role = session.getUserRole();
        boolean rideUser = "PASSENGER".equals(role) || "DRIVER".equals(role);
        switch (name) {
            case "query_ride_order": {
                requireRideUser(rideUser);
                String orderId = args.path("orderId").asText("");
                JsonNode order = ride.get("/api/orders/" + enc(orderId));
                boolean owned = uid.equals(order.path("passengerId").asText())
                        || uid.equals(order.path("driverId").asText());
                if (!owned) {
                    throw com.qoder.cs.common.exception.BizException.notFound("订单不存在或不属于当前用户");
                }
                return order;
            }
            case "list_ride_orders": {
                requireRideUser(rideUser);
                StringBuilder q = new StringBuilder("/api/orders/query?");
                q.append("PASSENGER".equals(role) ? "passengerId=" : "driverId=").append(enc(uid));
                if (args.hasNonNull("status")) {
                    q.append("&status=").append(enc(args.get("status").asText()));
                }
                q.append("&page=").append(args.path("page").asInt(1));
                q.append("&size=").append(Math.min(args.path("size").asInt(5), 20));
                return ride.get(q.toString());
            }
            case "query_wallet":
                requireRideUser(rideUser);
                return ride.get("/api/wallets/" + enc(uid));
            case "query_ledger": {
                requireRideUser(rideUser);
                StringBuilder q = new StringBuilder("/api/wallets/" + enc(uid) + "/ledger?");
                if (args.hasNonNull("bizType")) {
                    q.append("bizType=").append(enc(args.get("bizType").asText())).append('&');
                }
                q.append("page=").append(args.path("page").asInt(1));
                q.append("&size=").append(Math.min(args.path("size").asInt(5), 20));
                return ride.get(q.toString());
            }
            case "query_withdraws":
                requireRideUser(rideUser);
                return ride.get("/api/wallets/" + enc(uid) + "/withdraws");
            case "pricing_rules":
                return ride.get("/api/pricing/rules");
            case "query_pp_trade": {
                requireMerchant(role);
                return pp.callRead("trade.query", Map.of("orderId", args.path("orderId").asText("")));
            }
            case "query_pp_balance": {
                requireMerchant(role);
                return pp.callRead("account.balance", Map.of("accountId", args.path("accountId").asText("")));
            }
            case "create_ticket": {
                ObjectNode draft = json.createObjectNode();
                draft.put("draft", true);
                draft.put("sessionId", session.getId());
                draft.put("tenantCode", session.getTenantCode());
                draft.put("category", args.path("category").asText("其他"));
                draft.put("summary", args.path("summary").asText("用户问题待处理"));
                if (args.hasNonNull("orderId")) {
                    draft.put("orderId", args.get("orderId").asText());
                }
                return draft;
            }
            default:
                throw com.qoder.cs.common.exception.BizException.param("未知工具: " + name);
        }
    }

    private static void requireRideUser(boolean ok) {
        if (!ok) {
            throw com.qoder.cs.common.exception.BizException.biz("当前身份不可使用打车查询工具");
        }
    }

    private static void requireMerchant(String role) {
        if (!"MERCHANT".equals(role)) {
            throw com.qoder.cs.common.exception.BizException.biz("payment-pro 查询仅限商户身份");
        }
    }

    /** 手机号等敏感字段脱敏后再进 prompt。 */
    private JsonNode mask(JsonNode node) {
        if (node == null) {
            return json.nullNode();
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual() && e.getKey().toLowerCase().contains("phone")) {
                    String v = e.getValue().asText();
                    if (v.length() >= 7) {
                        obj.put(e.getKey(), v.substring(0, 3) + "****" + v.substring(v.length() - 4));
                    }
                } else {
                    mask(e.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                mask(child);
            }
        }
        return node;
    }

    private static ToolSpec spec(String name, String description, Map<String, Object> parameters) {
        return new ToolSpec(name, description, parameters);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}