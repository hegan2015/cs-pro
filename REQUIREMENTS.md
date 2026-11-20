# cs-pro 智能客服系统需求说明书

版本 v1.2.0 ｜ 2026-11-20 ｜ 状态：已交付并验证

> 文档定位：本文是**需求基线与验收映射**。字段级数据字典、接口契约、安全细节以 [DESIGN.md](DESIGN.md) 为权威；架构视图见 [ARCHITECTURE.md](ARCHITECTURE.md)；类/方法级实现设计见 [DETAILED-DESIGN.md](DETAILED-DESIGN.md)；实施与验证记录见 [UPGRADE-v1.2.0.md](UPGRADE-v1.2.0.md)。

## 1. 背景与目标

ride-pro（打车）与 payment-pro（支付）各自携带业务规则与客诉入口，但缺少统一的智能客服层：用户咨询分散、人工客服成本高、业务数据查询需要登录多个控制台。cs-pro 作为**独立轻量进程**为两系统提供统一智能客服入口，覆盖「租户订阅准入 → 机器人自动问答（LLM）→ 业务只读查询 → 工单与统计」闭环。

| 目标 | 可度量口径 |
|---|---|
| 机器人自动问答 | LLM + FAQ 知识库（BM25+向量混合 RAG）+ 业务数据（Function Calling），机器人解决率进统计报表 |
| 业务查询零侵入 | 对话中直查 ride 订单/钱包/流水、payment 交易/账户，**只读不写**，两系统不改一行代码 |
| 工单闭环 | 机器人解决不了 → 工单流转（含 ride-pro 开放收单），满意度 1–5 星 |
| 租户级商业化 | 三档套餐订阅准入、免费 FAQ 限次、激活码/支付双通道、月度账单 |
| 商用可运维 | 多实例、开放 API、审计/metrics/健康页、CI |

**硬约束**（需求级，不可妥协）：

1. **资金安全边界**：对业务系统默认只读；唯一例外是订阅购买经批准调用 payment-pro `trade.create/trade.pay`（金额服务端固定，LLM 工具层永不触达写端点）。
2. **零侵入**：ride/payment 零代码改动、零 schema 改动；cs-pro 宕机对两系统零影响。
3. **数据保护**：`data/` 只增不删；停服务不触碰数据；迁移工具对 H2 源库只读。
4. **降级可用**：LLM 是唯一外部强依赖且有完整降级——无 Key/超时/熔断/预算超限时，FAQ 直答 + 工单/统计/查询全闭环照常运行。
5. **端口隔离**：8096（第二实例 8099），与 ride/payment/yiwu/claim/ops 全错开，多系统可同时运行。

## 2. 用户与角色

| 角色 | 入口 | 鉴权 | 核心诉求 |
|---|---|---|---|
| 乘客 PASSENGER / 司机 DRIVER | chat.html 用户咨询 H5 | 建会话一次性 accessToken，此后 X-Session-Token | 问规则、查自己的订单/钱包/流水、附图、建单、转人工、评价 |
| 商户 MERCHANT | chat.html（pp 工具可见） | 同上 | 查 payment-pro 交易/账户（需 pp 入驻） |
| 客服坐席 | agent.html 工作台 | X-Admin-Token | 工单池处置、人工接管会话回复 |
| 运营管理员 | admin.html 看板 | X-Admin-Token（支持逗号分隔多令牌轮换） | 全量运营页签、FAQ 运营、租户/订阅/凭证/账单管理、审计 |
| 上游系统（ride-pro） | 开放 API | X-Open-Key 租户凭证 | 代用户收单/回查工单、自查用量/会话/账单 |
| 运维 | health.html / metrics | X-Admin-Token | 组件巡检、Prometheus 指标、日志 traceId 排障 |

## 3. 功能需求（FR）

### 3.1 对话域 FR-CHAT

