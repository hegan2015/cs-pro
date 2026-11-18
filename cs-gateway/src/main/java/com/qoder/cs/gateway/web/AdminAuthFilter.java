package com.qoder.cs.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.common.result.Result;
import com.qoder.cs.common.result.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 管理端点鉴权：X-Admin-Token。cs.admin.token 支持逗号分隔多值（轮换期新旧令牌并存）。 */
@Component
@Order(2)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final java.util.Set<String> tokens;
    private final ObjectMapper json = new ObjectMapper();

    public AdminAuthFilter(@Value("${cs.admin.token:cs-admin}") String token) {
        this.tokens = new java.util.HashSet<>();
        for (String t : token.split(",")) {
            if (!t.isBlank()) {
                this.tokens.add(t.trim());
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/admin/")
                && !tokens.contains(request.getHeader("X-Admin-Token"))) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(json.writeValueAsBytes(
                    Result.fail(ResultCode.PARAM_ERROR, "管理令牌无效（X-Admin-Token）")));
            return;
        }
        chain.doFilter(request, response);
    }
}