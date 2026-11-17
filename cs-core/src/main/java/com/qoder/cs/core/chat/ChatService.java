package com.qoder.cs.core.chat;

import com.qoder.cs.common.exception.BizException;
import com.qoder.cs.core.biz.ToolService;
import com.qoder.cs.core.config.CsProperties;
import com.qoder.cs.core.subscription.SubscriptionService;
import com.qoder.cs.facade.dto.LlmCallDTO;
import com.qoder.cs.facade.dto.MessageDTO;
import com.qoder.cs.facade.dto.SessionDTO;
import com.qoder.cs.facade.dto.TenantDTO;
import com.qoder.cs.facade.request.SessionCreateRequest;
import com.qoder.cs.kb.KbService;
import com.qoder.cs.llm.LlmClient;
import com.qoder.cs.llm.LlmException;
import com.qoder.cs.llm.LlmModel.ChatMessage;
import com.qoder.cs.llm.LlmModel.ChatResult;
import com.qoder.cs.llm.LlmModel.ToolCall;
import com.qoder.cs.llm.LlmModel.ToolSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话编排：订阅门槛（服务端强制）→ FAQ 直答 / LLM+工具循环 → SSE 推送。
 * 未订阅：仅 FAQ，每日免费额度 CAS 扣减；已订阅：LLM + 业务工具不限量。
 * LLM 不可用（未配 Key/熔断/调用失败）时降级 FAQ，对话不中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Set<String> ROLES = Set.of("PASSENGER", "DRIVER", "MERCHANT");

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final LlmCallMapper llmCallMapper;
    private final KbService kb;
    private final LlmClient llm;
    private final ToolService tools;
    private final SubscriptionService subscriptions;
    private final SseHub sse;
    private final CsProperties props;

    public SessionDTO createSession(SessionCreateRequest req) {
        if (req.getUserRole() == null || !ROLES.contains(req.getUserRole())) {
            throw BizException.param("userRole 仅 PASSENGER/DRIVER/MERCHANT");
        }
        if (req.getUserId() == null || req.getUserId().isBlank()) {
            throw BizException.param("userId 必填");
        }
        String tenantCode = req.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            tenantCode = "MERCHANT".equals(req.getUserRole()) ? "payment-pro" : "ride-pro";
        }
        subscriptions.requireTenant(tenantCode);
        SessionDTO s = new SessionDTO();
        s.setId("S" + sessionMapper.nextId());
        s.setUserRole(req.getUserRole());
        s.setUserId(req.getUserId().trim());
        s.setUserName(req.getUserName() == null || req.getUserName().isBlank()
                ? req.getUserId().trim() : req.getUserName().trim());
        s.setTenantCode(tenantCode);
        s.setCreatedAt(System.currentTimeMillis());
        sessionMapper.insert(s);
        log.info("会话创建 id={} role={} user={} tenant={}", s.getId(), s.getUserRole(), s.getUserId(), tenantCode);
        return sessionMapper.selectById(s.getId());
    }

    public SessionDTO getSession(String id) {
        SessionDTO s = sessionMapper.selectById(id);
        if (s == null) {
            throw BizException.notFound("会话不存在: " + id);
        }
        return s;
    }

    public List<MessageDTO> messages(String sessionId) {
        getSession(sessionId);
        return messageMapper.selectBySession(sessionId);
    }

    public SessionDTO endSession(String id) {
        SessionDTO s = getSession(id);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw BizException.conflict("会话已结束");
        }
        if (sessionMapper.end(id, System.currentTimeMillis(), s.getIntent()) == 0) {
            throw BizException.conflict("会话已结束");
        }
        return sessionMapper.selectById(id);
    }

    public List<SessionDTO> querySessions(String status, Long from, Long to, int page, int size) {
        return sessionMapper.query(status, from, to, (page - 1) * size, size);
    }

    public long countSessions(String status, Long from, Long to) {
        return sessionMapper.count(status, from, to);
    }

    /** 发送用户消息，同步返回机器人回复；SSE 同步推送 token/tool/message/done。 */
    public MessageDTO sendMessage(String sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw BizException.param("消息内容必填");
        }
        if (content.length() > 2000) {
            throw BizException.param("消息过长（上限 2000 字）");
        }
        SessionDTO session = getSession(sessionId);
        if (!"ACTIVE".equals(session.getStatus())) {
            throw BizException.conflict("会话已结束，请新建会话");
        }
        TenantDTO tenant = subscriptions.requireTenant(session.getTenantCode());
        boolean subscribed = subscriptions.isSubscribed(tenant.getId());
        if (!subscribed && !subscriptions.tryConsumeFree(session.getUserId())) {
            throw BizException.conflict("今日免费 FAQ 额度已用完（" + props.getFree().getDailyQuota()
                    + " 条/天），订阅后可无限使用智能助手与业务查询");
        }

        long start = System.currentTimeMillis();
        MessageDTO userMsg = newMessage(sessionId, "USER");
        userMsg.setContent(content.trim());
        messageMapper.insert(userMsg);
        sessionMapper.updateIntent(sessionId, classifyIntent(content));

        MessageDTO bot = subscribed
                ? llmAnswer(session, content, start)
                : faqAnswer(sessionId, content, start, false);
        messageMapper.insert(bot);
        sse.send(sessionId, "message", bot);
        sse.send(sessionId, "done", Map.of("messageId", bot.getId()));
        return bot;
    }

    // ---- LLM + 工具循环（已订阅） ----

    private MessageDTO llmAnswer(SessionDTO session, String content, long start) {
        List<KbService.Hit> hits = kb.search(content, props.getKb().getRagTopK());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(session, hits)));
        List<MessageDTO> history = new ArrayList<>(
                messageMapper.recentDialog(session.getId(), props.getChat().getHistoryRounds() * 2 + 1));
        Collections.reverse(history);
        for (MessageDTO m : history) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            messages.add("USER".equals(m.getRole()) ? ChatMessage.user(m.getContent())
                    : ChatMessage.assistant(m.getContent()));
        }
        List<ToolSpec> specs = tools.specsFor(session);
        long tokens = 0;
        String draft = null;
        try {
            for (int round = 0; round <= props.getChat().getMaxToolRounds(); round++) {
                boolean lastRound = round == props.getChat().getMaxToolRounds();
                ChatResult result = callLlm(session.getId(), messages, lastRound ? null : specs);
                tokens += result.getPromptTokens() + result.getCompletionTokens();
                if (!result.hasToolCalls()) {
                    return botMessage(session.getId(), content(result), joinRefs(hits), draft, start, tokens);
                }
                messages.add(ChatMessage.assistantToolCalls(result.getToolCalls()));
                for (ToolCall call : result.getToolCalls()) {
                    String toolResult = tools.execute(session, call.getName(), call.getArgumentsJson());
                    log.info("LLM tool session={} name={} ok={}", session.getId(), call.getName(),
                            !toolResult.startsWith("{\"error\""));
                    messages.add(ChatMessage.tool(call.getId(), toolResult));
                    if ("create_ticket".equals(call.getName()) && toolResult.contains("\"draft\":true")) {
                        draft = toolResult;
                    }
                    sse.send(session.getId(), "tool", Map.of("name", call.getName(),
                            "ok", !toolResult.startsWith("{\"error\"")));
                }
            }
            return botMessage(session.getId(), "问题较复杂，建议提交工单由人工跟进。", joinRefs(hits), draft, start, tokens);
        } catch (LlmException e) {
            log.warn("LLM 降级 FAQ session={} err={}", session.getId(), e.getMessage());
            MessageDTO bot = faqAnswer(session.getId(), content, start, true);
            bot.setTokens(tokens);
            return bot;
        }
    }

    private ChatResult callLlm(String sessionId, List<ChatMessage> messages, List<ToolSpec> specs) {
        long t0 = System.currentTimeMillis();
        LlmCallDTO call = new LlmCallDTO();
        call.setId("L" + llmCallMapper.nextId());
        call.setSessionId(sessionId);
        call.setModel(llm.model());
        call.setCreatedAt(t0);
        try {
            ChatResult r = llm.chat(messages, specs,
                    piece -> sse.send(sessionId, "token", Map.of("text", piece)));
            call.setStatus("OK");
            call.setPromptTokens(r.getPromptTokens());
            call.setCompletionTokens(r.getCompletionTokens());
            call.setLatencyMs(System.currentTimeMillis() - t0);
            llmCallMapper.insert(call);
            log.info("LLM OK session={} model={} tokens={} latencyMs={} toolCalls={}",
                    sessionId, call.getModel(), r.getPromptTokens() + r.getCompletionTokens(),
                    call.getLatencyMs(), r.hasToolCalls() ? r.getToolCalls().size() : 0);
            return r;
        } catch (LlmException e) {
            call.setStatus("ERROR");
            call.setError(e.getMessage() == null ? "unknown"
                    : e.getMessage().substring(0, Math.min(e.getMessage().length(), 250)));
            call.setLatencyMs(System.currentTimeMillis() - t0);
            llmCallMapper.insert(call);
            throw e;
        }
    }

    private String systemPrompt(SessionDTO session, List<KbService.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是出行与支付平台的智能客服，用简体中文回答，语气友好、结论准确、尽量简短。\n");
        sb.append("当前用户：身份=").append(session.getUserRole())
                .append("，userId=").append(session.getUserId())
                .append("，昵称=").append(session.getUserName())
                .append("，所属租户=").append(session.getTenantCode()).append("。\n");
        sb.append("规则：\n");
        sb.append("1. 金额字段单位为分，向用户展示时换算为元（如 1250 分 = 12.50 元）。\n");
        sb.append("2. 只能使用提供的工具查询当前用户自己的数据，不得编造订单/余额/流水；工具返回 error 时如实告知。\n");
        sb.append("3. 涉及退款、改价、账号处理等写操作一律不执行，引导用户提交工单。\n");
        sb.append("4. 无法解决或用户要求人工时，调用 create_ticket 生成工单草稿，并告知用户需确认后才会创建。\n");
        sb.append("5. 优先依据知识库参考回答；知识库与工具结果冲突时以工具实时结果为准。\n");
        if (!hits.isEmpty()) {
            sb.append("\n知识库参考：\n");
            for (KbService.Hit h : hits) {
                sb.append("- Q: ").append(h.entry().getQuestion())
                        .append("\n  A: ").append(h.entry().getAnswer()).append('\n');
            }
        }
        return sb.toString();
    }

    // ---- FAQ 直答（未订阅 / LLM 降级） ----

    private MessageDTO faqAnswer(String sessionId, String content, long start, boolean degraded) {
        List<KbService.Hit> hits = kb.search(content, props.getKb().getRagTopK());
        String answer;
        String refs = null;
        if (!hits.isEmpty() && hits.get(0).score() >= props.getKb().getDirectThreshold()) {
            KbService.Hit best = hits.get(0);
            answer = best.entry().getAnswer();
            refs = best.entry().getId();
            kb.recordHit(best.entry().getId());
        } else {
            StringBuilder sb = new StringBuilder("抱歉，知识库暂未找到匹配答案。您可以换个说法再问");
            if (!degraded) {
                sb.append("，或订阅后使用智能助手（可查询订单/钱包/流水）");
            }
            sb.append("。");
            List<com.qoder.cs.facade.dto.KbEntryDTO> hot = kb.hot(3);
            if (!hot.isEmpty()) {
                sb.append("\n热门问题：");
                for (int i = 0; i < hot.size(); i++) {
                    sb.append('\n').append(i + 1).append(". ").append(hot.get(i).getQuestion());
                }
            }
            answer = sb.toString();
        }
        if (degraded) {
            answer = "（智能助手暂不可用，已为您检索知识库）\n" + answer;
        }
        return botMessage(sessionId, answer, refs, null, start, 0);
    }

    // ---- 辅助 ----

    private MessageDTO botMessage(String sessionId, String content, String kbRefs, String draft,
                                  long start, long tokens) {
        MessageDTO bot = newMessage(sessionId, "BOT");
        bot.setContent(content);
        bot.setKbRefs(kbRefs);
        bot.setTicketDraft(draft);
        bot.setLatencyMs(System.currentTimeMillis() - start);
        bot.setTokens(tokens);
        return bot;
    }

    private MessageDTO newMessage(String sessionId, String role) {
        MessageDTO m = new MessageDTO();
        m.setId("M" + messageMapper.nextId());
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setCreatedAt(System.currentTimeMillis());
        return m;
    }

    private static String content(ChatResult r) {
        return r.getContent() == null || r.getContent().isBlank() ? "（无回复内容）" : r.getContent();
    }

    private static String joinRefs(List<KbService.Hit> hits) {
        if (hits.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (KbService.Hit h : hits) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(h.entry().getId());
        }
        return sb.toString();
    }

    public static String classifyIntent(String text) {
        if (text.contains("计价") || text.contains("价格") || text.contains("费用") || text.contains("多少钱")) {
            return "计价";
        }
        if (text.contains("退款") || text.contains("退钱")) {
            return "退款";
        }
        if (text.contains("提现")) {
            return "提现";
        }
        if (text.contains("支付") || text.contains("扣款") || text.contains("付款")) {
            return "支付";
        }
        if (text.contains("账号") || text.contains("登录") || text.contains("注册")) {
            return "账号";
        }
        if (text.contains("投诉") || text.contains("态度") || text.contains("差评")) {
            return "投诉";
        }
        return "其他";
    }
}