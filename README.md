# cs-pro 智能客服系统设计文档

版本 v1.2.0（商用底座 + 六特性批已交付）｜ 2026-11-18 ｜ 完整设计见 [DESIGN.md](DESIGN.md)（字段级数据字典、接口契约、安全细节以 DESIGN 为准），本版实施流程与验证记录见 [UPGRADE-v1.2.0.md](UPGRADE-v1.2.0.md)（上版 [UPGRADE-v1.1.0.md](UPGRADE-v1.1.0.md)）

定位：为 ride-pro（打车）与 payment-pro（支付）提供**统一智能客服入口**，覆盖「租户订阅准入 → 机器人自动问答（LLM）→ 业务只读查询 → 工单与统计」闭环。

> 状态说明：v1.2.0 已交付验证（48/48 单测 + 86/86 REST 冒烟 × 双实例 + 17/17 LLM 评测集 + F 批 live 26 项 + 浏览器实测）。本版新增：**商用底座**（Docker 共享中间件与保险系统公用 / H2⇄MySQL 双模持久层 + 迁移工具 / RocketMQ 事件总线可降级 / 多实例跨实例 SSE）与**六特性批**（LLM 治理：分级 max-tokens·备用路由·promptVersion·注入防护·PII 打码 / 租户并发配额 429 / RAG 改写·TopK 缓存·滚动摘要 / 坏例队列·KB 版本化回滚·引用解决率 / 图片多模态·多语言·主动服务轮询 / 开放 API 凭证·会话凭证·套餐账单·管理审计·Prometheus metrics·健康页·CI）。**默认 Docker MySQL**（2026-11-18 用户拍板「h2改为mysql，docker里的」）：`./run.sh` 直连共享栈 MySQL；`CS_DB_MODE=h2` 保留零依赖形态。管理端全列表真分页（{total,list}+page/size）+ 审计/坏例/开放凭证/账单页签。DeepSeek 真实联调通过。LLM Key 存仓库外 `~/.config/cs-pro/llm.properties`；无 Key 时自动按**降级模式**运行（FAQ 直答 + 工单/统计/查询全闭环可用）。本文档与 DESIGN 同步维护。

## 1. 目标与硬约束

| 目标 | 说明 |
|---|---|
| 机器人自动问答 | LLM API + FAQ 知识库（BM25 + 向量混合 RAG）+ 业务数据（Function Calling） |
| 业务查询 | 对话中直查 ride 订单/钱包/流水、payment 交易/账户，**只读不写** |
| 工单与统计 | 机器人解决不了 → 工单流转；会话/解决率/满意度/LLM 用量报表 |
| 租户订阅 | 商户/租户级订阅准入：免费 FAQ 限次 + 订阅解锁全功能，三档套餐 |
| 商用底座 | MySQL 持久层 + RocketMQ 事件总线 + 多实例 + 开放 API + 审计/metrics/健康页 |

硬约束（对齐 ride/payment 两系统惯例）：

- **默认 Docker MySQL**：Java 17 + Spring Boot 3.2 + Maven，`./run.sh` 一键启动（默认连 claim-shared 共享栈的 MySQL，与保险理赔系统公用；启动前自检 3306 可达并给出启动提示）；`CS_DB_MODE=h2` 可切回零依赖 H2 文件库。LLM 是唯一外部调用且有完整降级。**RocketMQ 可选**：`CS_MQ_ENABLED=true` 启用跨实例 SSE 广播 + 审计异步落库，不可用自动降级直写。
- **数据保护**：`data/` 只增不删；停服务不触碰数据；迁移工具对 H2 只读。
- **零侵入**：ride/payment 不改一行代码、零 schema 改动；cs-pro 宕机对两系统零影响。
- **资金安全边界**：对业务系统默认只读；**唯一例外**是订阅购买经批准调用 payment-pro `trade.create/trade.pay`（金额服务端固定，LLM 工具层永不触达写端点）。
- **端口隔离**：cs-gateway **8096**（第二实例 8099），与 ride（8090/2182/8093/2090x/9101）、payment（8080/2181/8092/2088x/8443）、yiwu（8095）、claim（8097）、ops（8098）完全错开，多系统可同时运行。`CS_PORT`/`DATA_DIR`/`ADMIN_TOKEN`（支持逗号分隔多令牌轮换）环境变量可覆盖。

