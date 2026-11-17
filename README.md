# cs-pro 智能客服系统设计文档

版本 v0.3（实现中）｜ 2026-11-17 ｜ 完整设计见 [DESIGN.md](DESIGN.md)（字段级数据字典、接口契约、安全细节以 DESIGN 为准）

定位：为 ride-pro（打车）与 payment-pro（支付）提供**统一智能客服入口**，覆盖「租户订阅准入 → 机器人自动问答（LLM）→ 业务只读查询 → 工单与统计」闭环。

> 状态说明：技术选型与订阅模型已拍板（DESIGN §1.4/§1.5），代码实现中；本文档与 DESIGN 同步维护。LLM 供应商与 Key 待用户提供，未提供前按**无 Key 降级模式**实现与验证（FAQ 直答 + 工单/统计/查询全闭环可用）。

## 1. 目标与硬约束

| 目标 | 说明 |
|---|---|
| 机器人自动问答 | LLM API + FAQ 知识库（BM25 RAG）+ 业务数据（Function Calling） |
| 业务查询 | 对话中直查 ride 订单/钱包/流水、payment 交易/账户，**只读不写** |
| 工单与统计 | 机器人解决不了 → 工单流转；会话/解决率/满意度/LLM 用量报表 |
| 租户订阅 | 商户/租户级订阅准入：免费 FAQ 限次 + 订阅解锁全功能 |

硬约束（对齐 ride/payment 两系统惯例）：

- **零外部部署依赖**：Java 17 + Spring Boot 3.2 + Maven + H2 文件库，`./run.sh` 一键启动；LLM 是唯一外部调用且有完整降级。
- **数据保护**：`data/` 只增不删；停服务不触碰数据。
- **零侵入**：ride/payment 不改一行代码、零 schema 改动；cs-pro 宕机对两系统零影响。
- **资金安全边界**：对业务系统默认只读；**唯一例外**是订阅购买经批准调用 payment-pro `trade.create/trade.pay`（金额服务端固定，LLM 工具层永不触达写端点）。
- **端口隔离**：cs-gateway **8096**（原拟 8095 已被 yiwu-pro 占用），与 ride（8090/2182/8093/2090x/9101）、payment（8080/2181/8092/2088x/8443）完全错开，三系统可同时运行。`CS_PORT`/`DATA_DIR`/`ADMIN_TOKEN` 环境变量可覆盖。

## 2. 技术选型（已拍板）

| 选型项 | 决议 |
|---|---|
| 架构形态 | 单进程 Spring Boot + REST 集成（内部模块化，不引 Dubbo/ZK） |
| LLM 接入 | OpenAI 兼容协议统一客户端（JDK HttpClient，不引第三方 SDK），适配 OpenAI/DeepSeek/通义/Kimi/Ollama |
| 知识库检索 | 本地 BM25 倒排（中文 n-gram 分词，进程内索引，零依赖） |
| payment 查询通道 | 8443 OpenAPI HMAC 验签（独立注册 cs-pro 商户，自签证书定向信任） |
| 实时推送 | SSE 单向流（token 级打字机 + 工具状态事件） |
| 存储 | H2 单文件库 `data/cs.mv.db`（MODE=MySQL，SQL 按 MySQL 方言便于迁移） |

## 3. 架构与模块

```
 用户咨询 H5 ──HTTP/SSE──┐
 客服工作台 H5 ──HTTP────┼── cs-gateway :8096（REST + SSE + 静态页 + 管理鉴权）
 管理看板 H5 ──HTTP──────┘        │
                          cs-core（进程内模块）
                          chat 对话编排 / ticket 工单 / stats 统计
                          llm 适配层 / kb 知识库 / biz 业务查询适配器
                                  │ 只读
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                   ▼
    ride-gateway :8090   payment-gateway :8443   LLM API（外部）
    订单/钱包/流水/计价    OpenAPI HTTPS+HMAC      OpenAI 兼容协议
```

Maven 六模块（单运行进程 cs-gateway）：

| 模块 | 职责 |
|---|---|
| cs-common | Result/错误码/BizException/MoneyUtil(分)/TraceFilter（沿用两系统契约风格） |
| cs-facade | DTO 契约（进程内引用，预留拆进程边界） |
| cs-kb | FAQ 知识库 + BM25 检索 |
| cs-llm | OpenAI 兼容客户端 + 工具调用协议 + 降级熔断 |
| cs-core | 对话编排 / 工单 / 统计 / 订阅 / biz 查询适配器 |
| cs-gateway | REST + SSE + H5 静态托管 + 管理鉴权（唯一运行进程） |

技术栈：Spring Boot 3.2.5、MyBatis Starter 3.0.3、H2 2.2.224、JDK 17、Lombok。

## 4. 核心功能

