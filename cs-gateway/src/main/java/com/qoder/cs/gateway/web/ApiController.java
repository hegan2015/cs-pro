package com.qoder.cs.gateway.web;

import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.chat.ChatService;
import com.qoder.cs.core.chat.SseHub;
import com.qoder.cs.core.ticket.RatingService;
import com.qoder.cs.core.ticket.TicketService;
import com.qoder.cs.facade.dto.MessageDTO;
import com.qoder.cs.facade.dto.RatingDTO;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.facade.dto.TicketDTO;
import com.qoder.cs.facade.request.RatingRequest;
import com.qoder.cs.facade.request.SessionCreateRequest;
import com.qoder.cs.facade.request.TicketCreateRequest;
import com.qoder.cs.kb.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户端 API：会话/消息/SSE/评分/工单/FAQ 检索。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final ChatService chat;
    private final SseHub sse;
    private final TicketService tickets;
    private final RatingService ratings;
    private final KbService kb;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of("status", "UP", "service", "cs-pro"));
    }

    // ---- 会话与消息 ----

    @PostMapping("/sessions")
    public Result<SessionDTO> createSession(@RequestBody SessionCreateRequest req) {
        return Result.ok(chat.createSession(req));
    }

    @GetMapping("/sessions/{id}")
    public Result<SessionDTO> getSession(@PathVariable String id) {
        return Result.ok(chat.getSession(id));
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<List<MessageDTO>> messages(@PathVariable String id,
                                             @RequestHeader(value = "X-Session-Token", required = false) String token) {
        chat.requireAccess(id, token);
        return Result.ok(chat.messages(id));
    }

    /** LLM 同步应答可能长达数十秒：走 MVC 异步（专用线程池），不占用 Tomcat 请求线程。 */
    @PostMapping("/sessions/{id}/messages")
    public java.util.concurrent.Callable<Result<MessageDTO>> sendMessage(
            @PathVariable String id, @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Token", required = false) String token) {
        return () -> {
            chat.requireAccess(id, token);
            return Result.ok(chat.sendMessage(id, body.get("content"), body.get("attachments")));
        };
    }

    @GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @RequestParam(value = "token", required = false) String token) {
        chat.requireAccess(id, token); // EventSource 无法设自定义头，凭证走 query
        chat.getSession(id);
        return sse.register(id);
    }

    @PostMapping("/sessions/{id}/end")
    public Result<SessionDTO> endSession(@PathVariable String id,
                                         @RequestHeader(value = "X-Session-Token", required = false) String token) {
        chat.requireAccess(id, token);
        return Result.ok(chat.endSession(id));
    }

    /** 用户请求转人工：接管后消息排队，由客服经工作台回复。 */
    @PostMapping("/sessions/{id}/human")
    public Result<SessionDTO> requestHuman(@PathVariable String id,
                                           @RequestHeader(value = "X-Session-Token", required = false) String token) {
        chat.requireAccess(id, token);
        return Result.ok(chat.requestHuman(id));
    }

    // ---- 评分 ----

    @PostMapping("/ratings")
    public Result<RatingDTO> rate(@RequestBody RatingRequest req) {
        return Result.ok(ratings.rate(req));
    }

    // ---- 工单（全员免费） ----

    @PostMapping("/tickets")
    public Result<TicketDTO> createTicket(@RequestBody TicketCreateRequest req,
                                          @RequestParam(required = false) String userId) {
        String uid = userId;
        if (req.getSessionId() != null && !req.getSessionId().isBlank()) {
            uid = chat.getSession(req.getSessionId()).getUserId();
        }
        if (uid == null || uid.isBlank()) {
            throw com.qoder.cs.common.exception.BizException.param("userId 或 sessionId 必填");
        }
        return Result.ok(tickets.create(req, uid, "USER"));
    }

    @GetMapping("/tickets")
    public Result<Map<String, Object>> listTickets(@RequestParam(required = false) String userId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", tickets.count(userId, status, category));
        out.put("list", tickets.query(userId, status, category, page, Math.min(size, 50)));
        return Result.ok(out);
    }

    @GetMapping("/tickets/{id}")
    public Result<Map<String, Object>> ticketDetail(@PathVariable String id) {
        return Result.ok(tickets.detail(id));
    }

    @PostMapping("/tickets/{id}/cancel")
    public Result<TicketDTO> cancelTicket(@PathVariable String id, @RequestParam String userId) {
        return Result.ok(tickets.cancel(id, userId));
    }

    // ---- FAQ 检索（免费） ----

    @GetMapping("/kb/search")
    public Result<List<Map<String, Object>>> kbSearch(@RequestParam String q,
                                                      @RequestParam(defaultValue = "3") int limit) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (KbService.Hit h : kb.search(q, Math.min(limit, 10))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entry", h.entry());
            row.put("score", Math.round(h.score() * 100) / 100.0);
            out.add(row);
        }
        return Result.ok(out);
    }

    @GetMapping("/kb/hot")
    public Result<List<com.qoder.cs.facade.dto.KbEntryDTO>> kbHot(@RequestParam(defaultValue = "5") int limit) {
        return Result.ok(kb.hot(Math.min(limit, 20)));
    }
}