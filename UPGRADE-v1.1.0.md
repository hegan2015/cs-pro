# cs-pro v1.1.0 更新说明（商用化快赢五项 + BM25 召回补丁）

日期：2026-11-18 ｜ 基线：v1.0.0（main=25e01a2）｜ 状态：全部落地并实测通过
范围：①多轮工具记忆 ②租户日 token 预算 ③审计/熔断/重试/工具并行 ④golden 评测集 ⑤人工接管 ⑥BM25 同义词扩展。**H2 文件库保持不变**（不迁 PostgreSQL）。

验证结论速览：`./mvnw package` 28/28 单测绿；REST 冒烟 **59/59**；LLM 评测集 **17/17**；预算/熔断/同义词三项演练全部命中预期（详见 §8）。

---

## 0. 升级一份老部署怎么操作（TL;DR）

```bash
cd cs-pro
git pull                                # 或拷贝整个目录
./stop.sh                               # 只停进程，data/ 不动
./run.sh                                # 重新编译+启动；schema.sql 尾部是幂等 ALTER，老库自动补列
python3 smoke-test.py                   # 59 项，期望全 PASS
python3 llm-eval.py                     # 17 项（需 LLM Key + ride-pro 在跑），期望全 PASS
```

新配置项全部有默认值，**不改任何配置也能直接升级**；想启用预算/调重试，在 `~/.config/cs-pro/llm.properties` 追加：

```properties
cs.llm.retry-max=2                     # 可重试错误额外重试次数（默认 2）
cs.llm.retry-backoff-ms=1000           # 指数退避基数（默认 1000ms：1s→2s）
cs.llm.daily-token-budget-per-tenant=300000   # 租户日 token 预算，0=不限（默认）
```

---

## 1. ① 多轮工具记忆 + 历史 token 预算

**问题**：v1.0 每轮把工具查询结果丢弃，用户问完余额再问“那笔钱能不能覆盖退款”，模型要么拒答要么重新查——多花 token 且答案漂移；且历史全量拼接，长会话 prompt 无上限。

**怎么做**：
1. 数据层：`cs_message` 增加 `tool_trace CLOB` 列（幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，两份 schema.sql 同步：生产 `cs-gateway/src/main/resources/` 与测试 `cs-core/src/test/resources/`）。MessageDTO 加 `toolTrace` 字段，MessageMapper INSERT 带上该列。
2. 编排层：ChatService 工具循环里把每次调用压成一行文本 `toolName(args≤120字) => result≤400字` 收集进 trace（单条上限 400、总量上限 1500，超出截断），机器人回复落库时随消息写入 `tool_trace`。
3. 历史重建：`appendHistoryWithBudget()` 取最近 `historyRounds*2+1` 条（新→旧），**跳过刚插入的本轮 USER 消息**（它单独作最后一条 user 消息，避免重复——见 §9 踩坑），按“内容+trace 字符数 ≤ `cs.chat.max-history-chars`（默认 6000）”从新到旧截断，再还原时间序；BOT 消息有 trace 时渲染为一条 assistant 消息：`【上轮工具查询记录，引用同数据追问时可直接使用】\n<trace>\n【当时给用户的回复】\n<正文>`。
4. 提示词：system 规则第 6 条明确“历史记录只用于同数据追问；新问题仍须调用工具取实时结果，严禁答非所问”。

**验收**：llm-eval 多轮用例——第 1 轮查钱包出 3413.36；第 2 轮“刚才那个余额，不用再查了”直接引用记忆答对，且不产生新工具调用。

## 2. ② 租户日 token 预算护栏

**问题**：LLM 按 token 计费，单租户异常刷量能烧穿整个 Key 的额度，必须服务端兜底（不是前端提示）。

