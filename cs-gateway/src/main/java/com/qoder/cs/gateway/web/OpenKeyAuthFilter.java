package com.qoder.cs.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.open.OpenKeyService;
import com.qoder.cs.facade.dto.TenantDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 开放 API 鉴权：/api/open/** 需 X-Open-Key（租户级凭证）。
 * 校验通过后将租户 id/code 放入请求属性，供 OpenApiController 做数据归属隔离。
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class OpenKeyAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_TENANT_ID = "openTenantId";
    public static final String ATTR_TENANT_CODE = "openTenantCode";

    private final OpenKeyService openKeys;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/open/")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            TenantDTO t = openKeys.resolve(request.getHeader("X-Open-Key"));
            request.setAttribute(ATTR_TENANT_ID, t.getId());
            request.setAttribute(ATTR_TENANT_CODE, t.getCode());
        } catch (BizException e) {
            int http = "A0403".equals(e.getCode()) ? 403 : 401;
            response.setStatus(http);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(json.writeValueAsBytes(Result.fail(e.getCode(), e.getMessage())));
            return;
        }
        chain.doFilter(request, response);
    }
}