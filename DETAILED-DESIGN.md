# cs-pro 智能客服系统详细设计

版本 v1.2.0 ｜ 2026-11-20

> 文档定位：本文是**类/方法级实现设计**（模块类清单、关键算法、状态机、配置键、错误码、调度、测试映射）。字段级数据字典见 [DESIGN.md](DESIGN.md) §4，接口契约见 DESIGN §6，安全要点见 DESIGN §8——本文不重复字段表，只写"怎么实现"。需求基线见 [REQUIREMENTS.md](REQUIREMENTS.md)，架构视图见 [ARCHITECTURE.md](ARCHITECTURE.md)。
>
> 引用约定：以 `类.方法` 为锚（行号随版本漂移，不写死）；常量/默认值均为当前代码实测。

## 1. 模块类总览

| 模块 | 类 | 职责 |
|---|---|---|
| cs-common | Result / ResultCode / BizException / MoneyUtil / IdGenerator | 统一返回体 / 三段式错误码 / 业务异常 / 分单位金额 / 跨方言取号 |
| cs-facade | 16 DTO + 5 Request | 纯契约（Session/Message/Ticket/TicketNote/Rating/KbEntry/KbRevision/LlmCall/Tenant/Subscription/ActivationCode/SubOrder/OpenKey/Invoice/AdminAudit/ProactiveEvent；SessionCreate/TicketCreate/OpenTicketCreate/KbUpsert/Rating） |
| cs-kb | KbService / Bm25Index / KbMapper / KbRevisionMapper / vector.VectorIndex | FAQ 领域服务 / BM25 倒排 / 持久化 / 版本快照 / 向量旁路 |
| cs-llm | LlmClient / LlmProperties / LlmException / LlmModel | OpenAI 兼容客户端 / 配置 / 异常标志位 / 协议模型 |
| cs-core | 见 §6 分域 | 编排与领域服务 |
| cs-gateway | CsGatewayApplication / ApiController / SubscriptionController / AdminController / OpenApiController / UploadController / TraceWebFilter / AdminAuthFilter / OpenKeyAuthFilter / GlobalExceptionHandler / CsAsyncConfig | 唯一运行进程 |

## 2. cs-common

- **Result/ResultCode**：成功 body 即 data；失败 `{code,message}`。码表见 §10。
- **BizException**：静态工厂 `param/unauthorized/forbidden/notFound/conflict/rateLimited/biz/downstream`，携带 ResultCode；GlobalExceptionHandler 统一映射 HTTP。
- **MoneyUtil**：全系统金额单位**分**（long），展示层换算元；system prompt 规则 1 明确要求 LLM 换算。
- **IdGenerator**：跨方言取号器。H2 走原生 `NEXT VALUE FOR` SEQUENCE；MySQL 走号表 `cs_sequence` + `LAST_INSERT_ID(next_val+1)` 原子取号（同一连接内 UPDATE→SELECT，**多实例安全**）。`next(seq)` 按业务序列名取号。

## 3. cs-facade

纯 DTO/Request，无逻辑、无 Spring 注解依赖，进程内引用；预留拆进程边界（ARCHITECTURE §10）。MessageDTO 含 content/role/refs/trace/draft/attachments/tokens/elapsedMs；SessionDTO 含 accessToken（仅建会话返回一次，详情/列表剥离）。

## 4. cs-kb

### 4.1 Bm25Index（进程内倒排）

- `rebuild(Map<String,String> documents)`：KB 变更即全量重建（synchronized 由 KbService 持）；文档文本 = `docText(question, keywords, answer)` 静态拼接。
- 中文按字/词 n-gram 切分建倒排；**12 组同义词**查询侧扩词（词频取 max）。
- `search(query, limit)`：BM25 打分返回 `Scored` 列表。已知特性：同分平局序跨 JVM 可能不一致（曾致向量效果误判，验证时须固定断言口径）。

### 4.2 KbService（领域服务）

- **检索** `search(query, limit)`：缓存 key=`query|limit`，TTL `cs.kb.cache-ttl-ms`（默认 30000ms，@Value 注入），KB 变更即失效；未命中按 `max(limit×3,10)` 超采样 → BM25 榜 + 向量榜 → `VectorIndex.rrfFuse` 融合取 Top limit；向量空/降级→纯 BM25 旧路。返回 `Hit(entry, score)` record。
- **CRUD**：create/update/delete/seedIfAbsent 均 synchronized；**update 前旧版快照入 cs_kb_revision**（版本化）；`rollback(kbId, revisionId)` 一键回滚（同样先快照当前版）；`listVersions` 版本列表。
- **运营统计**：`recordHit`（检索命中计数）、`recordCited(hits)`（回答引用计数 cited_count+1）、`citationStats()`（cited_count/cited_resolved_count 引用解决率）。
- **向量运营**：`reindexVectors()` 全量重建（失败报业务错不崩）、`vectorStatus()` 状态卡片数据。
- **直答阈值**：`cs.kb.direct-threshold`=1.0（BM25）、`cs.kb.vector-direct-threshold`=0.65（cosine，int8 实测校准）；判定在 ChatService.faqAnswer。