| 编号 | 需求 | 验收要点 |
|---|---|---|
| FR-CHAT-01 | 会话创建/列表/详情/结束，身份（userId/userRole/tenantCode）绑定 | 会话凭证仅建会话返回一次；详情/列表剥离 token；老会话兼容放行 |
| FR-CHAT-02 | 消息发送与 SSE 流式推送（token 打字机 + tool 进度 + message/done） | 消息先落库再调 LLM；多实例经 RocketMQ 广播到达 |
| FR-CHAT-03 | 多轮记忆：字符预算内近期轮次 + 更早轮次本地滚动摘要 + 上轮工具轨迹注回 | 6000 字符预算；摘要零额外 LLM 调用；指代消解（"要多久到账"承接上轮"怎么提现"） |
| FR-CHAT-04 | 图片多模态：上传 ≤5MB、每消息 ≤3 张，附图走视觉模型 | 工单带附件、工作台缩略图；历史图片不重传 |
| FR-CHAT-05 | 多语言跟随（英文问英文答） | LLM 评测集语义断言 |
| FR-CHAT-06 | 人工接管：用户请求转人工 / 管理端接管与交还；接管中消息排队不耗额度 | 接管幂等；客服回复经 SSE 送达 |
| FR-CHAT-07 | 满意度评价 1–5 星 | PK 防重 |
| FR-CHAT-08 | LLM 建单需用户确认（草稿卡片） | 防幻觉/循环建单；确认才落库 |

### 3.2 知识库域 FR-KB

| 编号 | 需求 | 验收要点 |
|---|---|---|
| FR-KB-01 | FAQ 增删改查 + 分类/关键词/启用开关 | 管理端全列表真分页 {total,list} |
| FR-KB-02 | 混合检索：BM25（中文 n-gram+12 组同义词）+ 向量（Qdrant+本地 ONNX embedding）RRF 融合 | Top3 注入 system；30s TTL 缓存变更即失效 |
| FR-KB-03 | FAQ 直答双阈值：BM25≥1.0 或 cosine≥0.65 | 0.65 为 int8 模型实测校准（同主题改写 0.66~0.81、跨主题≤0.50） |
| FR-KB-04 | 向量旁路降级：容器不可达→纯 BM25，30s 熔断；可整体关闭 | CS_VECTOR_ENABLED=false；管理端向量状态+一键重建 |
| FR-KB-05 | 版本化：每次改写前旧版快照，支持版本列表与一键回滚 | cs_kb_revision；K31 永久保留作演示数据（用户拍板） |
| FR-KB-06 | 坏例队列：差评/降级会话自动入队（原因+问答摘要+引用条目） | 管理端坏例页签 |
| FR-KB-07 | 引用解决率：cited_count/cited_resolved_count 度量每条 FAQ 实际效果 | 管理端引用统计 |

### 3.3 业务查询域 FR-TOOL

| 编号 | 需求 | 验收要点 |
|---|---|---|
| FR-TOOL-01 | 9 个只读工具（ride 6 + pp 2 + create_ticket），白名单硬编码 | 无写工具；菜单按角色动态裁剪（DESIGN §3.2.1） |
| FR-TOOL-02 | 身份参数服务端注入，LLM 不可决定 | 越权查他人订单返回 A0404 不泄漏存在性 |
| FR-TOOL-03 | 工具结果脱敏后进 prompt + LLM 输出侧二次 PII 打码 | 手机号掩码、卡号前 6 后 4 |
| FR-TOOL-04 | 工具循环 ≤3 轮（最多 4 次调用），最后一轮不带工具定义强制收敛 | 同轮多工具并行、单工具 10s 超时 |
| FR-TOOL-05 | pp 查询经 8443 OpenAPI HMAC 验签 + 自签证书定向信任 | 未入驻时 pp 工具整体禁用，对话不中断 |

### 3.4 工单域 FR-TICKET

| 编号 | 需求 | 验收要点 |
|---|---|---|
| FR-TICKET-01 | 生命周期 OPEN→PROCESSING→RESOLVED→CLOSED；OPEN/PROCESSING 可本人撤单 CANCELLED | 全部流转 CAS；7 天自动关闭 |
| FR-TICKET-02 | 建单幂等 request_id 唯一键 | LLM 建单确定性键防循环重复 |
| FR-TICKET-03 | 工单携带会话快照/关联订单/分类/优先级/来源/图片附件/用户昵称/问题描述 | 详情抽屉时间线+备注 |
| FR-TICKET-04 | ride-pro 开放收单：POST /api/open/tickets（X-Open-Key，source 缺省 ride-pro）+ 回查 | requestId 幂等返回同一工单；category 七类限枚举 |
| FR-TICKET-05 | 管理端/坐席端处置：指派/解决/关闭/备注；ride 来源徽标+订单深链 | 深链新页签跳 ride 管理端 ?order= |

### 3.5 订阅域 FR-SUB

