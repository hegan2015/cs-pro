package com.qoder.cs.core.subscription;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.facade.dto.ActivationCodeDTO;
import com.qoder.cs.facade.dto.SubscriptionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户订阅域：准入判定（expire_at 实时校验）、激活码兑换（CAS 一码一用）、
 * 免费额度（CAS 不超发）、续订天数叠加。可靠性全部服务端兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final long DAY_MS = 86_400_000L;
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantMapper tenantMapper;
    private final com.qoder.cs.common.id.IdGenerator ids;
    private final ActivationCodeMapper codeMapper;
    private final CsProperties props;
    private final TransactionTemplate tx;

    /** 内置平台租户（幂等，只增不删）。 */
    public void ensureBuiltinTenants() {
        createTenantIfAbsent("ride-pro", "ride-pro 打车平台");
        createTenantIfAbsent("payment-pro", "payment-pro 支付平台");
    }

    public TenantDTO createTenantIfAbsent(String code, String name) {
        TenantDTO existing = tenantMapper.selectByCode(code);
        if (existing != null) {
            return existing;
        }
        TenantDTO t = new TenantDTO();
        t.setId("TN" + ids.next("seq_tenant"));
        t.setCode(code);
        t.setName(name);
        t.setCreatedAt(System.currentTimeMillis());
        try {
            tenantMapper.insert(t);
        } catch (DuplicateKeyException e) {
            return tenantMapper.selectByCode(code);
        }
        return t;
    }

    public TenantDTO createTenant(String code, String name) {
        if (code == null || !code.matches("[a-zA-Z0-9_-]{2,32}")) {
            throw BizException.param("租户编码需为 2-32 位字母数字_-");
        }
        if (name == null || name.isBlank()) {
            throw BizException.param("租户名称必填");
        }
        if (tenantMapper.selectByCode(code) != null) {
            throw BizException.conflict("租户已存在: " + code);
        }
        return createTenantIfAbsent(code, name);
    }

    public TenantDTO requireTenant(String code) {
        TenantDTO t = tenantMapper.selectByCode(code);
        if (t == null) {
            throw BizException.notFound("租户不存在: " + code);
        }
        if (!"ACTIVE".equals(t.getStatus())) {
            throw BizException.conflict("租户已冻结: " + code);
        }
        return t;
    }

    public List<TenantDTO> listTenants() {
        return tenantMapper.selectAll();
    }

    public void setTenantStatus(String tenantId, String status) {
        if (!"ACTIVE".equals(status) && !"FROZEN".equals(status)) {
            throw BizException.param("status 仅 ACTIVE/FROZEN");
        }
        if (tenantMapper.updateStatus(tenantId, status) == 0) {
            throw BizException.notFound("租户不存在: " + tenantId);
        }
    }

    /** 订阅有效性：每次请求实时判定，不依赖调度器翻状态。 */
    public boolean isSubscribed(String tenantId) {
        SubscriptionDTO sub = tenantMapper.selectSubscription(tenantId);
        return sub != null && sub.getExpireAt() != null && sub.getExpireAt() > System.currentTimeMillis();
    }

    public Map<String, Object> status(String tenantCode, String userId) {
        TenantDTO t = requireTenant(tenantCode);
        SubscriptionDTO sub = tenantMapper.selectSubscription(t.getId());
        boolean subscribed = sub != null && sub.getExpireAt() > System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", t);
        out.put("subscribed", subscribed);
        out.put("subscription", sub);
        out.put("plan", Map.of("name", props.getPlan().getName(),
                "days", props.getPlan().getDays(), "price", props.getPlan().getPrice()));
        out.put("planCode", sub != null && sub.getPlanCode() != null ? sub.getPlanCode() : "free");
        out.put("plans", props.getPlans());
        Map<String, Object> free = new LinkedHashMap<>();
        free.put("limit", props.getFree().getDailyQuota());
        free.put("used", userId == null ? 0 : freeUsed(userId));
        out.put("freeUsage", free);
        return out;
    }

    /** 续订叠加：newExpire = max(now, 当前expire) + days。 */
    public SubscriptionDTO extend(String tenantId, int days, String source) {
        return extend(tenantId, days, source, "standard");
    }

    public SubscriptionDTO extend(String tenantId, int days, String source, String planCode) {
        if (days <= 0 || days > 3650) {
            throw BizException.param("days 越界");
        }
        String code = planCode == null || planCode.isBlank() ? "standard" : planCode;
        long now = System.currentTimeMillis();
        SubscriptionDTO cur = tenantMapper.selectSubscription(tenantId);
        long base = cur != null && cur.getExpireAt() > now ? cur.getExpireAt() : now;
        SubscriptionDTO sub = new SubscriptionDTO();
        sub.setTenantId(tenantId);
        sub.setPlanName(props.planSpec(code).getName());
        sub.setPlanCode(code);
        sub.setDays(days);
        sub.setStatus("ACTIVE");
        sub.setStartAt(cur != null && cur.getExpireAt() > now ? cur.getStartAt() : now);
        sub.setExpireAt(base + days * DAY_MS);
        sub.setSource(source);
        sub.setUpdatedAt(now);
        tenantMapper.upsertSubscription(sub);
        log.info("订阅开通/续订 tenant={} days={} plan={} source={} expireAt={}", tenantId, days, code, source, sub.getExpireAt());
        return sub;
    }

    public List<ActivationCodeDTO> generateCodes(int days, int count) {
        return generateCodes(days, count, "standard");
    }

    public List<ActivationCodeDTO> generateCodes(int days, int count, String planCode) {
        if (days <= 0 || days > 3650) {
            throw BizException.param("days 越界");
        }
        if (count <= 0 || count > 100) {
            throw BizException.param("count 需 1-100");
        }
        String code = planCode == null || planCode.isBlank() ? "standard" : planCode;
        if (!props.getPlans().containsKey(code)) {
            throw BizException.param("未知套餐: " + code);
        }
        List<ActivationCodeDTO> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ActivationCodeDTO c = new ActivationCodeDTO();
            c.setCode(randomCode());
            c.setDays(days);
            c.setPlanCode(code);
            c.setCreatedAt(System.currentTimeMillis());
            codeMapper.insert(c);
            codes.add(c);
        }
        return codes;
    }

    public List<ActivationCodeDTO> listCodes(String status) {
        return codeMapper.list(status);
    }

    /** 兑换：单事务 CAS 一码一用 + 延长订阅。 */
    public SubscriptionDTO redeem(String tenantCode, String code) {
        TenantDTO t = requireTenant(tenantCode);
        if (code == null || code.isBlank()) {
            throw BizException.param("激活码必填");
        }
        String normalized = code.trim().toUpperCase();
        return tx.execute(s -> {
            if (codeMapper.redeem(normalized, t.getId(), System.currentTimeMillis()) == 0) {
                throw BizException.conflict("激活码无效或已被使用");
            }
            ActivationCodeDTO c = codeMapper.selectByCode(normalized);
            return extend(t.getId(), c.getDays(), "CODE", c.getPlanCode());
        });
    }

    public SubscriptionDTO grant(String tenantId, int days) {
        if (tenantMapper.selectById(tenantId) == null) {
            throw BizException.notFound("租户不存在: " + tenantId);
        }
        return extend(tenantId, days, "ADMIN");
    }

    public List<Map<String, Object>> allSubscriptions() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (TenantDTO t : tenantMapper.selectAll()) {
            SubscriptionDTO sub = tenantMapper.selectSubscription(t.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenant", t);
            row.put("subscription", sub);
            row.put("subscribed", sub != null && sub.getExpireAt() > now);
            out.add(row);
        }
        return out;
    }

    // ---- 免费额度 ----

    public int freeUsed(String userId) {
        Integer c = tenantMapper.freeCount(userId, today());
        return c == null ? 0 : c;
    }

    /** 原子消费一次免费额度；超额返回 false（并发不超发）。 */
    public boolean tryConsumeFree(String userId) {
        String day = today();
        int quota = props.getFree().getDailyQuota();
        if (tenantMapper.incrementFree(userId, day, quota) > 0) {
            return true;
        }
        try {
            tenantMapper.insertFree(userId, day);
            return true;
        } catch (DuplicateKeyException e) {
            return tenantMapper.incrementFree(userId, day, quota) > 0;
        }
    }

    /** 展示态同步（正确性不依赖它）。 */
    public void syncExpiredDisplay() {
        tenantMapper.markExpired(System.currentTimeMillis());
    }

    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder("CS-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }
}