package com.qoder.cs.core.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * payment-pro OpenAPI 客户端（HTTPS 8443 + HMAC-SHA256 验签）。
 * 端点白名单：只读 trade.query/account.balance；订阅支付专用 trade.create/trade.pay（§3A.5）。
 * 未配置 config/payment-pro.properties 时 enabled=false，整体禁用。
 */
@Slf4j
@Component
public class PaymentProClient {

    private static final Set<String> READ_METHODS = Set.of("trade.query", "account.balance");
    private static final Set<String> SUB_PAY_METHODS = Set.of("trade.create", "trade.pay");

    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public PaymentProClient(@Value("${cs.pp.base-url:}") String baseUrl,
                            @Value("${cs.pp.app-id:}") String appId,
                            @Value("${cs.pp.app-secret:}") String appSecret,
                            @Value("${cs.pp.cert-path:}") String certPath) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.appId = appId;
        this.appSecret = appSecret;
        this.enabled = !this.baseUrl.isBlank() && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
        this.http = enabled ? buildClient(certPath) : null;
        if (enabled) {
            log.info("payment-pro OpenAPI 已启用 baseUrl={} appId={}", this.baseUrl, appId);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public JsonNode callRead(String method, Map<String, Object> params) {
        if (!READ_METHODS.contains(method)) {
            throw BizException.param("非只读端点禁止经 callRead 调用: " + method);
        }
        return call(method, params);
    }

    /** 仅供 SubscriptionPayService 使用（金额/商户服务端固定）。 */
    public JsonNode callSubscriptionPay(String method, Map<String, Object> params) {
        if (!SUB_PAY_METHODS.contains(method)) {
            throw BizException.param("非订阅支付端点: " + method);
        }
        return call(method, params);
    }

    private JsonNode call(String method, Map<String, Object> params) {
        if (!enabled) {
            throw BizException.biz("payment-pro 集成未配置");
        }
        try {
            Map<String, Object> body = new TreeMap<>(params);
            body.put("appId", appId);
            body.put("timestamp", System.currentTimeMillis());
            body.put("sign", sign(body));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/openapi/" + method))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw BizException.downstream("payment-pro " + resp.statusCode() + ": " + errorMessage(resp.body()));
            }
            return json.readTree(resp.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.downstream("payment-pro 不可达: " + e.getMessage());
        }
    }

    /** TreeMap 排序、剔除 sign、跳过 null → k=v&… → HmacSHA256 小写 hex（与对端同构）。 */
    private String sign(Map<String, Object> body) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<>(body).entrySet()) {
            if ("sign".equals(e.getKey()) || e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private HttpClient buildClient(String certPath) {
        try {
            if (certPath == null || certPath.isBlank()) {
                throw new IllegalStateException("缺少 cs.pp.cert-path");
            }
            Certificate cert;
            try (FileInputStream in = new FileInputStream(certPath)) {
                cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("payment-pro", cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(ssl)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("payment-pro 证书加载失败: " + e.getMessage(), e);
        }
    }

    private String errorMessage(String body) {
        try {
            return json.readTree(body).path("error").asText(body);
        } catch (Exception e) {
            return body == null ? "" : body;
        }
    }
}