## 2. 技术选型（已拍板）

| 选型项 | 决议 |
|---|---|
| 架构形态 | 单进程 Spring Boot + REST 集成（内部模块化，不引 Dubbo/ZK）；支持同库多实例水平部署 |
| LLM 接入 | OpenAI 兼容协议统一客户端（JDK HttpClient，不引第三方 SDK），适配 OpenAI/DeepSeek/通义/Kimi/Ollama；主备路由链 + 视觉模型 |
| 知识库检索 | 本地 BM25 倒排（中文 n-gram + 同义词扩展，进程内索引）+ **Qdrant 向量检索**（本地 ONNX Embedding，RRF 融合）+ 查询改写 + TopK 短 TTL 缓存；向量容器不可达自动降级纯 BM25 |
| payment 查询通道 | 8443 OpenAPI HMAC 验签（独立注册 cs-pro 商户，自签证书定向信任） |
| 实时推送 | SSE 单向流（token 级打字机 + 工具状态事件）；多实例经 RocketMQ 广播 |
| 存储 | **双模**：默认 Docker MySQL 8（claim-shared 共享栈 `cs_pro` 库，`db/schema-mysql.sql`）；`CS_DB_MODE=h2` 切回 H2 单文件库 `data/cs.mv.db`（MODE=MySQL） |
| 消息 | 可选 RocketMQ 5（共享 Docker 栈）：SSE 跨实例广播 + 审计异步落库，失败自动降级直写 |

## 3. 架构与模块

```
 用户咨询 H5 ──HTTP/SSE──┐
 客服工作台 H5 ──HTTP────┼── cs-gateway :8096/:8099（REST + SSE + 静态页 + 管理/开放鉴权）
 管理看板 H5 ──HTTP──────┘        │
                          cs-core（进程内模块）
                          chat 对话编排 / ticket 工单 / stats 统计·metrics·健康
                          subscription 订阅 / open 开放凭证 / billing 账单 / audit 管理审计
                          llm 适配层 / kb 知识库 / biz 业务查询适配器
                                  │ 只读                │ 可选商用 profile
              ┌───────────────────┼──────────────┐      ▼
              ▼                   ▼              ▼   MySQL 8 + RocketMQ 5
    ride-gateway :8090   payment-gateway :8443   LLM API（Docker 共享栈，与保险系统公用）
    订单/钱包/流水/计价    OpenAPI HTTPS+HMAC      OpenAI 兼容协议
```

Maven 六模块（单运行进程 cs-gateway，可多实例）：

| 模块 | 职责 |
|---|---|
| cs-common | Result/错误码/BizException/MoneyUtil(分)/TraceFilter/IdGenerator（H2 SEQUENCE ⇄ MySQL 号表双方言） |
| cs-facade | DTO 契约（进程内引用，预留拆进程边界） |
| cs-kb | FAQ 知识库 + BM25 检索 + 向量混合检索（Qdrant）+ 版本化快照 |
| cs-llm | OpenAI 兼容客户端 + 工具调用协议 + 降级熔断 + 主备路由 + 视觉 |
| cs-core | 对话编排 / 工单 / 统计 / 订阅 / biz 查询适配器 / 开放凭证 / 账单 / 管理审计 / MQ 集成 |
| cs-gateway | REST + SSE + H5 静态托管 + 管理/会话/开放三级鉴权（唯一运行进程） |

技术栈：Spring Boot 3.2.5、MyBatis Starter 3.0.3、H2 2.2.224、MySQL Connector/J + RocketMQ Client（商用 profile）、JDK 17、Lombok。