### 4.1 一轮对话链路
用户消息落库 → 组装上下文（system prompt + 最近 10 轮 + KB Top3 命中）→ LLM 携带工具定义调用 → tool_calls 执行循环（最多 3 轮防死循环，biz 适配器只读查询）→ 机器人消息落库（含引用来源）→ SSE 流式推送 → 意图标签落库。

### 4.2 Function Calling 工具（全部只读，白名单硬编码）
`query_ride_order` / `list_ride_orders` / `query_wallet` / `query_ledger` / `query_withdraws` / `pricing_rules`（ride REST）；`query_pp_trade` / `query_pp_balance`（payment OpenAPI 验签）；`search_kb`（本地）；`create_ticket`（**返回草稿卡片，用户确认才落单**，防幻觉建单）。

工具安全三原则：身份参数服务端注入（LLM 不可决定）+ orderId 归属校验（防越权，不匹配返回 A0404 不泄漏存在性）；无写工具；结果脱敏后进 prompt（手机号掩码、卡号前 6 后 4）。

### 4.3 知识库 RAG（轻量零依赖）
中文 n-gram 倒排 + BM25 打分，Top3 注入 system prompt；KB 变更即重建索引。种子 FAQ 从两系统业务规则提炼（取消费、免密扣款、提现冻结语义、退款手续费比例等高频问题）。演进方向：embedding 向量检索。

### 4.4 工单生命周期
`OPEN → ASSIGNED → PROCESSING → RESOLVED → CLOSED`（用户确认或 7 天自动关闭），任意非终态可 CANCELLED。全部流转 CAS；建单幂等 `request_id` 唯一键（LLM 用确定性键 `TICKET-{sessionId}-{seq}`）；携带会话快照/关联订单/分类/优先级/来源；满意度 1–5 星（PK 防重）。

### 4.5 统计报表
会话量/消息量、机器人解决率、转工单率与分类分布、平均响应时长、满意度、高频问题 TopN、LLM 用量（token/调用/失败/降级，cs_llm_call 留痕）。SQL 聚合 + stats_daily 每日快照（调度 MERGE 幂等），看板读快照 + 实时增量。

## 5. 订阅域（租户级准入）

- **租户**：cs_tenant（code 唯一，种子内置 ride-pro/payment-pro），管理端创建/冻结；会话按 tenantCode 绑定（缺省按角色映射），权益归租户、其名下用户共享。
- **门槛矩阵（服务端每次请求实时判定 expire_at > now）**：

| 能力 | 未订阅/已过期 | 订阅有效 |
|---|---|---|
| FAQ 直答 | ✅ 每日限次（默认 5 条/用户/日，超额 A0409 + 订阅引导） | ✅ 不限次 |
| LLM 智能问答 / 业务查询 | ❌ | ✅ |
| 工单创建/查询 | ✅（求助通道不付费墙） | ✅ |

- **套餐**：单套餐 + 有效期（默认 30 天 ¥99，可配）；续订天数叠加 `newExpire = max(now, expire_at) + days`。
- **激活码兑换（默认通道，零依赖）**：管理端批量生成 `CS-XXXX-XXXX`；兑换单事务 CAS 一码一用（并发仅一胜）；管理端可手工开通（source=ADMIN 留痕）。
- **payment-pro 支付开通（可选）**：未入驻时订阅页仅激活码。订阅单状态机 `PENDING → PAID → ACTIVATED（终态）/ FAILED / CLOSED（超 30min）`；金额=套餐配置价**服务端固定**；**服务端 5s 调度回查 trade.query 兜底收敛终态**，前端轮询仅加速展示；开通 CAS + request_id 唯一键，调度/轮询/重启重放不重复加天数。
- 免费额度 `cs_free_usage`（PK=user_id+day）MERGE + CAS count<quota 原子自增，并发不超发。

## 6. LLM 适配与降级

- 配置 `config/llm.properties`（baseUrl/apiKey/model/temperature/maxTokens/timeoutMs，**gitignore，Key 不入日志/DTO**）。
- 流式：工具调用轮非流式（简化解析），最终回答轮流式 SSE 转发。
- **降级策略（无 Key 系统照常运行）**：未配置 → FAQ 检索直答 + 相似问题；超时/5xx → 重试 1 次 → FAQ 兜底提示；工具失败 → 错误说明回填 LLM 引导；连续失败 ≥5 → 熔断 60s（cs_llm_call 记 FALLBACK）。
- Prompt 治理：行为边界（不承诺退款、不编造数据、资金操作引导官方端）+ 防注入声明；**工具白名单 + 身份服务端注入是硬防线，prompt 是软防线**。

## 7. 接口与前端

REST 契约详见 DESIGN §6。成功 body 为 data，失败 `{error, code}`（A0400/A0404/A0409/B0001/B0002/C0001 三段式）。

