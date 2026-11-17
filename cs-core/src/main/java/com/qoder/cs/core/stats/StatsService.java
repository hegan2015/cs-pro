package com.qoder.cs.core.stats;

import com.qoder.cs.core.biz.PaymentProClient;
import com.qoder.cs.core.biz.RideClient;
import com.qoder.cs.core.chat.LlmCallMapper;
import com.qoder.cs.core.chat.MessageMapper;
import com.qoder.cs.core.chat.SessionMapper;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.core.ticket.RatingMapper;
import com.qoder.cs.core.ticket.TicketMapper;
import com.qoder.cs.facade.dto.KbEntryDTO;
import com.qoder.cs.kb.KbService;
import com.qoder.cs.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统计：实时 SQL 汇总 + stats_daily 每日快照（MERGE 幂等，历史趋势不依赖明细留存）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final TicketMapper ticketMapper;
    private final LlmCallMapper llmCallMapper;
    private final RatingMapper ratingMapper;
    private final StatsMapper statsMapper;
    private final KbService kb;
    private final LlmClient llm;
    private final RideClient ride;
    private final PaymentProClient pp;
    private final SubscriptionService subscriptions;

    public Map<String, Object> overview() {
        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long tomorrowStart = todayStart + 86_400_000L;
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("total", sessionMapper.count(null, null, null));
        sessions.put("active", sessionMapper.count("ACTIVE", null, null));
        sessions.put("today", sessionMapper.count(null, todayStart, tomorrowStart - 1));
        out.put("sessions", sessions);

        Map<String, Object> messages = new LinkedHashMap<>();
        messages.put("total", messageMapper.countAll());
        messages.put("today", messageMapper.countBetween(todayStart, tomorrowStart));
        messages.put("avgBotLatencyMs", Math.round(statsMapper.avgBotLatency()));
        out.put("messages", messages);

        Map<String, Object> tickets = new LinkedHashMap<>();
        tickets.put("total", ticketMapper.count(null, null, null));
        tickets.put("open", ticketMapper.countByStatus("OPEN"));
        tickets.put("processing", ticketMapper.countByStatus("PROCESSING"));
        tickets.put("resolved", ticketMapper.countByStatus("RESOLVED"));
        tickets.put("closed", ticketMapper.countByStatus("CLOSED"));
        tickets.put("today", ticketMapper.countBetween(todayStart, tomorrowStart));
        out.put("tickets", tickets);

        long kbHits = 0;
        for (KbEntryDTO e : kb.list()) {
            kbHits += e.getHitCount() == null ? 0 : e.getHitCount();
        }
        Map<String, Object> knowledge = new LinkedHashMap<>();
        knowledge.put("entries", kb.list().size());
        knowledge.put("hits", kbHits);
        out.put("kb", knowledge);

        Map<String, Object> llmStats = new LinkedHashMap<>();
        llmStats.put("available", llm.available());
        llmStats.put("model", llm.model());
        llmStats.put("ok", llmCallMapper.countByStatus("OK"));
        llmStats.put("error", llmCallMapper.countByStatus("ERROR"));
        llmStats.put("tokens", llmCallMapper.sumTokens());
        out.put("llm", llmStats);

        Map<String, Object> ratings = new LinkedHashMap<>();
        for (String type : List.of("SESSION", "MESSAGE", "TICKET")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("count", ratingMapper.countByType(type));
            r.put("avg", Math.round(ratingMapper.avgByType(type) * 100) / 100.0);
            r.put("low", ratingMapper.lowCountByType(type));
            ratings.put(type.toLowerCase(), r);
        }
        out.put("ratings", ratings);

        long subscribed = subscriptions.allSubscriptions().stream()
                .filter(r -> Boolean.TRUE.equals(r.get("subscribed"))).count();
        Map<String, Object> subs = new LinkedHashMap<>();
        subs.put("tenants", subscriptions.listTenants().size());
        subs.put("subscribed", subscribed);
        out.put("subscriptions", subs);

        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("ridePro", ride.healthy());
        upstream.put("paymentPro", pp.enabled());
        out.put("upstream", upstream);
        return out;
    }

    /** 近 N 天趋势：当日实时统计，历史读快照（缺失时回补实时值）。 */
    public List<Map<String, Object>> daily(int days) {
        if (days <= 0 || days > 90) {
            days = 7;
        }
        LocalDate today = LocalDate.now();
        String fromDay = today.minusDays(days - 1L).format(DAY);
        Map<String, Map<String, Long>> snapshot = new LinkedHashMap<>();
        for (Map<String, Object> row : statsMapper.range(fromDay, today.format(DAY))) {
            String day = String.valueOf(row.get("day"));
            String metric = String.valueOf(row.get("metric"));
            long value = ((Number) row.get("value")).longValue();
            snapshot.computeIfAbsent(day, k -> new LinkedHashMap<>()).put(metric, value);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            String day = d.format(DAY);
            Map<String, Long> metrics = snapshot.getOrDefault(day, Map.of());
            long start = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long end = start + 86_400_000L;
            boolean isToday = i == 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day);
            row.put("sessions", isToday ? sessionMapper.count(null, start, end - 1)
                    : metrics.getOrDefault("sessions", sessionMapper.count(null, start, end - 1)));
            row.put("messages", isToday ? messageMapper.countBetween(start, end)
                    : metrics.getOrDefault("messages", messageMapper.countBetween(start, end)));
            row.put("tickets", isToday ? ticketMapper.countBetween(start, end)
                    : metrics.getOrDefault("tickets", ticketMapper.countBetween(start, end)));
            out.add(row);
        }
        return out;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 30_000)
    public void snapshotToday() {
        LocalDate today = LocalDate.now();
        long start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = start + 86_400_000L;
        String day = today.format(DAY);
        statsMapper.upsertDaily(day, "sessions", sessionMapper.count(null, start, end - 1));
        statsMapper.upsertDaily(day, "messages", messageMapper.countBetween(start, end));
        statsMapper.upsertDaily(day, "tickets", ticketMapper.countBetween(start, end));
    }
}