| 编号 | 需求 | 验收要点 |
|---|---|---|
| FR-SUB-01 | 租户管理（code 唯一，种子 ride-pro/payment-pro），创建/冻结 | 冻结即拒（开放 API 403） |
| FR-SUB-02 | 门槛矩阵服务端实时判定（expire_at > now）：未订阅 FAQ 限次 5 条/人/日、无 LLM/业务查询；工单不付费墙 | 超额 A0409+订阅引导；CAS 原子计数并发不超发 |
| FR-SUB-03 | 三档套餐 free/standard(¥99/30天,10分/千token,日预算30万,并发2)/pro(¥299,8分/千,200万,并发8)，可配 | 续订叠加 newExpire=max(now,expire_at)+days |
| FR-SUB-04 | 激活码兑换（默认零依赖通道）：批量生成 CS-XXXX-XXXX，一码一用 | 兑换单事务 CAS；管理端手工开通 source=ADMIN 留痕 |
| FR-SUB-05 | payment-pro 支付开通（可选）：PENDING→PAID→ACTIVATED/FAILED/CLOSED | 金额服务端固定；5s 调度回查 trade.query 收敛终态；开通 CAS+request_id 不重复加天数 |
| FR-SUB-06 | 租户并发配额（超限 429 立即拒绝，不入库不排队）+ 日 token 预算护栏（超限降级 FAQ+BUDGET 审计） | 信号量 tryAcquire 不阻塞；预算含 MQ 在途 token |
| FR-SUB-07 | 月度账单：(tenant,period) 唯一键 upsert，当月 OK 调用 token×套餐单价向上取整 | 重算幂等；管理端出账、开放 API 自查 |

### 3.6 开放与运营域 FR-OPEN / FR-ADMIN / FR-OBS

| 编号 | 需求 | 验收要点 |
|---|---|---|
| FR-OPEN-01 | 开放凭证生命周期：生成/吊销/冻结拒绝；key→tenant 服务端解析 | 无效 401、冻结 403、吊销即失效 |
| FR-OPEN-02 | 开放 API：usage（订阅+今日 token）/sessions（分页剥离敏感字段）/invoices/tickets | 租户隔离 |
| FR-ADMIN-01 | 管理端 8 页签全列表真分页：总览/会话审计/工单/FAQ/LLM 审计/租户激活码订阅单/管理审计坏例主动事件/open-key 账单 | {total,list}+page/size 契约 |
| FR-ADMIN-02 | 管理审计：全变更操作留痕（旁路写入不阻断主流程） | cs_admin_audit |
| FR-ADMIN-03 | 管理令牌轮换（逗号分隔多令牌） | 新旧令牌并行期可用 |
| FR-OBS-01 | Prometheus metrics（/api/admin/metrics）+ 健康页组件巡检 5s 轮询 | DB/MQ/LLM/上游/主动服务/向量徽章 |
| FR-OBS-02 | LLM 审计六状态全留痕：OK/ERROR/CIRCUIT/BUDGET/FALLBACK/ABORTED | 与日志 grep 口径一一对应；promptVersion/实际模型/token 落库 |
| FR-OBS-03 | 统计报表：会话/消息量、解决率、转工单率与分类分布、响应时长、满意度、高频问题 TopN、LLM 用量 | SQL 聚合 + stats_daily 每日快照 |
| FR-PROACTIVE-01 | 主动服务：定时轮询 ride 超时订单/payment 失败支付单（只读），event_key 幂等推送 | 已有会话复用、无会话自动建；上游未跑自动跳过 |

## 4. 非功能需求（NFR）