**怎么做**：
1. 配置：`LlmProperties.dailyTokenBudgetPerTenant`（0=不限）。
2. 统计口径：`LlmCallMapper.sumTokensTodayByTenant(tenantCode, dayStart)` —— `prompt+completion` 求和，**只算 `status='OK'` 的真实调用**（失败/降级/预算行的 tokens 留 NULL，不污染预算），经 `cs_llm_call JOIN cs_session` 按会话租户归属聚合，日界取本机当日 0 点。
3. 拦截位：llmAnswer 最前端（订阅校验之后、组 prompt 之前）——超限则：写一条 `status='BUDGET'` 审计（error 记 `used=X/budget=Y`）→ WARN 日志 → 直接走 FAQ 降级，回复带前缀“（今日租户智能助手用量已达上限，已为您检索知识库）”。**不消耗免费额度计数，也不调 LLM**。

**验收演练**：`CS_LLM_DAILYTOKENBUDGETPERTENANT=2000 ./run.sh --no-build` 重启后发消息 → 收到上限前缀 + FAQ 答案；`GET /api/admin/llm-calls?status=BUDGET` 出现 `used=241002/2000` 行。环境变量走 Spring relaxed binding，优先级高于 `~/.config` 文件，演练完去掉 env 重启即恢复。

## 3. ③ 审计补全 + per-endpoint 熔断 + 退避重试 + 工具并行

**3.1 审计状态机**。`cs_llm_call.status` 从 OK/ERROR 扩为 **OK / ERROR / CIRCUIT / BUDGET / FALLBACK**：
- call 级：真实发出且成功=OK；发出但失败=ERROR；**熔断期被拒= CIRCUIT**（没发出，tokens NULL）。
- 消息级汇总：**FALLBACK**（一次用户消息最终降级 FAQ，error 记 `熔断降级|调用失败降级 tokensSoFar=X cause=...`，含该轮内已烧掉的 tokens）；**BUDGET**（预算拦截）。
- 查询口沿用 `GET /api/admin/llm-calls?status=...`，admin.html 的 LLM 审计表自动可按新状态过滤。

**3.2 per-endpoint 熔断**。v1.0 是全局开关：一个端点坏会误伤所有端点（比如多租户不同 base-url、或演练时临时指错）。改为 `Map<String, Breaker>`，key=`baseUrl去尾斜杠|model`；同一端点**连续 5 次失败 → 熔断 60s**；熔断期内调用直接抛 `LlmException(markCircuit())`（不发起网络请求、不重试）；**half-open 不设探测线程**——60s 后下一次真实请求就是探测，成功则失败计数清零，失败则再熔 60s。`available()` 也改为按当前端点判断。

**3.3 分类退避重试**。`LlmException` 增加 fluent 标记：`withHttpStatus(int)`（doChat 两处非 2xx 分支都带上）、`markStreamed()`（sink 包装 AtomicBoolean，出过 token 就置位）。重试判定 `retryable()`：
- 已流式输出过 → **不重试**（重试=重复吐字）；
- 有 HTTP 状态：429/5xx 可重试，**4xx 不重试**（Key 错、参数错重试无意义）；
- 无状态（status=0）：cause 非空（连接拒绝/超时等网络类）可重试。

重试循环 `maxAttempts = retryMax+1`（默认 3 次尝试），退避 1s→2s（`retryBackoffMs*2^n`），日志 `LLM 端点 {} 第 {} 次失败（{}），{}ms 后重试`。失败计数是 **chat 级计一次**（一次用户请求最终失败算 1 次，不把 3 次尝试计成 3 次熔断路径），成功清零。

**3.4 同轮多工具并行**。模型一轮返回多个 tool_calls 时：1 个→当前线程直执；多个→固定 8 线程 daemon 池（`cs-tool-%d`）并发跑，每个 `future.get(10s)` 子超时，超时/异常统一填 `{"error":"工具执行超时或异常"}` 保证消息数组对齐。**@PreDestroy shutdownNow** 防泄漏。

**验收演练**：`CS_LLM_BASEURL=http://127.0.0.1:9` 重启后连发 6 条：前 5 条各触发 3 次尝试→ERROR×5→第 5 次后熔断日志 `连续失败 5 次，熔断 60000ms`；第 6 条**立即**降级（WARN `circuit=true err=LLM 熔断中...`）。审计核对：CIRCUIT≥1、ERROR 成批、FALLBACK 每消息一条。恢复：去掉 env `./run.sh --no-build`，发消息 LLM 正常（熔断器随进程重置）。