### 4.3 vector.VectorIndex（旁路增强）

- `enabled()`：CS_VECTOR_ENABLED 开关；`search(query, limit)`：embedding 容器（8100）转向量 → Qdrant（6333）cosine 近邻，返回 `VectorHit(kbId, score)`。
- **降级**：`downUntil` volatile 时间戳，任一容器异常 `trip(op, e)` 置 30s 熔断 + WARN；期间 search/syncEntry/removeEntry 直接短路；`status()` 探针 10s 缓存，返回 `Status(enabled, embeddingUp, qdrantUp, …)`。
- **同步**：`syncEntry(e)` KB 增改即 upsert（禁用即 `removeEntry`）；point id = kbId 的 name-based UUID；`reindex(entries)` 全量重建并清零 downUntil；`autoBackfill()` 启动时点数≠启用条目数自动后台补建。
- **融合**：`rrfFuse(a, b, k, limit)` 静态纯函数——每条得分 Σ `1/(k+名次)`，k=`cs.vector.rrf-k`（默认 60）；只看名次不看原始分，解决 BM25 与 cosine 量纲不可比。

## 5. cs-llm

### 5.1 LlmModel（协议模型）

ChatMessage（role/content/contentParts 多模态/toolCalls/toolCallId，`hasImages()` 判附图）、ChatResult（content/toolCalls/finishReason/model/promptTokens/completionTokens，`hasToolCalls()`）、ToolCall（id/name/argumentsJson）、ToolSpec（name/description/parameters）。

### 5.2 LlmException（标志位异常）

`isStreamed()`（已出流，禁重试禁切备）、`isCircuit()`（熔断拒绝）、`getPartialContent()`（断流已生成文本）、`getHttpStatus()`（重试分类用）；链式 `markStreamed/markCircuit/withPartialContent/withHttpStatus`。

### 5.3 LlmClient（核心客户端）

- **端点抽象**：`Endpoint(baseUrl, model, apiKey)` record，`key()=baseUrl去尾斜杠|model`；`primary()/backup()` 从 LlmProperties 组装（backup 缺省 model/key 回落主值）。
- **路由链** `chat(messages, tools, onToken, maxTokens)`：主端点 attempt 失败 → 仅当 `backup!=null && !e.isStreamed()` 切备用；均败抛 last 由调用方降级 FAQ。
- **单端点弹性** `attempt(ep,…)`：
  1. 熔断检查：`breakers` ConcurrentHashMap 按 ep.key() 分键；openUntil 未到→直接抛 markCircuit（不发网络请求）；half-open 无专门探测器，由到期后下一次真实调用承担；
  2. `emitted` AtomicBoolean 包裹 onToken：一旦推送过片段，失败即 markStreamed；
  3. 重试循环 maxAttempts=retryMax+1（默认 3 次），退避 `retryBackoffMs×2^(attempt-1)`（1s/2s）；
  4. `retryable(e)`：已出流→否；HTTP 429/5xx→是；4xx→否；无状态码但有 cause（IOException/Timeout）→是；
  5. 成功 `fails.set(0)`；耗尽 `recordFailure`：连续失败 ≥circuitFailureThreshold(5) → openUntil=now+circuitOpenMs(60s)。
- **请求构造** `doChat`：含图片消息且配 visionModel→本次换视觉模型（同端点覆盖 model）；body=model/messages/temperature/max_tokens/tools/stream(+stream_options.include_usage)；JDK HttpClient connectTimeout 5s、请求 timeout=timeoutMs(60s)、Bearer 鉴权。
- **流式解析**：`BodyHandlers.ofLines()` 逐行；只认 `data:` 前缀，`[DONE]` 终止；每 chunk 取 choices[0].delta：content 非空→StringBuilder 累积**并立即 onToken.accept**（边生成边推）；finish_reason/usage 随 chunk 更新；`mergeStreamToolCalls` 按 index 定位同一 ToolCall，id 覆盖、name/arguments **字符串累加**拼完整 JSON。
- **断流**：读流异常→`markStreamed().withPartialContent(已生成文本)` 上抛，调用方保留半成品（ChatService ABORTED 分支）。
- **可观测**：`available()/configured()/model()/breakerStates()`（fails/open/openUntil 快照供 metrics）。

