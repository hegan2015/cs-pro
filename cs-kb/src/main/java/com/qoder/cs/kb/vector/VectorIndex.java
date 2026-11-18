package com.qoder.cs.kb.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.cs.facade.dto.KbEntryDTO;
import com.qoder.cs.kb.KbMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量索引：Qdrant 存储 + 本地 ONNX Embedding（cs-embedding 容器）。
 * 旁路增强——任何失败降级 BM25 并熔断 30s，不阻断知识库主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndex {

    private final KbMapper kbMapper;

    @Value("${cs.vector.enabled:true}")
    private boolean enabled;
    @Value("${cs.vector.qdrant-url:http://127.0.0.1:6333}")
    private String qdrantUrl;
    @Value("${cs.vector.embedding-url:http://127.0.0.1:8100}")
    private String embeddingUrl;
    @Value("${cs.vector.collection:cs_kb}")
    private String collection;
    @Value("${cs.vector.timeout-ms:3000}")
    private int timeoutMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    private volatile long downUntil = 0;
    private volatile boolean collectionReady = false;
    private volatile Status cachedStatus;
    private volatile long statusAt = 0;

    public record VectorHit(String kbId, double score) {
    }

    public record Status(boolean enabled, boolean embeddingUp, boolean qdrantUp,
                         long indexed, String model, int dim) {
    }

    public boolean enabled() {
        return enabled;
    }

    /** 检索：失败/熔断期返回空列表（调用方退回 BM25）。 */
    public List<VectorHit> search(String query, int limit) {
        if (!enabled || System.currentTimeMillis() < downUntil) {
            return List.of();
        }
        try {
            float[] vec = embed(List.of(query)).get(0);
            ensureCollection(vec.length);
            JsonNode resp = request("POST", qdrantUrl + "/collections/" + collection + "/points/search",
                    Map.of("vector", vec, "limit", limit, "with_payload", true, "with_vector", false));
            List<VectorHit> out = new ArrayList<>();
            for (JsonNode p : resp.path("result")) {
                String kbId = p.path("payload").path("kbId").asText(null);
                if (kbId != null) {
                    out.add(new VectorHit(kbId, p.path("score").asDouble()));
                }
            }
            return out;
        } catch (Exception e) {
            trip("search", e);
            return List.of();
        }
    }

    /** KB 增改同步（旁路）：禁用条目从索引移除。 */
    public void syncEntry(KbEntryDTO e) {
        if (!enabled) {
            return;
        }
        if (Boolean.FALSE.equals(e.getEnabled())) {
            removeEntry(e.getId());
            return;
        }
        if (System.currentTimeMillis() < downUntil) {
            return;
        }
        try {
            float[] vec = embed(List.of(docText(e))).get(0);
            ensureCollection(vec.length);
            upsert(List.of(point(e.getId(), vec, e)));
        } catch (Exception ex) {
            trip("sync " + e.getId(), ex);
        }
    }

    public void removeEntry(String id) {
        if (!enabled || System.currentTimeMillis() < downUntil) {
            return;
        }
        try {
            ensureCollectionProbe();
            request("POST", qdrantUrl + "/collections/" + collection + "/points/delete",
                    Map.of("points", List.of(pointId(id))));
        } catch (Exception ex) {
            trip("remove " + id, ex);
        }
    }

    /** 全量重建（管理端/启动补建）：失败抛出由调用方处置。 */
    public int reindex(List<KbEntryDTO> entries) throws Exception {
        List<KbEntryDTO> active = entries.stream().filter(e -> !Boolean.FALSE.equals(e.getEnabled())).toList();
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < active.size(); i += 32) {
            List<KbEntryDTO> batch = active.subList(i, Math.min(i + 32, active.size()));
            List<float[]> vecs = embed(batch.stream().map(VectorIndex::docText).toList());
            for (int j = 0; j < batch.size(); j++) {
                points.add(point(batch.get(j).getId(), vecs.get(j), batch.get(j)));
            }
        }
        int dim = points.isEmpty() ? probeDim() : ((List<?>) points.get(0).get("vector")).size();
        ensureCollection(dim);
        if (!points.isEmpty()) {
            upsert(points);
        }
        collectionReady = true;
        downUntil = 0;
        statusAt = 0;
        log.info("向量索引重建完成 entries={}", points.size());
        return points.size();
    }

    /** 状态探针（10s 缓存，健康页轮询不打爆容器）。 */
    public Status status() {
        long now = System.currentTimeMillis();
        Status c = cachedStatus;
        if (c != null && now - statusAt < 10_000) {
            return c;
        }
        boolean embUp = false;
        boolean qUp = false;
        long indexed = 0;
        String model = null;
        int dim = 0;
        if (enabled) {
            try {
                JsonNode h = request("GET", embeddingUrl + "/health", null);
                embUp = "ok".equals(h.path("status").asText());
                model = h.path("model").asText(null);
                dim = h.path("dim").asInt();
            } catch (Exception ignored) {
            }
            try {
                JsonNode col = request("GET", qdrantUrl + "/collections/" + collection, null);
                qUp = "ok".equals(col.path("status").asText()) || col.has("result");
                indexed = col.path("result").path("points_count").asLong(0);
            } catch (NotFoundException nf) {
                qUp = true; // Qdrant 可达，collection 尚未创建（首次启动）
            } catch (Exception ignored) {
            }
        }
        Status s = new Status(enabled, embUp, qUp, indexed, model, dim);
        cachedStatus = s;
        statusAt = now;
        return s;
    }

    /** 启动自动补建：容器在跑且点数与启用条目数不一致时后台全量重建。 */
    @PostConstruct
    void autoBackfill() {
        if (!enabled) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Status s = status();
                if (!s.embeddingUp() || !s.qdrantUp()) {
                    log.info("向量服务不可达，跳过自动补建（仅 BM25 检索）");
                    return;
                }
                List<KbEntryDTO> entries = kbMapper.selectEnabled();
                if (s.indexed() != entries.size()) {
                    reindex(entries);
                }
            } catch (Exception e) {
                log.warn("向量索引自动补建失败（仅 BM25 检索）: {}", e.getMessage());
            }
        }, "vector-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** RRF 融合排序（Reciprocal Rank Fusion）：返回按融合分降序的 id。 */
    public static List<String> rrfFuse(List<String> a, List<String> b, int k, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < a.size(); i++) {
            scores.merge(a.get(i), 1.0 / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < b.size(); i++) {
            scores.merge(b.get(i), 1.0 / (k + i + 1), Double::sum);
        }
        return scores.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    public static String docText(KbEntryDTO e) {
        String answer = e.getAnswer() == null ? "" : e.getAnswer();
        if (answer.length() > 400) {
            answer = answer.substring(0, 400);
        }
        return e.getQuestion() + "\n" + (e.getKeywords() == null ? "" : e.getKeywords()) + "\n" + answer;
    }

    public static String pointId(String kbId) {
        return UUID.nameUUIDFromBytes(kbId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---- 内部 ----

    private List<float[]> embed(List<String> texts) throws Exception {
        JsonNode resp = request("POST", embeddingUrl + "/embed", Map.of("texts", texts));
        List<float[]> out = new ArrayList<>();
        for (JsonNode v : resp.path("vectors")) {
            float[] arr = new float[v.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = (float) v.get(i).asDouble();
            }
            out.add(arr);
        }
        if (out.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回数量不符: " + out.size() + "/" + texts.size());
        }
        return out;
    }

    private int probeDim() throws Exception {
        return request("GET", embeddingUrl + "/health", null).path("dim").asInt(512);
    }

    private void ensureCollection(int dim) throws Exception {
        if (collectionReady) {
            return;
        }
        try {
            request("GET", qdrantUrl + "/collections/" + collection, null);
            collectionReady = true;
        } catch (NotFoundException e) {
            request("PUT", qdrantUrl + "/collections/" + collection,
                    Map.of("vectors", Map.of("size", dim, "distance", "Cosine")));
            collectionReady = true;
            log.info("Qdrant collection 已创建 name={} dim={}", collection, dim);
        }
    }

    private void ensureCollectionProbe() throws Exception {
        ensureCollection(probeDim());
    }

    private Map<String, Object> point(String kbId, float[] vec, KbEntryDTO e) {
        List<Float> vector = new ArrayList<>(vec.length);
        for (float v : vec) {
            vector.add(v);
        }
        return Map.of("id", pointId(kbId), "vector", vector,
                "payload", Map.of("kbId", kbId, "question", e.getQuestion(),
                        "category", e.getCategory() == null ? "" : e.getCategory()));
    }

    private void upsert(List<Map<String, Object>> points) throws Exception {
        request("PUT", qdrantUrl + "/collections/" + collection + "/points?wait=true",
                Map.of("points", points));
    }

    private void trip(String op, Exception e) {
        downUntil = System.currentTimeMillis() + 30_000;
        collectionReady = false;
        log.warn("向量{}失败，30s 内降级 BM25: {}", op, e.toString());
    }

    private static class NotFoundException extends Exception {
    }

    private JsonNode request(String method, String url, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json");
        switch (method) {
            case "GET" -> b.GET();
            case "POST" -> b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            default -> throw new IllegalArgumentException(method);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            throw new NotFoundException();
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + url);
        }
        return json.readTree(resp.body());
    }
}