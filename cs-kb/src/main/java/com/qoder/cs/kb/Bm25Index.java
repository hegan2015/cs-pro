package com.qoder.cs.kb;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文 BM25 倒排索引（零依赖）。
 * 分词：ASCII 字母数字词 + CJK 单字与相邻二元组（bigram 携带更多语义）。
 * 查询扩展：同义词表（classpath:/synonyms.txt，每行逗号分组）命中时把同组词并入查询，弥补无分词的召回短板。
 * 线程安全：rebuild 生成新索引后原子替换引用，读路径无锁。
 */
public class Bm25Index {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private static final List<String[]> SYN_GROUPS = loadSynonyms();

    public record Scored(String id, double score) {
    }

    private record Doc(String id, int length, Map<String, Integer> tf) {
    }

    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of(), 0);

    private record Snapshot(List<Doc> docs, Map<String, Integer> df, double avgLen) {
    }

    public void rebuild(Map<String, String> documents) {
        List<Doc> docs = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;
        for (var e : documents.entrySet()) {
            Map<String, Integer> tf = tokenize(e.getValue());
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            docs.add(new Doc(e.getKey(), len, tf));
            totalLen += len;
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        double avg = docs.isEmpty() ? 0 : (double) totalLen / docs.size();
        snapshot = new Snapshot(docs, df, avg);
    }

    public List<Scored> search(String query, int limit) {
        Snapshot s = snapshot;
        if (s.docs().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> qtf = tokenize(query);
        expandSynonyms(query.toLowerCase(Locale.ROOT), qtf);
        int n = s.docs().size();
        List<Scored> result = new ArrayList<>();
        for (Doc doc : s.docs()) {
            double score = 0;
            for (String term : qtf.keySet()) {
                Integer dfi = s.df().get(term);
                if (dfi == null) {
                    continue;
                }
                int tf = doc.tf().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                double idf = Math.log(1 + (n - dfi + 0.5) / (dfi + 0.5));
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.length() / s.avgLen()));
                score += idf * norm;
            }
            if (score > 0) {
                result.add(new Scored(doc.id(), score));
            }
        }
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /** question 重复一次提升问题词权重；keywords/answer 参与召回。 */
    public static String docText(String question, String keywords, String answer) {
        return question + " " + question + " " + (keywords == null ? "" : keywords) + " " + answer;
    }

    static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> tf = new HashMap<>();
        if (text == null || text.isBlank()) {
            return tf;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = ASCII_WORD.matcher(lower);
        while (m.find()) {
            tf.merge(m.group(), 1, Integer::sum);
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk.append(c);
            } else {
                flushCjk(cjk, tf);
            }
        }
        flushCjk(cjk, tf);
        return tf;
    }

    private static void flushCjk(StringBuilder cjk, Map<String, Integer> tf) {
        if (cjk.length() == 0) {
            return;
        }
        for (int i = 0; i < cjk.length(); i++) {
            tf.merge(String.valueOf(cjk.charAt(i)), 1, Integer::sum);
            if (i + 1 < cjk.length()) {
                tf.merge(cjk.substring(i, i + 2), 1, Integer::sum);
            }
        }
        cjk.setLength(0);
    }

    /** 查询句中出现组内任一词，则把组内其余词的 term 并入查询（权重 1，不改变已有权重）。 */
    private static void expandSynonyms(String lowerQuery, Map<String, Integer> qtf) {
        for (String[] group : SYN_GROUPS) {
            boolean hit = false;
            for (String word : group) {
                if (lowerQuery.contains(word)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            for (String word : group) {
                if (lowerQuery.contains(word)) {
                    continue;
                }
                for (String term : tokenize(word).keySet()) {
                    qtf.merge(term, 1, Math::max);
                }
            }
        }
    }

    private static List<String[]> loadSynonyms() {
        List<String[]> groups = new ArrayList<>();
        try (InputStream in = Bm25Index.class.getResourceAsStream("/synonyms.txt")) {
            if (in == null) {
                return List.of();
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String[] words = Arrays.stream(t.split("[,，]"))
                        .map(w -> w.trim().toLowerCase(Locale.ROOT))
                        .filter(w -> !w.isEmpty())
                        .distinct()
                        .toArray(String[]::new);
                if (words.length >= 2) {
                    groups.add(words);
                }
            }
        } catch (Exception e) {
            // 同义词表加载失败不影响检索主链路
        }
        return List.copyOf(groups);
    }
}