### 5.4 LlmProperties 配置键（llm.*，仓库外 ~/.config/cs-pro/llm.properties）

| 键 | 默认 | 说明 |
|---|---|---|
| base-url / api-key / model | https://api.openai.com/v1 / 空 / gpt-4o-mini | 主端点；实际接 DeepSeek（deepseek-flash） |
| base-url-backup / model-backup / api-key-backup | 空 | 备用端点（缺省回落主 model/key） |
| vision-model | 空 | 附图消息路由 |
| temperature | 0.3 | |
| max-tokens / max-tokens-query / max-tokens-report | 1024 / 512 / 2048 | 分级：工具轮与查询类小额度，投诉/其他类大额度 |
| timeout-ms | 60000 | 单请求超时 |
| retry-max / retry-backoff-ms | 2 / 1000 | 重试次数与退避基数 |
| circuit-failure-threshold / circuit-open-ms | 5 / 60000 | 熔断阈值与时长 |
| prompt-version | v1.2.0 | 随审计落库 |
| mask-pii | true | 输出侧打码开关 |
| daily-token-budget-per-tenant | 0（不限） | 外部配置覆盖为 100 万 |

`configured()`=apiKey 非空；无 Key→ChatService 走 FAQ 降级模式。

## 6. cs-core 分域详细设计

### 6.1 chat 域

**ChatService**（编排核心，方法 map）：

| 方法 | 职责与关键逻辑 |
|---|---|
| sendMessage | 四道闸：参数校验（content≤2000 字、图片≤3 张）→会话 ACTIVE→租户准入+订阅实时判定→并发信号量（subscribed 且非接管时 tryAcquire，超限 429 不入库）；USER 先落库；接管中→排队返回 null；未订阅→tryConsumeFree 扣免费额度（超额 A0409）→faqAnswer；已订阅→llmAnswer；BOT 落库→broadcast message/done；finally 释放信号量 |
| llmAnswer | 日预算护栏（sumTokensTodayByTenant + mq.pendingTokens ≥ budget → BUDGET 审计 + faqAnswer 前缀降级）→ kb.search(ragQuery, ragTopK) → systemPrompt + appendHistoryWithBudget → 分级 maxTokens（classifyIntent 投诉/其他→report）→ 工具循环 for round 0..maxToolRounds(3)：lastRound 不带 specs；无 tool_calls→收尾五道（deriveIntent/recordCited/maskOutput/length 标注/botMessage）；有→assistantToolCalls 注回 + runTools + tool 结果注回 + trace 累积 + SSE tool；超轮→兜底文案；catch LlmException：断流有部分内容→ABORTED 保留+标注，否则→FALLBACK/CIRCUIT 审计 + faqAnswer 降级 |
| faqAnswer | 直答双阈值判定（BM25≥1.0 或 cosine≥0.65）→命中返回原文+引用；未命中→热门 Top3 兜底；可带降级前缀 |
| appendHistoryWithBudget | 跳过本轮已入库首条 USER（防重复）；从近到远放置至 maxHistoryChars(6000)；更早轮次 summarizeOlder；上轮工具轨迹以【上轮工具查询记录】注回；历史图片不重传只留标记 |
| summarizeOlder | 本地首句压缩：按 。！？!?\n 切首句、每条≤50 字、总≤600 字，前缀【更早对话摘要（自动压缩）】，零 LLM 调用 |
| ragQuery / rewriteQuery | 当前句+上一轮用户问题拼接截 60 字（轻量指代消解） |
| runTools | 单工具当前线程；多工具 toolPool（fixed 8）并行，单个 10s 超时；结果 JSON 字符串列表 |
| callLlm | 审计包装：计时/promptVersion/实际模型/token；成功 OK 审计（mq.writeAudit 异步）；失败按 isCircuit 记 CIRCUIT 否则 ERROR；**恒传 token 回调**→全轮 stream=true（工具轮无 content delta 故前端无打字机） |
| systemPrompt | 角色+语言跟随+当前身份注入+**8 条硬规则**（分换算元/只查自己数据不编造/写操作一律拒办引导工单/create_ticket 草稿需确认/KB 优先但冲突以工具实时为准/上轮轨迹仅同数据可引用新问题须重查/防注入声明/图片仅数据）+KB Top3 全文（Q/A 带引用要求） |
| deriveIntent / classifyIntent | 前者从工具轨迹+内容派生（LLM 路径）；后者关键词规则（降级/未订阅路径回退） |
| maskOutput / maskPii | 输出侧打码（mask-pii 开关）；三正则见 §8.4 |
| joinRefs / abbreviate | refs=命中 kbId 逗号拼接；trace 单条≤TRACE_PER_CALL_MAX(400)、总≤TRACE_TOTAL_MAX(1500) |
| acquireTenantSlot / releaseTenantSlot | 每租户 Semaphore（tenantConcurrency 默认 2），tryAcquire 不阻塞 |