## 4. 核心功能

### 4.1 一轮对话链路
用户消息落库 → **查询改写**（当前句+上轮问题轻量指代消解，0 额外 LLM 调用）→ KB Top3 命中（30s TTL 缓存，变更即失效）→ 组装上下文（system prompt + 字符预算内近期轮次含工具轨迹 + **更早轮次本地滚动摘要**）→ 租户并发配额检查（超限 429）→ 日 token 预算检查（超限降级 FAQ 留 BUDGET 审计）→ LLM 携带工具定义调用（分级 max-tokens，主端点失败重试退避→备用路由）→ tool_calls 执行循环（最多 3 轮，多工具并行，biz 只读）→ 机器人消息落库（引用来源/工具轨迹/附件）→ SSE 流式推送（多实例经 MQ 广播）→ 意图标签落库。

### 4.2 Function Calling 工具（全部只读，白名单硬编码）
`query_ride_order` / `list_ride_orders` / `query_wallet` / `query_ledger` / `query_withdraws` / `pricing_rules`（ride REST）；`query_pp_trade` / `query_pp_balance`（payment OpenAPI 验签）；`create_ticket`（**返回草稿卡片，用户确认才落单**，防幻觉建单）。KB 检索不暴露为工具，由 RAG 前置注入 Top3 代替。

工具安全三原则：身份参数服务端注入（LLM 不可决定）+ orderId 归属校验（防越权，不匹配返回 A0404 不泄漏存在性）；无写工具；结果脱敏后进 prompt（手机号掩码、卡号前 6 后 4），**LLM 输出侧另做 PII 打码**（mask-pii）。

### 4.3 知识库 RAG 与知识运营
中文 n-gram 倒排 + BM25 打分 + 12 组同义词扩展，Top3 注入 system prompt；KB 变更即重建索引。**混合检索（2026-11-18 用户拍板引入向量库）**：Qdrant 单容器（共享栈 claim-shared，6333）+ 本地 ONNX Embedding 容器（cs-embedding，8100，bge-small-zh-v1.5 int8，512 维，模型烘进镜像），BM25 与向量双榜 **RRF 融合**（k=60）；FAQ 直答双阈值（BM25≥1.0 或 cosine≥0.65，后者按 int8 模型实测校准：同主题改写 0.66~0.81、跨主题≤0.50）。**旁路降级**：任一容器不可达→纯 BM25，失败熔断 30s；启动自动补建索引，管理台可一键重建（`POST /api/admin/kb/reindex`）与状态查看（`GET /api/admin/kb/vector-status`）；`CS_VECTOR_ENABLED=false` 整体关闭。**运营闭环**：每次改写前旧版快照（cs_kb_revision）支持一键回滚；差评/降级会话自动入**坏例队列**；**引用解决率**统计（cited_count/cited_resolved_count）回答"哪条 FAQ 真正管用"。

