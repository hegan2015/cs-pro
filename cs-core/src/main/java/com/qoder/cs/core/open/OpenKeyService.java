package com.qoder.cs.core.open;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.subscription.TenantMapper;
import com.qoder.cs.facade.dto.OpenKeyDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * 开放 API 凭证：admin 为租户生成/吊销 X-Open-Key，租户凭此自助查询本租户数据，
 * 无需共享全局 admin token。校验失败统一 401，租户冻结 403。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenKeyMapper keys;
    private final TenantMapper tenantMapper;
    private final SubscriptionService subscriptions;

    public OpenKeyDTO generate(String tenantCode) {
        TenantDTO t = subscriptions.requireTenant(tenantCode);
        OpenKeyDTO k = new OpenKeyDTO();
        k.setApiKey("ok-" + randomHex(32));
        k.setTenantId(t.getId());
        k.setSecret(randomHex(48));
        k.setCreatedAt(System.currentTimeMillis());
        keys.insert(k);
        log.info("开放凭证生成 tenant={} key={}", tenantCode, k.getApiKey());
        return k;
    }

    public void revoke(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw BizException.param("apiKey 必填");
        }
        if (keys.revoke(apiKey.trim()) == 0) {
            throw BizException.notFound("凭证不存在或已吊销: " + apiKey);
        }
        log.info("开放凭证吊销 key={}", apiKey);
    }

    public java.util.Map<String, Object> query(String tenantCode, int page, int size) {
        String tenantId = null;
        if (tenantCode != null && !tenantCode.isBlank()) {
            tenantId = subscriptions.requireTenant(tenantCode).getId();
        }
        int limit = Math.min(size, 100);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", keys.count(tenantId));
        out.put("list", keys.page(tenantId, (page - 1) * limit, limit));
        return out;
    }

    /** 校验凭证并返回所属租户（冻结租户拒绝）。供 OpenKeyAuthFilter 调用。 */
    public TenantDTO resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw BizException.unauthorized("缺少开放凭证（X-Open-Key）");
        }
        OpenKeyDTO k = keys.selectByKey(apiKey.trim());
        if (k == null || !"ACTIVE".equals(k.getStatus())) {
            throw BizException.unauthorized("开放凭证无效或已吊销");
        }
        TenantDTO t = tenantMapper.selectById(k.getTenantId());
        if (t == null) {
            throw BizException.unauthorized("凭证所属租户不存在");
        }
        if (!"ACTIVE".equals(t.getStatus())) {
            throw BizException.forbidden("租户已冻结: " + t.getCode());
        }
        return t;
    }

    private static String randomHex(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return sb.toString();
    }
}