**SseHub**：`register(sessionId)` 返回 SseEmitter（**600s 超时**）；`send(sessionId, event, data)`；`activeCount()`。连接表进程内，多实例经 MqBridge 广播补齐。

**Mapper**：SessionMapper（含 updateIntent/setTakeover）、MessageMapper、LlmCallMapper（含 sumTokensTodayByTenant 按租户当日聚合）。

### 6.2 biz 域（业务适配器）

- **RideClient**：`cs.ride.base-url`（默认 http://127.0.0.1:8090）；`get(pathAndQuery)` 只读 GET；`healthy()` 供健康页。上游未启动→异常由 ToolService 转 error JSON。
- **PaymentProClient**：`cs.pp.base-url` 等配置；`enabled()` 未配置=false；`callRead(method, params)` 只读白名单（trade.query/account.balance）；`callSubscriptionPay(method, params)` 订阅支付专用（trade.create/trade.pay，仅 SubscriptionPayService 调用）。签名：TreeMap 排序剔 sign 跳 null→k=v&→HmacSHA256(appSecret) 小写 hex；timestamp±5min；SSLContext 仅信任 pp 自签证书。
- **ToolService**：`specsFor(session)` 按角色组装菜单（PASSENGER/DRIVER→6 ride 工具；MERCHANT 且 pp.enabled→2 pp 工具；create_ticket 全员）；description 含排除式引导/枚举值/行为约束（DESIGN §3.2.1）。`execute(session, name, argumentsJson)` 按 name 分发：**身份参数服务端覆盖注入**、orderId 归属校验（越权 A0404）、结果 `mask()` 递归脱敏（phone 类字段前 3 后 4）后序列化；失败返回 `{"error":…}` 不抛异常（对话不中断）；未注册 name→error JSON。
- **UploadService**：MAX_BYTES=5MB、MAX_PER_MESSAGE=3；validateList 校验相对路径白名单（/uploads/）；图片转 base64 data URL 进多模态 parts（本地路径供应商不可达）。

### 6.3 ticket 域

- **TicketService**：`create(req, userId, source)` request_id 唯一键幂等（LLM 确定性键 TICKET-{sessionId}-{seq}）；状态机 OPEN→PROCESSING（assign）→RESOLVED（resolve）→CLOSED（close/自动）；OPEN/PROCESSING→CANCELLED（cancel 仅本人）；**全部流转 CAS**（WHERE status=旧态）；`autoClose()` @Scheduled 60s：RESOLVED 超 7 天→CLOSED；`addNote` 备注时间线；`detail` 工单+备注；query/count 双签名（用户端 userId 强制注入 / 管理端 status/category/source/q 筛选分页）。
- **RatingService**：满意度 1–5 星，PK 防重（会话/工单维度）。
- 开放收单（OpenApiController→TicketService.create，source 缺省 ride-pro）：requestId 幂等返回同一工单；category 七类枚举；attachments≤3 张 /uploads/ 相对路径。

### 6.4 subscription 域

- **SubscriptionService**：`ensureBuiltinTenants/createTenantIfAbsent`（种子 ride-pro/payment-pro）；`requireTenant`（冻结拒）；`isSubscribed`（**expire_at > now 实时判定**）；`status`（订阅+今日免费额度）；`extend(tenantId, days, source, planCode)` 续订叠加 `newExpire=max(now,expire_at)+days`；`generateCodes(days, count, planCode)` 批量 CS-XXXX-XXXX；`redeem(tenantCode, code)` 兑换单事务 CAS 一码一用；`grant` 手工开通 source=ADMIN；`tryConsumeFree(userId)` cs_free_usage MERGE+CAS 原子自增（并发不超发，dailyQuota 默认 5）；`freeUsed`；`syncExpiredDisplay` 过期展示同步；queryTenants/queryCodes/querySubscriptions 真分页。
- **SubscriptionPayService**：订阅单状态机 PENDING→PAID→ACTIVATED（终态）/FAILED/CLOSED；金额服务端固定（套餐 price）；`@Scheduled(fixedDelay=5000)` 回查 trade.query 兜底收敛终态；开通 CAS+request_id 唯一键不重复加天数；调用 PaymentProClient.callSubscriptionPay。
- **套餐配置** CsProperties.plans（Map<String,PlanSpec>）：PlanSpec{name, monthlyPrice, tokenPricePerK, dailyTokenBudget, concurrency}；默认 free/standard(¥99/30天,10分/千,30万,2)/pro(¥299,8分/千,200万,8)。

