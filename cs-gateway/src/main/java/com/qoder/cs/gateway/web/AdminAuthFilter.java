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

/** 管理端点鉴权：X-Admin-Token（默认 cs-admin，可用 ADMIN_TOKEN 环境变量覆盖）。 */
@Component
@Order(2)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final String token;
    private final ObjectMapper json = new ObjectMapper();

    public AdminAuthFilter(@Value("${cs.admin.token:cs-admin}") String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/admin/")
                && !token.equals(request.getHeader("X-Admin-Token"))) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(json.writeValueAsBytes(
                    Result.fail(ResultCode.PARAM_ERROR, "管理令牌无效（X-Admin-Token）")));
            return;
        }
        chain.doFilter(request, response);
    }
}