## 4. ④ golden 评测集（llm-eval.py）

**问题**：改 prompt/换模型前没有回归基准，v1.0 靠 smoke 只能测接口通，测不了“答得对不对”。

**怎么做**：零依赖 Python（stdlib），面向**语义断言**而非接口断言，17 项分 8 组：
| 组 | 断言要点 |
|---|---|
| 钱包 | 数值=3413.36 元 + tokens>0（证明走了 LLM） |
| 订单列表 | 含 O1/O2/O3 ≥2 笔（防单号幻觉） |
| 计价 | 含“起步价” + 高峰系数 1.3 |
| 多轮记忆 | 跨轮引用余额且拒重复查询 |
| 拒代办 | “直接帮我退款O1”→必须引导工单，且**不得出现“已退款”话术** |
| 越权 | P2 会话查 P1 的 O3 → 不存在/不属于您 |
| 流水/提现 | TOPUP/PAY_DEBIT/REFUND_CREDIT 关键词 |
| 人工接管 | 全链路 6 断言（见 §6） |

前提：cs(8096)+ride(8090) 在跑、ride-pro 租户已订阅。用法 `python3 llm-eval.py [base_url]`，任一 FAIL 退出码 1——可直接当回归门禁用。**它是行为测试，LLM 有随机性：失败先看回复原文再判断是回归还是模型抖动。**

## 5. ⑤ 人工接管会话

**问题**：机器人答不了只能提工单，缺“人进来接一句话”的实时通道。

**怎么做**（服务端兜底、幂等设计）：
1. 数据层：`cs_session.takeover BOOLEAN NOT NULL DEFAULT FALSE`（幂等 ALTER）+ `SessionMapper.setTakeover(id, on)`。
2. 用户侧：`POST /api/sessions/{id}/human` → 校验 ACTIVE、未接管（重复接管 409）→ 置 takeover=true → 落一条 SYSTEM 消息“已为您转接人工客服…”并 SSE 推送。此后 sendMessage 的顺序是**先落库用户消息 → takeover 为真则直接返回 null**：消息排队可查、不耗免费额度、不调 LLM。
3. 客服侧（工作台 agent.html 顶部新增「会话服务（人工接管）」双卡）：会话列表按待人工排序+红黄徽标；5s 轮询刷新消息（零依赖不引 WebSocket）；操作=接管/回复/交还。管理端 `POST /api/admin/sessions/{id}/takeover`、`/handback`、`/messages {content}`。
4. 幂等关键点：**adminSetTakeover 同状态重复调用直接返回成功**（v1.0 会抛 409——评测里用户已转人工、客服再点接管属正常并发，报错会让 UI/脚本崩）。agentReply 仅接管中可用（否则 409），消息加前缀“【人工客服】”落库并 SSE；交还后 takeover=false，下一条用户消息恢复机器人应答。
5. chat.html：「转人工」按钮 → 调 /human → 本地提示“已进入客服队列”；A0409 时刷新会话状态横幅（refreshBanner 先 GET /api/sessions/{id} 读 takeover 字段）。

**验收**：llm-eval 接管组 6 断言全过（转接→排队无回复→接管幂等→回复带标识→用户可读→交还恢复）。

## 6. ⑥ BM25 同义词扩展（召回补丁）

**问题**：中文按“ASCII 词+CJK 单字+相邻二字 bigram”建索引，无分词器 → 用户说“叫车/约车/退钱”查不到“打车/退款”条目。

**怎么做**：`Bm25Index.search()` 查询侧扩展——静态 `SYN_GROUPS` 12 组词表（打车系/退款系/收费系/客服系等），**组内任一词命中查询 → 其余词 tokenize 后并入查询项**，词频取 max（不叠加，防扩词组反噬排序）；词表加载 `classpath:/synonyms.txt` 支持外部覆盖，读失败静默不影响主链路。索引侧不动、存量数据零迁移。

**验收**：`GET /api/kb/search?q=叫车起步价` → 首位“打车怎么计价？”；`q=退钱多久到账` → 首位“退款多久到账？”。

## 7. 契约与代码变更清单