### 6.5 open / billing / audit 域

- **OpenKeyService**：`generate(tenantCode)` 生成凭证；`revoke(apiKey)` 吊销即失效；`resolve(apiKey)`→TenantDTO（服务端解析，冻结拒）；`query` 分页。OpenKeyAuthFilter 据此 401/403。
- **InvoiceService**：`generate(tenantCode, period)` 按账期聚合当月 OK 调用 token×套餐单价（**向上取整**），(tenant,period) 唯一键 upsert 重算幂等；非法账期报业务错；`list/query`。
- **AdminAuditService**：管理端全变更操作留痕 cs_admin_audit；**旁路写入不阻断主流程**（失败仅日志）。

### 6.6 mq 域

**MqBridge**（RocketMQ 可选集成）：topics `cs_sse_event`（默认）/`cs_llm_audit`；`start/stop` 生命周期；`active()`；`broadcast(sessionId, event, data)` 本地 SseHub.send + MQ 广播（其他实例消费后本地 send）；`writeAudit(call, tenantCode)` 异步落库（失败降级直写）；`pendingTokens(tenantCode)` 在途未落库 token 计数（供日预算护栏，防异步窗口超发）。MQ 不可用→全部直写/本地，主链路不受影响。

### 6.7 proactive / stats / seed / config / support

- **ProactiveService**：`@Scheduled(fixedDelayString="${cs.proactive.interval-ms:60000}", initialDelay=20s)`；轮询 ride 超时订单/pp 失败支付单（只读，lookbackHours 24、maxPerPoll 10）；event_key 唯一键幂等推送——已有会话复用、无会话自动建；上游未跑自动跳过；enabled 开关。
- **StatsService**：SQL 聚合（会话/消息量、解决率、转工单率与分类分布、响应时长、满意度、高频 TopN、LLM 用量）；`@Scheduled(fixedDelay=600s, initialDelay=30s)` stats_daily 快照。
- **MetricsService**：`render()` Prometheus 文本格式（/api/admin/metrics）。
- **HealthService**：`detail()` 组件巡检（DB/MQ/LLM/上游/主动服务/向量），health.html 5s 轮询。
- **SeedService**：内置租户 + FAQ 种子（从两系统 README 业务规则提炼；K31 永久保留）。
- **CsProperties**（cs.*）：见 §9 配置总表。**IdConfig**：按 profile 装配 IdGenerator（mysql 标志）。
- **IdempotentMapper**：idempotent_record 通用幂等记录。

## 7. cs-gateway

