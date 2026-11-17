package com.qoder.cs.core.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** ride-pro 网关只读客户端（GET 类接口，零写入）。 */
@Slf4j
@Component
public class RideClient {

    private final String baseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public RideClient(@Value("${cs.ride.base-url:http://127.0.0.1:8090}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public JsonNode get(String pathAndQuery) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + pathAndQuery))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw BizException.downstream("ride-pro " + resp.statusCode() + ": " + errorMessage(resp.body()));
            }
            return json.readTree(resp.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.downstream("ride-pro 不可达: " + e.getMessage());
        }
    }

    public boolean healthy() {
        try {
            get("/api/health");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String errorMessage(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node.path("error").asText(body);
        } catch (Exception e) {
            return body == null ? "" : body;
        }
    }
}