**接口**（新增）：`POST /api/sessions/{id}/human`、`POST /api/admin/sessions/{id}/takeover|handback`、`POST /api/admin/sessions/{id}/messages`。**响应字段**：SessionDTO+`takeover`、MessageDTO+`toolTrace`。

**schema**（全部幂等 ALTER，老库重启自动生效）：`cs_message.tool_trace CLOB`、`cs_session.takeover BOOLEAN DEFAULT FALSE`。

**配置**（新增键，见 §0）：`cs.llm.retry-max/retry-backoff-ms/daily-token-budget-per-tenant`、`cs.chat.max-history-chars`；`config/llm.properties.example` 同步注释。

**主要文件**：ChatService（重写sendMessage顺序/llmAnswer/历史重建/接管三方法/工具并行）、LlmClient（Breaker Map+重试循环+分类）、LlmException（标记位）、LlmProperties、LlmCallMapper、SessionMapper/MessageMapper、CsProperties、ToolService（工具描述收紧）、Bm25Index+synonyms.txt、ApiController/AdminController、chat.html/agent.html、llm-eval.py、smoke-test.py（额度用例改独立租户，见 §9）。

## 8. 全量验证记录（2026-11-18）

| 项 | 结果 |
|---|---|
| `./mvnw package` 单测 | **28/28**（kb 7 + llm 2 + core 19）BUILD SUCCESS |
| REST 冒烟 smoke-test.py | **59/59**（较 v1.0 +4：免费租户/未订阅状态/额度/默认映射用例拆分） |
| LLM 评测 llm-eval.py | **17/17**（含多轮记忆、接管全链路） |
| 预算演练 | 前缀话术 ✓ + `llm-calls?status=BUDGET` `used=241002/2000` ✓ |
| 熔断演练 | 5 连败→熔断路 ✓、第 6 条 circuit=true 即时降级 ✓、ERROR/CIRCUIT/FALLBACK 审计 ✓、恢复 ✓ |
| 同义词 | “叫车起步价”“退钱多久到账”首位命中 ✓ |
| 数据保护 | 全程未动 `data/`；两次 env 覆盖演练均重启还原 |

## 9. 踩坑与修复（实施流程的一部分，供后续维护者）

1. **本轮 USER 消息重复进历史**：sendMessage 为支持接管排队改成“先落库再判定”后，历史重建把它也算进去，末尾又追加一次 → 模型把当前提问当“上一条”，出现连环答非所问（评测一度 11/17）。修复：appendHistoryWithBudget 显式跳过首条（刚插入的 USER）并把它作为唯一末条 user 消息。**改历史拼接逻辑必须带两轮对话的行为测试**。
2. **admin takeover 幂等**：用户已转人工后客服再点接管会 409，双击即报错、评测脚本直接崩——同状态返回成功，仅“已结束会话”仍 409。
3. **smoke 不再对持久库幂等**：v1.0 额度用例假定 ride-pro 未订阅，但任何一次真实兑换（本次演练环境 ride-pro 已订到 2026-12）都会让它永远失败。修复：额度/订阅用例改用每轮新建的 `SMOKE-FREE-<rand>` 独立租户，ride-pro 默认映射单独一条断言。**新用例尽量避免绑全局租户状态**。
4. **工具描述引导模型选错工具**：“把我的订单列出来”偶发被路由到单查 `query_ride_order`（无单号必失败，进而带偏后续轮次）。修复：描述里写明“orderId 须用户明确给出，看列表用 list_ride_orders”，system 规则 2 同步。行为漂移先查日志 `LLM tool ... ok=false` 再改 prompt，不要盲目重试评测。

## 10. 明确没做的（边界）

- H2 → PostgreSQL：**按用户要求保持 H2 文件库不动**；预算/熔断/接管全部机制与库无关。
- 分词器升级（IK/HanLP）、向量混合检索：同义词表是零依赖补丁，根治见上轮讨论（Java NLP 生态成本）。
- WebSocket 推送工作台：用 5s 轮询替代，保持零依赖。
- 多实例：熔断器/预算均在进程内，水平扩容需外置状态（Redis 等）——商用化路线图的工程底座项。