- **ApiController**（用户端 /api/*）：会话 CRUD/消息/SSE 流（?token=）/评价/工单/KB 检索/上传；会话端点 X-Session-Token 校验（老会话兼容放行）。
- **SubscriptionController**：订阅状态/套餐/激活码兑换/支付开通。
- **AdminController**（/api/admin/*）：8 页签全量运营端点，真分页 {total,list}+page/size；KB reindex/vector-status；metrics/health。
- **OpenApiController**（/api/open/*）：usage/sessions/invoices/tickets（收单+回查）。
- **UploadController**：POST /api/uploads（≤5MB）。
- **过滤器链**：TraceWebFilter（traceId 注入 MDC/响应头）→ AdminAuthFilter（`cs.admin.token` 逗号分隔多令牌 Set，轮换期新旧并存）/ OpenKeyAuthFilter（resolve→401/403）。
- **GlobalExceptionHandler**：BizException→HTTP 映射（§10）；TaskRejectedException/AsyncRequestTimeoutException→**503 系统繁忙**（LLM 池满快速失败不拖垮容器）；兜底 Exception→500。
- **CsAsyncConfig**：llmRequestExecutor（core8/max32/AbortPolicy）+ MVC 异步支持；LLM 调用与 Tomcat 线程隔离。
- **静态托管**：五页面零构建 H5（chat/agent/admin/health/subscription）+ common.css/common.js；no-cache 头。

## 8. 关键算法与常量

### 8.1 工具循环（ChatService.llmAnswer）

```
for round in 0..maxToolRounds(3):          # 最多 4 次 LLM 调用
    specs = (round==3) ? null : tools.specsFor(session)   # 最后一轮剥夺工具
    maxTk = (round==3) ? answerMaxTokens : maxTokensQuery
    result = callLlm(messages, specs, maxTk)
    if !result.hasToolCalls(): return 收尾五道(result)
    messages += assistantToolCalls(result.toolCalls)
    results = runTools(session, result.toolCalls)         # 并行/10s/身份注入/归属校验/脱敏
    for each: messages += tool(callId, result); trace += name+args+=>abbrev(result,400)
              if create_ticket && draft:true → 存草稿（不落单）
              SSE tool {name, ok}
超轮 → "问题较复杂，建议提交工单由人工跟进。"
```

### 8.2 历史预算（appendHistoryWithBudget）

跳过本轮首条 USER → 从近到远累加至 6000 字符 → 溢出轮次 summarizeOlder（首句≤50 字/条、总≤600 字）→ 上轮工具轨迹注回 → 当前消息末尾单独 append（附图转 base64 parts）。

### 8.3 RRF 融合（VectorIndex.rrfFuse）

`score(doc) = Σ榜 1/(k + rank)`，k=60；两榜各取 max(limit×3,10)；按融合分降序取 Top limit。纯静态函数，单测直接断言名次叠加。

### 8.4 PII 打码（两道，口径以代码为准）

- **工具结果侧** ToolService.mask：递归 JSON，key 含 "phone" 且长度≥7 → 前 3+****+后 4。
- **LLM 输出侧** ChatService.maskPii（mask-pii 开关）三正则：
  - ID_CARD `\d{17}[\dXx]` → 前 3+11 星+后 4；
  - BANK_CARD `\d{16,19}` → **前 4+8 星+后 4**；
  - PHONE `1[3-9]\d{9}` → 前 3+****+后 4。
  - 均带 `(?<!\d)…(?!\d)` 边界防误伤长数字串。

> 注：旧文档"卡号前 6 后 4"表述与代码不符，已按实际口径修正（见 README/DESIGN 同步）。

### 8.5 常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| 消息长度上限 | 2000 字 | ChatService.sendMessage |
| 图片 | ≤5MB / ≤3 张 | UploadService |
| SSE 超时 | 600s | SseHub |
| 工具循环 | ≤3 轮（≤4 次调用） | cs.chat.max-tool-rounds |
| 历史预算 / 摘要 | 6000 字符 / ≤600 字 | cs.chat.max-history-chars / summarizeOlder |
| trace | 单条 400 / 总 1500 | ChatService |
| 免费额度 | 5 条/人/日 | cs.free.daily-quota |
| 租户并发 | 2（pro 8） | cs.chat.tenant-concurrency / plans |
| 直答阈值 | BM25 1.0 / cosine 0.65 | cs.kb.* |
| 检索缓存 | 30s TTL | cs.kb.cache-ttl-ms |
| 向量熔断 | 30s | VectorIndex.trip |
| LLM 重试/熔断 | 2 次退避 1s 起 / 5 败熔 60s | llm.* |
| 工单自动关闭 | RESOLVED 7 天 | TicketService.autoClose |
| 订阅回查 | 5s | SubscriptionPayService |

## 9. 配置键总表（cs.*）

| 键 | 默认 | 说明 |
|---|---|---|
| cs.admin.token | cs-admin | 逗号分隔多令牌轮换 |
| cs.db.mode | mysql | h2 切零依赖（CS_DB_MODE） |
| cs.mq.enabled | false | RocketMQ 开关（CS_MQ_ENABLED） |
| cs.mq.sse-topic / audit-topic | cs_sse_event / cs_llm_audit | |
| cs.vector.enabled | true | CS_VECTOR_ENABLED |
| cs.vector.rrf-k | 60 | RRF 常数 |
| cs.kb.cache-ttl-ms | 30000 | 检索缓存 |
| cs.kb.direct-threshold / vector-direct-threshold | 1.0 / 0.65 | 直答双阈值 |
| cs.kb.rag-top-k | 3 | 注入条数 |
| cs.chat.history-rounds / max-tool-rounds / max-history-chars / tenant-concurrency | 10 / 3 / 6000 / 2 | |
| cs.free.daily-quota | 5 | |
| cs.plans.* | free/standard/pro | PlanSpec 五字段 |
| cs.proactive.enabled / interval-ms / lookback-hours / max-per-poll | true / 60000 / 24 / 10 | |
| cs.ride.base-url | http://127.0.0.1:8090 | |
| cs.pp.base-url 等 | 空（未入驻） | integrate-payment.sh 写入 |

llm.* 见 §5.4。环境变量 CS_PORT/DATA_DIR/ADMIN_TOKEN 覆盖对应配置。

## 10. 错误码与异常映射

| ResultCode | 含义 | HTTP |
|---|---|---|
| A0400 PARAM_ERROR | 参数错 | 400 |
| A0401 UNAUTHORIZED | 未鉴权 | 401 |
| A0403 FORBIDDEN | 无权（冻结/越权凭证） | 403 |
| A0404 NOT_FOUND | 不存在（含越权订单不泄漏存在性） | 404 |
| A0409 CONFLICT | 冲突（额度用完/会话已结束/状态 CAS 失败） | 409 |
| A0429 RATE_LIMITED | 并发配额满 | 429 |
| B0001 BIZ_ERROR | 业务错 | 400 |
| B0002 SYSTEM_ERROR | 系统错（含池满 503 分支复用码） | 500/503 |
| C0001 DOWNSTREAM_ERROR | 下游错 | 502 |

## 11. 调度任务汇总

| 任务 | 周期 | 幂等/收敛机制 |
|---|---|---|
| SubscriptionPayService 回查 | fixedDelay 5s | trade.query 终态收敛 + CAS + request_id |
| TicketService.autoClose | fixedDelay 60s | CAS WHERE status=RESOLVED + 7 天阈值 |
| ProactiveService 轮询 | fixedDelay 60s（initialDelay 20s） | event_key 唯一键 |
| StatsService 快照 | fixedDelay 600s（initialDelay 30s） | stats_daily 按日 upsert |

## 12. 测试设计映射（9 个测试类）

| 测试类 | @Test 数 | 覆盖 |
|---|---|---|
| Bm25IndexTest | 7 | n-gram/同义词/docText/打分 |
| VectorIndexTest | 5 | rrfFuse/pointId/降级/直答阈值 |
| LlmClientTest | 2 | 降级熔断/路由 |
| ChatServiceTest | 21 | 身份注入/越权/工具循环/历史预算/断流/接管/向量直答 |
| CommercialFeaturesTest | 8 | open-key 生命周期/冻结拒绝/会话凭证/老会话兼容/账单幂等/非法账期/审计/metrics |
| SubscriptionServiceTest | 6 | 订阅全场景/免费额度 CAS/一码一用 |
| SubscriptionPayServiceTest | 3 | 支付状态机/回查收敛/开通幂等 |
| TicketServiceTest | 4 | CAS 流转/幂等并发/自动关闭 |
| TestApp | — | 测试引导（H2 上下文） |

合计 56 个测试方法（当前代码计数）；最近全绿执行记录见 UPGRADE-v1.2.0.md §17.3（向量批 55/55）。REST 冒烟 smoke-test.py（92 项×双实例）、LLM 评测 llm-eval.py（17 项语义断言，真实 DeepSeek）为独立脚本，不在 mvn test 内。

## 13. LLM 应用场景实现定位（8 场景 + 公共底座）

> 按用户视角场景反查代码。行号为 v1.2.0 快照（以类.方法名为准）；路径相对仓库根。场景语义与职责全景见 ARCHITECTURE §6.6，选择机制见 DESIGN §3.2.1。

### 13.1 场景 1：问法对不上知识库（语义理解 + KB 消化）

| 环节 | 位置 |
|---|---|
| 检索词改写（口语归一/指代补齐） | ChatService.ragQuery:583 → rewriteQuery:595 |
| 混合检索 Top3 | KbService.search:61（缓存:65）；Bm25Index.search:51；VectorIndex.search:68；rrfFuse:209 |
| KB 全文进 system + 规则 5 冲突仲裁 | ChatService.systemPrompt:661（尾部「知识库参考」拼接） |
| 引用落库/解决率 | ChatService.joinRefs:785、llmAnswer:418 → KbService.recordCited:245 / citationStats:258 |
| 对照：未订阅 FAQ 直答（不走 LLM） | ChatService.faqAnswer:691（双阈值:695） |

### 13.2 场景 2：查自己的数据（人话→查询→人话）

| 环节 | 位置 |
|---|---|
| 工具菜单（说明书） | ToolService.specsFor:33（list:41 / 单查:37 / 钱包:46） |
| 菜单进请求 tools 字段 | LlmClient.doChat:215-223 |
| tool_calls 流式拼接 | LlmClient:282 → mergeStreamToolCalls:336 |
| 循环执行与注回 | ChatService.llmAnswer:411 循环、:427-436 |
| 真实查库 | RideClient.get:31；分发 ToolService.execute:77 |
| 身份注入/归属校验/脱敏 | ToolService.execute:77 内部；mask:172（phone:184） |
| 数据→人话 | llmAnswer:416-425 无 tool_calls 分支 |

### 13.3 场景 3：多轮追问（上下文记忆）

| 环节 | 位置 |
|---|---|
| 历史组装总控 | ChatService.appendHistoryWithBudget:509 |
| 跳过本轮已落库 USER | :513 |
| 6000 字符预算 | :521 |
| 滚动摘要（零 LLM） | :528 → summarizeOlder:557（首句≤50/总≤600） |
| 上轮轨迹注回 | :546【上轮工具查询记录】 |
| 规则 6 同数据可引用/新问题须重查 | systemPrompt:661 第 6 条 |

### 13.4 场景 4：一句话查多样（多工具并行+综合）

| 环节 | 位置 |
|---|---|
| 一轮多 tool_calls | LlmClient.mergeStreamToolCalls:336 按 index 分拼 |
| 并行执行/10s 超时 | ChatService.runTools:603；toolPool:72（fixed 8） |
| 多份结果注回 | llmAnswer:430-436 |
| 综合回答 + 分→元（规则 1） | 下一轮 callLlm:413；systemPrompt:661 第 1 条 |

### 13.5 场景 5：截图理解（视觉多模态）

| 环节 | 位置 |
|---|---|
| 上传校验 ≤5MB/≤3 张 | UploadService:24-25；UploadController POST /api/uploads |
| base64 data URL + parts | ChatService.currentUserMessage:477 |
| 附图→换视觉模型 | LlmClient.doChat:206（hasImages + visionModel 覆盖） |
| 规则 8 读图/图内文字仅作数据 | systemPrompt:661 第 8 条 |
| 历史图片不重传 | appendHistoryWithBudget:509 内只留标记 |
| 工单带附件 | llmAnswer:438 withDraftAttachments |

### 13.6 场景 6：英文问英文答（多语言跟随）

| 环节 | 位置 |
|---|---|
| 唯一实现=prompt 首段指令（无检测代码/无翻译服务） | systemPrompt:661 开头「检测用户最新消息的语言…跟随回复（KB 中文翻译要点后引用）」 |
| 验证 | llm-eval.py 17 项含英文语义断言 |

### 13.7 场景 7：解决不了→工单草稿（判断+浓缩）

| 环节 | 位置 |
|---|---|
| 何时调 create_ticket（description 引导） | ToolService.specsFor:67 |
| 参数 schema（category/summary/orderId 模型填） | :68-72 |
| 执行只返回草稿不落库 | execute:77 create_ticket 分支（draft:true） |
| 草稿拦截+合并附图 | ChatService.llmAnswer:437-438 |
| 规则 4 需确认才创建 | systemPrompt:661 第 4 条 |
| 确认后真建单（幂等） | TicketService.create:38（request_id 唯一键）；前端确认卡片 chat.html |
| 超轮兜底引导工单 | llmAnswer:449 |

### 13.8 场景 8：办不到的要求（拒办+防注入）

| 环节 | 位置 |
|---|---|
| 硬：菜单无写工具 | ToolService.specsFor:33 全量只读+草稿 |
| 硬：身份注入/越权 A0404 | execute:77 内部 |
| 软：规则 3 拒办+不为此乱查 | systemPrompt:661 第 3 条 |
| 软：规则 7 防注入 | 同上第 7 条 |
| 输出侧二次打码 | ChatService.maskOutput:861 → maskPii:848（三正则:841-846） |

### 13.9 公共底座（贯穿全部场景）

| 机制 | 位置 |
|---|---|
| 四道闸 | ChatService.sendMessage:223（接管:257/订阅:239/并发:243/预算:380） |
| 订阅实时判定/免费额度 CAS | SubscriptionService.isSubscribed:111 / tryConsumeFree:265 |
| 审计包装（恒传 token 回调→全轮流式） | ChatService.callLlm:627（回调:637） |
| 主备路由/熔断/重试/已出流保护 | LlmClient.chat:112 / attempt:136 / retryable:182 / recordFailure:193 |
| SSE 推送 | SseHub.send:37；tool 事件 llmAnswer:444；message/done sendMessage:277-278 |
| 跨实例广播/审计异步/在途 token | MqBridge.broadcast:137 / writeAudit:167 / pendingTokens:193 |
| 断流 ABORTED / 失败 FALLBACK | llmAnswer:454-464 / :466-473 |
| LLM 专用线程池 8/32 | CsAsyncConfig.llmRequestExecutor:28 |
| 前端打字机/上传/草稿卡片/满意度 | chat.html（SSE onmessage 分发 token/tool/message/done） |

**记忆抓手**：8 场景的「智能」几乎都落在同一方法 `systemPrompt`（8 条规则）+ 同一循环 `llmAnswer`；差异只在喂什么材料（KB/历史/图片/工具结果）与模型产出什么（正文 vs tool_calls）。硬保证全在 `ToolService.execute` 与 `sendMessage` 四道闸，与模型是否听话无关。