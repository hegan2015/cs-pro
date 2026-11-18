package com.qoder.cs.core.billing;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.subscription.TenantMapper;
import com.qoder.cs.facade.dto.InvoiceDTO;
import com.qoder.cs.facade.dto.SubscriptionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 月度账单：聚合租户账期内 OK 状态 LLM 调用 token，按套餐千 token 单价向上取整计费（分）。
 * 幂等出账：同租户同账期重复生成覆盖更新（唯一约束兜底），金额随用量重算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final InvoiceMapper invoices;
    private final LlmCallMapper llmCalls;
    private final TenantMapper tenantMapper;
    private final SubscriptionService subscriptions;
    private final CsProperties props;
    private final com.qoder.cs.common.id.IdGenerator ids;

    public InvoiceDTO generate(String tenantCode, String period) {
        TenantDTO t = subscriptions.requireTenant(tenantCode);
        YearMonth ym = parsePeriod(period);
        long from = ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long to = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        InvoiceDTO agg = llmCalls.aggregateByTenant(tenantCode, from, to);
        long prompt = agg.getPromptTokens() == null ? 0 : agg.getPromptTokens();
        long complet = agg.getCompletTokens() == null ? 0 : agg.getCompletTokens();
        long calls = agg.getCalls() == null ? 0 : agg.getCalls();

        SubscriptionDTO sub = tenantMapper.selectSubscription(t.getId());
        String planCode = sub != null && sub.getPlanCode() != null ? sub.getPlanCode() : "standard";
        long pricePerK = props.planSpec(planCode).getTokenPricePerK();
        long total = prompt + complet;
        long amount = (total * pricePerK + 999) / 1000; // 向上取整到分

        InvoiceDTO inv = new InvoiceDTO();
        inv.setId("INV" + ids.next("seq_invoice"));
        inv.setTenantId(t.getId());
        inv.setPeriod(ym.format(PERIOD));
        inv.setPromptTokens(prompt);
        inv.setCompletTokens(complet);
        inv.setCalls(calls);
        inv.setAmount(amount);
        inv.setPlanCode(planCode);
        inv.setCreatedAt(System.currentTimeMillis());
        invoices.upsert(inv);
        log.info("账单出账 tenant={} period={} tokens={} calls={} amount={}分 plan={}",
                tenantCode, inv.getPeriod(), total, calls, amount, planCode);
        return invoices.selectByTenantPeriod(t.getId(), inv.getPeriod());
    }

    public List<InvoiceDTO> list(String tenantCode, String period) {
        if (tenantCode != null && !tenantCode.isBlank()) {
            TenantDTO t = subscriptions.requireTenant(tenantCode);
            List<InvoiceDTO> all = invoices.listByTenant(t.getId());
            if (period != null && !period.isBlank()) {
                String p = parsePeriod(period).format(PERIOD);
                return all.stream().filter(i -> p.equals(i.getPeriod())).toList();
            }
            return all;
        }
        if (period != null && !period.isBlank()) {
            return invoices.listByPeriod(parsePeriod(period).format(PERIOD));
        }
        return invoices.listAll();
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw BizException.param("period 需为 YYYY-MM");
        }
        try {
            return YearMonth.parse(period, PERIOD);
        } catch (Exception e) {
            throw BizException.param("period 非法: " + period);
        }
    }
}