- 用户端：会话/消息/SSE 流/评价/工单/KB 检索/订阅状态/激活码兑换/支付开通。
- 管理端（`/api/admin/*`，X-Admin-Token，默认 cs-admin）：总览/趋势/会话审计/工单池流转/FAQ 管理/LLM 审计/健康探活/播种/租户管理/激活码管理/订阅单审计。

前端四页面（零构建 H5，网关静态托管，共享 common.css/common.js 移植 ride 组件库风格）：

| 页面 | 内容 |
|---|---|
| subscription.html | 订阅状态卡 + 套餐权益对比 + 激活码兑换 + 支付开通（payEnabled 时） |
| chat.html | 身份选择 + 订阅状态条 + SSE 打字机对话 + 引用来源 + 一键建单 + 满意度 |
| agent.html | 工单池筛选分页 + 详情抽屉（会话快照/处理时间线）+ 分派/处理/解决 |
| admin.html | 统计看板 + FAQ 管理 + LLM 审计 + 租户/激活码/订阅单管理 |

## 8. 安全与正确性要点

1. **资金边界**：无任何写业务系统能力（订阅支付除外且服务端常量封装）；被问退款只解释规则 + 引导。
2. **越权防护**：会话身份绑定 + 列表查询服务端注入 ID + 订单归属校验。
3. **LLM 建单需用户确认**（草稿卡片），防幻觉/循环建单。
4. **幂等**：建单 request_id 唯一键 + 快路径回放；评价 PK 防重；消息先落库再调 LLM（失败不丢消息）；调度 CAS 重启不丢进度。
5. **订阅准入服务端强制**：实时判定、CAS 原子计数、一码一用、开通幂等、支付终态调度收敛。
6. **敏感信息**：apiKey 不入日志；进 prompt 数据脱敏；落库原文供审计、管理端展示脱敏。

## 9. 部署与运维

```bash
cd cs-pro
./run.sh                # mvnw package + 启动 cs-gateway + 就绪等待（8096）
./run.sh --no-build
./stop.sh               # SIGTERM + 15s + SIGKILL 兜底，数据保留
open http://127.0.0.1:8096/

# 可选：payment-pro OpenAPI 入驻（pp 查询/订阅支付）
# 先启动 payment-pro，再：
./integrate-payment.sh  # 注册 cs-pro 商户/取 appId+appSecret/拷证书/写配置 → 重启生效
```

- ride/payment 未启动时 cs-pro 照常启动（业务查询降级，健康页标红）。
- 日志 `logs/cs-gateway.log` 含 traceId（透传 X-Trace-Id 可跨系统检索）。
- LLM 配置：复制 `config/llm.properties.example` 为 `config/llm.properties` 填入 Key（不入库）。

## 10. 验证方案（实现阶段执行）

| 层 | 内容 |
|---|---|
| 单测 | KB 打分、工单 CAS、建单幂等并发、身份注入/越权拒绝、LLM 降级熔断、订阅（兑换并发仅一胜/到期判定/额度不超发/天数叠加/开通幂等） |
| REST 冒烟 | 无 Key 全闭环：开会话→FAQ 直答→建单→分派→解决→关闭→评价→统计；鉴权 401；越权 404；订阅限次→超额 409→兑换解锁→重复兑换 409 |
| LLM 联调（有 Key） | 真实问答 + 工具查单 + 拒绝代办退款 + 断网兜底 |
| 集成实测 | 三系统同跑；pp 验签查单/错签拒绝；停 ride 对话降级不中断 |
| 浏览器实测 | 四页面全流程（SSE 打字机/工具状态/工单抽屉/看板） |
| 混沌 | LLM 超时注入、业务系统宕机、cs 重启数据完整 |

## 11. 已知边界与演进

单进程 H2 → 拆进程 + MySQL；BM25 → embedding 向量检索；ID 直查 → 统一登录；工单异步 → 在线人工转接（WS）；LLM 单供应商 → 多供应商路由；只读客服 → 受控代办（独立授权审计）；pp 联查需 pp 订单号 → ride 侧只读映射端点（侵入式改动届时单独确认）。

## 12. 待拍板事项

1. **LLM 供应商与 Key**：用户提供后写入 `config/llm.properties`；未提供前按无 Key 降级模式实现与验证。
2. 工单处理人本期用文本 assignee（无客服账号体系）。
3. 端口 8096 已确认（8095 被 yiwu-pro 占用）。

## 13. 目录结构

```
cs-pro/
├── pom.xml / mvnw / .mvn/     # Maven 多模块 + wrapper
├── run.sh / stop.sh           # 一键启停
├── integrate-payment.sh       # payment-pro OpenAPI 一键入驻（可选）
├── DESIGN.md                  # 完整设计（权威）
├── README.md                  # 本文档（与 DESIGN 同步）
├── config/                    # llm.properties.example（实际配置 gitignore）
├── cs-common / cs-facade / cs-kb / cs-llm / cs-core / cs-gateway
└── data/ · logs/              # 运行时产物（gitignore，数据只增不删）
```