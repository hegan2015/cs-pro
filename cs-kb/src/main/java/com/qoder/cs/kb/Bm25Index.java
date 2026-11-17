package com.qoder.cs.kb;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文 BM25 倒排索引（零依赖）。
 * 分词：ASCII 字母数字词 + CJK 单字与相邻二元组（bigram 携带更多语义）。
 * 线程安全：rebuild 生成新索引后原子替换引用，读路径无锁。
 */
public class Bm25Index {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");
    private static final double K1 = 1.5;
    private static final double B = 0.75;

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
}