### 4.4 工单生命周期
`OPEN → PROCESSING → RESOLVED → CLOSED`（分派即进入 PROCESSING；用户确认或 7 天自动关闭），OPEN/PROCESSING 可本人撤单 CANCELLED。全部流转 CAS；建单幂等 `request_id` 唯一键；携带会话快照/关联订单/分类/优先级/来源/**图片附件**；满意度 1–5 星（PK 防重）。**ride-pro 接入**：开放收单口 `POST /api/open/tickets`（X-Open-Key，source 缺省 ride-pro，携带用户昵称/问题描述/订单号），管理端「工单」页签处置，见 §7.1。

### 4.5 多模态 / 多语言 / 主动服务
- **图片**：`POST /api/uploads`（≤5MB）→ 附图消息走视觉模型（parts=[text, image_url]），工单带附件、工作台缩略图。
- **多语言**：跟随用户语言作答（英文问英文答）。
- **主动服务**：定时轮询 ride 超时订单 / payment 失败支付单（只读），event_key 唯一键幂等推送——已有会话复用、无会话自动建；上游未跑自动跳过。

### 4.6 统计报表与可观测
会话量/消息量、机器人解决率、转工单率与分类分布、平均响应时长、满意度、高频问题 TopN、LLM 用量（token/调用/失败/降级）。SQL 聚合 + stats_daily 每日快照。**Prometheus metrics**（/api/admin/metrics）+ **健康页**（health.html 组件巡检 5s 轮询）+ **管理审计**（cs_admin_audit 全变更操作留痕）。

## 5. 订阅域（租户级准入）

- **租户**：cs_tenant（code 唯一，种子内置 ride-pro/payment-pro），管理端创建/冻结；会话按 tenantCode 绑定（缺省按角色映射），权益归租户、其名下用户共享。
- **门槛矩阵（服务端每次请求实时判定 expire_at > now）**：

| 能力 | 未订阅/已过期 | 订阅有效 |
|---|---|---|
| FAQ 直答 | ✅ 每日限次（默认 5 条/用户/日，超额 A0409 + 订阅引导） | ✅ 不限次 |
| LLM 智能问答 / 业务查询 | ❌ | ✅ |
| 工单创建/查询 | ✅（求助通道不付费墙） | ✅ |

- **三档套餐（cs.plans，可配）**：free（FAQ 限次）/ standard（¥99/30 天，token 10 分/千，日预算 30 万，并发 2）/ pro（¥299，8 分/千，日预算 200 万，并发 8）。续订天数叠加 `newExpire = max(now, expire_at) + days`；激活码/订阅带 plan_code。
- **月度账单**：按租户账期聚合当月 OK 调用 token × 套餐单价（向上取整），(tenant, period) 唯一键 upsert 重算幂等；管理端出账/查询，开放 API 租户自查。
- **激活码兑换（默认通道，零依赖）**：管理端批量生成 `CS-XXXX-XXXX`；兑换单事务 CAS 一码一用；管理端可手工开通（source=ADMIN 留痕）。
- **payment-pro 支付开通（可选）**：订阅单状态机 `PENDING → PAID → ACTIVATED（终态）/ FAILED / CLOSED`；金额服务端固定；**服务端 5s 调度回查 trade.query 兜底收敛终态**；开通 CAS + request_id 唯一键不重复加天数。
- 免费额度 `cs_free_usage` MERGE + CAS 原子自增，并发不超发。

## 6. LLM 适配与降级

- 配置 `~/.config/cs-pro/llm.properties`（**仓库外**，chmod 600；Key 不入日志/DTO；兼容仓库内 `config/llm.properties`，gitignore）。
- **治理**：分级 max-tokens（查询 512 / 报告 2048）；promptVersion 随审计落库；防注入声明 + 输出 PII 打码；断流标注。
- **路由与弹性**：per-endpoint 熔断（连续 5 败熔 60s，half-open 真实请求探测）；分类退避重试（429/5xx/网络可重试，4xx 与已出流不重试）；主端点重试耗尽自动切**备用端点**；LLM 调用专用线程池 + 每租户并发配额（超限 429）。
- 流式：工具调用轮非流式，最终回答轮流式 SSE 转发；附图消息走 vision-model。
- **降级策略（无 Key 系统照常运行）**：未配置 → FAQ 直答；超时/5xx → 重试→备用→FAQ 兜底；预算超限 → FAQ + BUDGET 审计。审计状态机 OK/ERROR/CIRCUIT/BUDGET/FALLBACK/ABORTED 全留痕。
- Prompt 治理：**工具白名单 + 身份服务端注入是硬防线，prompt 是软防线**。

## 7. 接口与前端

REST 契约详见 DESIGN §6。成功 body 为 data，失败 Result `{code, message}`（A0400/A0401/A0403/A0404/A0409/B0001/B0002/C0001 三段式）。

- **用户端**：会话/消息/SSE 流/评价/工单/KB 检索/订阅/上传。**会话凭证**：建会话一次性下发 accessToken，此后会话端点须 `X-Session-Token`（SSE 走 `?token=`），详情/列表剥离 token，老会话兼容放行。
- **管理端**（`/api/admin/*`，X-Admin-Token，支持逗号分隔多令牌轮换）：总览/趋势/会话审计/接管/工单池/FAQ 管理+版本回滚+引用统计/坏例/主动事件/LLM 审计/租户/激活码/订阅单/open-key/套餐/账单/**管理审计日志**/**metrics**/**健康**。
- **开放 API**（`/api/open/*`，X-Open-Key 租户凭证，无效 401/冻结 403/可吊销）：usage（订阅+今日 token）/ sessions（分页，剥离敏感字段）/ invoices / **tickets 收单与回查（ride-pro 工单接入，见 §7.1）**。

前端五页面（零构建 H5，网关静态托管，共享 common.css/common.js）：

| 页面 | 内容 |
|---|---|
| subscription.html | 订阅状态卡 + 套餐权益对比 + 激活码兑换 + 支付开通（payEnabled 时） |
| chat.html | 身份选择 + 订阅状态条 + SSE 打字机对话 + 引用来源 + 图片上传 + 一键建单 + 转人工 + 满意度 |
| agent.html | 工单池筛选分页 + 详情抽屉（会话快照/时间线/附件缩略图）+ 分派/解决 + 人工接管会话服务；**ride-pro 来源徽标 + 订单号深链**（新页签跳 ride 管理端 `?order=<orderId>`） |
| admin.html | 8 页签全列表真分页：运营总览 + 会话审计 + **工单（status/category/source/关键字筛选 + 详情抽屉 + 指派/解决/关闭/备注）** + FAQ 管理（版本/回滚/引用率）+ LLM 审计（6 状态）+ 租户/激活码/订阅单 + 管理审计/坏例/主动事件 + open-key/账单 |
| health.html | 组件巡检徽章（DB/MQ/LLM/上游/主动服务）5s 轮询 + Prometheus 指标预览 |

### 7.1 ride-pro 工单接入（开放收单口）

ride-pro 后端以租户凭证代用户提交客服工单并回查进度，cs-pro 侧零侵入对接（ride-pro 不改代码即可用 curl/HTTP 客户端接入）：

- **鉴权**：复用开放 API `X-Open-Key`（管理端「开放与账单」页签或 `POST /api/admin/open-keys {tenantCode:"ride-pro"}` 生成；无效 401、租户冻结 403、吊销即失效）。
- **收单**：`POST /api/open/tickets`，body `{requestId, userId, userName?, source?, category?, orderId?, title, description?, attachments?}`。`requestId` 幂等（唯一键，重复提交返回同一工单）；`source` 缺省 `ride-pro`；`category` 限 计价/支付/退款/提现/账号/投诉/其他（缺省 其他）；`title` 落 summary，`attachments` 为 `/uploads/` 相对路径逗号列表（≤3 张）。返回与用户端 `POST /api/tickets` 同构的 TicketDTO（`id` 即工单号，含 `status`）。
- **回查**：`GET /api/open/tickets?userId=&status=&page=&size=`（{total,list} 分页）与 `GET /api/open/tickets/{id}`（工单 + 备注时间线），供 ride-pro 展示处理进度。
- **处置**：管理端 admin.html「工单」页签筛选（含 source=ride-pro）+ 详情抽屉（来源徽标/订单深链/用户/描述/附件/备注时间线）+ 指派/解决/关闭/加备注；坐席端 agent.html 工单池同步展示 ride 上下文。状态机与既有工单一致（OPEN→PROCESSING→RESOLVED→CLOSED，CAS + 7 天自动关闭）。
- **schema**：cs_ticket 增列 `user_name`/`description`（H2 幂等 ALTER 自动生效；MySQL 存量库按 db/schema-mysql.sql 注释手动 ALTER 一次）。

```bash
KEY=$(curl -s -X POST http://127.0.0.1:8096/api/admin/open-keys -H 'X-Admin-Token: cs-admin' \
  -H 'Content-Type: application/json' -d '{"tenantCode":"ride-pro"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["apiKey"])')
curl -s -X POST http://127.0.0.1:8096/api/open/tickets -H "X-Open-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"requestId":"RIDE-T-001","userId":"P1001","userName":"张三","category":"投诉","orderId":"O20261119001","title":"司机绕路投诉","description":"行程明显绕路，要求核查计价"}'
curl -s "http://127.0.0.1:8096/api/open/tickets?userId=P1001" -H "X-Open-Key: $KEY"
```

## 8. 安全与正确性要点

1. **资金边界**：无任何写业务系统能力（订阅支付除外且服务端常量封装）；被问退款只解释规则 + 引导。
2. **越权防护**：会话身份绑定 + **会话访问凭证** + 列表查询服务端注入 ID + 订单归属校验；开放 API 租户隔离（key→tenant 服务端解析，冻结即拒）。
3. **LLM 建单需用户确认**（草稿卡片），防幻觉/循环建单；防注入声明 + 输出 PII 打码。
4. **幂等**：建单/订阅单 request_id 唯一键；评价 PK 防重；账单一租户一账期一单；主动事件 event_key 唯一；消息先落库再调 LLM；调度 CAS 重启不丢进度。
5. **订阅准入服务端强制**：实时判定、CAS 原子计数、一码一用、开通幂等、支付终态调度收敛、并发配额与日预算服务端拦截。
6. **敏感信息**：apiKey/secret 不入日志；accessToken 仅建会话返回一次；进 prompt 数据脱敏；管理变更全量审计留痕（旁路写入不阻断）。

## 9. 部署与运维

```bash
cd cs-pro
./run.sh                # 默认：Docker MySQL 单进程（8096），mvnw package + 启动 + 就绪等待
./run.sh --no-build
./stop.sh               # SIGTERM + 15s + SIGKILL 兜底，数据保留
open http://127.0.0.1:8096/

# 前置：共享 Docker 中间件（与保险系统公用）；run.sh 默认 mysql 模式，3306 不可达会报错并提示
"$HOME/.docker/bin/docker" compose -f ../ops-pro/deploy/middleware-compose.yml up -d
mysql -h127.0.0.1 -ucspro -pcspro-dev-2026 cs_pro < db/schema-mysql.sql
./stop.sh && ./migrate-to-mysql.sh        # 存量 H2 → MySQL（只读源库 + 行数校验，可选）

# 可选：向量混合检索（Qdrant 6333 + 本地 ONNX embedding 8100，同 claim-shared 栈；不可达自动降级纯 BM25）
./deploy/embedding/download-model.sh      # 首次：ModelScope 预下载模型（int8 24MB，gitignored）
"$HOME/.docker/bin/docker" compose -f deploy/vector-compose.yml up -d --build
CS_VECTOR_ENABLED=false ./run.sh          # 整体关闭向量通道

CS_MQ_ENABLED=true ./run.sh --no-build    # RocketMQ 可选（跨实例 SSE + 审计异步）
CS_PORT=8099 CS_MQ_ENABLED=true ./run.sh --no-build   # 第二实例
CS_DB_MODE=h2 ./run.sh                    # 零依赖 H2 模式（无需 Docker）

# 可选：payment-pro OpenAPI 入驻（pp 查询/订阅支付）
./integrate-payment.sh
```

- ride/payment 未启动时 cs-pro 照常启动（业务查询降级，健康页标注）。MQ 不可用自动降级直写，不影响主链路。
- **MySQL profile 不自动执行 schema**（sql.init.mode=never）：schema 变更须手动 ALTER 运行库并同步 db/schema-mysql.sql；H2 侧幂等 ALTER 自动生效。
- 日志 `logs/cs-gateway-<port>.log` 含 traceId。LLM 判读：`grep 'LLM OK'`＝真实调用成功；`grep 'LLM tool'`＝工具留痕；`grep 'LLM 降级'`＝回落 FAQ；`grep '熔断'`＝熔断。与 cs_llm_call 审计一一对应。
- LLM 配置：复制 `config/llm.properties.example` 为 `~/.config/cs-pro/llm.properties` 填入 Key（仓库外、不入库）。
- CI：`.github/workflows/ci.yml`（temurin 17 + mvnw package + jar 工件）。

## 10. 验证方案（已执行）

| 层 | 内容 |
|---|---|
| 单测 | 48 项：KB 打分、工单 CAS、幂等并发、身份注入/越权、LLM 降级熔断、订阅全场景、**商用特性**（open-key 生命周期/冻结拒绝/会话凭证/老会话兼容/账单聚合幂等/非法账期/审计/metrics） |
| REST 冒烟 | smoke-test.py **86 项** × 双实例（8096/8099，MySQL+MQ）：全闭环 + 鉴权 401/403 + 限次超额 + 幂等 + 冻结准入 + 管理列表分页契约 |
| LLM 评测 | llm-eval.py **17 项**语义断言（真实 DeepSeek，独立评测租户自动开通）：钱包/订单/计价/多轮记忆/拒代办/越权/流水/接管全链路 |
| 商用 live | F 批 26 项 + 令牌轮换 4 项：开放 API/会话凭证/账单/审计/metrics/健康全过 |
| 浏览器实测 | 五页面全流程（SSE 打字机/图片上传/工单抽屉/看板/健康页轮询） |
| 迁移与多实例 | 752 行迁移行数校验一致；跨实例 SSE 推送实测到达 |

## 11. 已知边界与演进

ID 直查 → 统一登录；工单异步 → 在线人工转接（WS）；开放 API → HMAC 签名 + 限流；熔断/配额/预算进程内 → Redis 外置全局（共享栈已有容器）；账单演示口径 → 真实出账；pp 联查需 pp 订单号 → ride 侧只读映射端点（侵入式改动届时单独确认）。

## 12. 待拍板事项

1. **LLM 供应商与 Key**：已接 DeepSeek（deepseek-flash），Key 在 `~/.config/cs-pro/llm.properties`（仓库外）；换供应商改 base-url/model 即可，备用端点可配第二供应商。无 Key 自动降级不变。
2. 工单处理人本期用文本 assignee（无客服账号体系）。
3. 端口 8096（+8099 第二实例）已确认；商用中间件与保险系统公用 Docker 栈已拍板。
4. 种子 FAQ K31 永久保留作 KB 版本化演示数据（用户拍板）。

## 13. 目录结构

```
cs-pro/
├── pom.xml / mvnw / .mvn/     # Maven 多模块 + wrapper
├── run.sh / stop.sh           # 一键启停（CS_PORT/CS_DB_MODE/CS_MQ_ENABLED/ADMIN_TOKEN）
├── smoke-test.py / llm-eval.py # REST 冒烟（86 项）/ LLM 行为评测集（17 项）
├── migrate-to-mysql.sh / tools/ # H2 → MySQL 迁移（只读源库 + 行数校验）
├── integrate-payment.sh       # payment-pro OpenAPI 一键入驻（可选）
├── db/schema-mysql.sql        # MySQL 建表（幂等；商用模式手动执行）
├── .github/workflows/ci.yml   # CI：构建 + 单测 + jar 工件
├── DESIGN.md                  # 完整设计（权威）
├── README.md                  # 本文档（与 DESIGN 同步）
├── UPGRADE-v1.2.0.md          # v1.2.0 实施流程与验证记录（含 v1.1.0 链接）
├── COLLABORATION.md           # 用户指导 AI 开发要点复盘（新会话先读）
├── config/                    # llm.properties.example（实际 Key 配置放仓库外 ~/.config/cs-pro/）
├── cs-common / cs-facade / cs-kb / cs-llm / cs-core / cs-gateway
└── data/ · logs/ · run/       # 运行时产物（gitignore，数据只增不删）
```