| 编号 | 类别 | 指标/约束 | 实现锚点 |
|---|---|---|---|
| NFR-01 | 性能 | LLM 调用超时 60s；工具单个 10s；SSE 连接 600s；LLM 专用线程池 core8/max32 AbortPolicy | LlmProperties.timeoutMs / ChatService.toolPool / SseHub / CsAsyncConfig |
| NFR-02 | 弹性 | 主端点重试 2 次指数退避（1s 起）→备用路由；per-endpoint 熔断 5 连败/60s；已出流不重试不切备 | LlmClient.attempt/retryable/recordFailure |
| NFR-03 | 降级 | 四级降级链：向量→BM25；LLM 主→备→FAQ；MQ→直写；pp 未配置→工具禁用 | 各组件旁路开关，主链路不阻断 |
| NFR-04 | 并发 | 每租户并发默认 2（套餐可配 8）；免费额度 CAS 不超发；建单/订阅/评价/账单/主动事件全幂等 | 信号量 + 唯一键 + CAS |
| NFR-05 | 安全 | 三级鉴权（管理/会话/开放）；身份服务端注入；归属校验；PII 两道打码；防注入声明（软）+白名单（硬）；Key 不入日志/DTO | DESIGN §8 |
| NFR-06 | 数据 | 双模持久层：默认 Docker MySQL（cs_pro 库），CS_DB_MODE=h2 零依赖；迁移只读源库+行数校验；data/ 只增不删 | db/schema-mysql.sql / migrate-to-mysql.sh |
| NFR-07 | 部署 | 一键 ./run.sh（自检 3306 可达）；./stop.sh SIGTERM+15s+SIGKILL 兜底；多实例同库水平部署；端口全错开 | run.sh / stop.sh |
| NFR-08 | 可观测 | 日志含 traceId；LLM 判读 grep 口径（LLM OK/tool/降级/熔断）；健康页+metrics | TraceWebFilter / HealthService / MetricsService |
| NFR-09 | 兼容 | OpenAI 兼容协议适配 OpenAI/DeepSeek/通义/Kimi/Ollama；换供应商改 base-url/model 即可 | LlmClient 零第三方 SDK |
| NFR-10 | 交付 | CI（temurin17+mvnw package+jar 工件）；文档套齐（README/DESIGN/UPGRADE/COLLABORATION+本三件套） | .github/workflows/ci.yml |

## 5. 验收标准与验证映射

> 以下为**已执行的验证记录**（出处 UPGRADE-v1.2.0.md），非本次重新运行结果。当前代码单测方法数 56（9 个测试类 @Test 计数）；向量批交付时全绿记录为 55/55（§17.3），其后工单对接批补充了测试资源与用例。

| 验证层 | 规模（记录值） | 覆盖需求 |
|---|---|---|
| 单元测试 | 55/55（向量批，§17.3）；当前 56 方法 | FR-KB（BM25/RRF/直答阈值）、FR-TICKET（CAS/幂等并发）、FR-TOOL（身份注入/越权）、FR-SUB（全场景/支付收敛）、FR-OPEN/FR-OBS（商用特性） |
| REST 冒烟 | 92/92 × 双实例（8096/8099，MySQL+MQ） | 全闭环 + 鉴权 401/403 + 限次超额 + 幂等 + 冻结准入 + 分页契约 + 向量状态/重建 |
| LLM 评测 | 17/17（真实 DeepSeek，独立评测租户） | FR-CHAT-03/05、FR-TOOL（钱包/订单/计价/多轮记忆/拒代办/越权/流水/接管） |
| 商用 live | F 批 26 项 + 令牌轮换 4 项 | FR-OPEN/FR-ADMIN/FR-OBS |
| 浏览器实测 | 五页面全流程 | SSE 打字机/图片上传/工单抽屉/看板/健康页轮询 |
| 迁移与多实例 | 752 行迁移行数校验一致；跨实例 SSE 实测到达 | NFR-06/07 |

**验收判定原则**：不改库/删断言凑绿；对账全绿≠语义对（小差额逐单追）；源码写完≠线上生效（jar 需重建重启）；UI 须浏览器实测而非仅接口断言。

## 6. 范围外与演进（需求级声明）

本期**不做**（列入 DESIGN §11 演进，届时单独拍板）：

- 统一登录（现为 ID 直查 + 会话凭证）；
- 在线人工转接 WS（现为工单异步 + 接管排队）；
- 开放 API HMAC 签名 + 限流（现为 X-Open-Key 凭证）；
- 熔断/配额/预算 Redis 外置全局（现为进程内，多实例各自计数）；
- 真实出账（现为演示口径账单聚合）；
- ride 订单号→pp 订单号联查（需 ride 侧只读映射端点，属侵入式改动）；
- 多供应商加权路由、本地小模型兜底（现为主备两级路由 + FAQ 文字兜底）。

## 7. 决议与约束溯源

- 技术选型/订阅模型/硬约束决议：DESIGN §1.3–1.5（2026-11-17 用户确认）；
- 默认 Docker MySQL：2026-11-18 用户拍板「h2改为mysql，docker里的」；
- 向量混合检索：2026-11-18 用户拍板引入 Qdrant + 本地 ONNX embedding；
- K31 永久保留、端口 8096/8099、商用中间件与保险系统公用 Docker 栈：均已确认（DESIGN §12）；
- 用户指导 AI 开发要点复盘：[COLLABORATION.md](COLLABORATION.md)（新会话先读）。