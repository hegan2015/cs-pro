# cs-pro v1.2.0 更新说明（商用底座 M0–M4 + 特性批 A–F）

日期：2026-11-18 ｜ 基线：v1.1.0 ｜ 状态：全部落地并实测通过
范围：**M0–M4 商用底座**（Docker 共享中间件 / MySQL 双模持久层 / H2 迁移工具 / RocketMQ 事件总线 / 多实例）+ **A–F 六个特性批**（LLM 治理 / 并发隔离 / 检索增强 / 知识运营 / 多模态与主动服务 / 商用缺口轻量版）。

验证结论速览：`./mvnw -o package` **48/48** 单测绿；REST 冒烟 **62/62 × 双实例**（8096/8099，MySQL+MQ profile）；LLM 评测集 **17/17**（真实 DeepSeek，独立评测租户）；F 批 live 验证 **26 项 + 令牌轮换 4 项**全过；health.html 浏览器实测（组件徽章 + 5s 轮询 + metrics 预览）。

---

## 0. 升级一份老部署怎么操作（TL;DR）

**默认模式不变**：H2 文件库 + 单进程，`./stop.sh && ./run.sh` 即完成升级（schema.sql 尾部幂等 ALTER 自动补列建表）。`data/` 只增不删。

**商用模式（可选，MySQL + RocketMQ + 多实例）**：

```bash
# 1) 中间件：与保险理赔系统公用同一套 Docker 栈（已建好则跳过）
"$HOME/.docker/bin/docker" compose -f ../ops-pro/deploy/middleware-compose.yml up -d
# 2) 建库建表（幂等，可重复执行）
mysql -h127.0.0.1 -ucspro -pcspro-dev-2026 cs_pro < db/schema-mysql.sql
# 3) 存量数据迁移（可选；先停服务，H2 只读打开，原库不动）
./stop.sh && ./migrate-to-mysql.sh
# 4) 商用模式启动（可多实例，端口错开即可）
CS_DB_MODE=mysql CS_MQ_ENABLED=true ./run.sh --no-build                 # 8096
CS_PORT=8099 CS_DB_MODE=mysql CS_MQ_ENABLED=true ./run.sh --no-build    # 第二实例
# 5) 回归
python3 smoke-test.py                    # 62 项
python3 llm-eval.py                      # 17 项（需 LLM Key + ride-pro 在跑）
```

**注意**：MySQL profile 下 `sql.init.mode=never`——后续版本若再改 schema，须手动对运行库执行 ALTER 并同步 `db/schema-mysql.sql`（H2 侧仍靠幂等 ALTER 自动生效）。

新增配置全部有默认值，不改配置也能直接升级。新键见 §12 与 `config/llm.properties.example`。

---

## 1. M0 商用中间件：Docker 共享栈（与保险系统公用）

按用户拍板「数据库用 MySQL、消息用 RocketMQ，中间件和保险系统公用，用 Docker 管理」：

- 复用 **ops-pro/deploy/middleware-compose.yml** 的 `claim-shared-*` 容器栈（10 容器：mysql / redis / zookeeper / rocketmq-namesrv / broker / dashboard / temporal / temporal-ui / temporal-db / minio），不新建第二套。
- MySQL 8（127.0.0.1:3306）：库 `cs_pro`，账号 `cspro/cspro-dev-2026`（演示口令，生产走环境变量 `CS_MYSQL_*` 覆盖）。
- RocketMQ 5.1.4（namesrv 127.0.0.1:9876）：cs-pro 独占两个 topic（`cs_sse_event` / `cs_llm_audit`），与保险系统 topic 隔离。
- 端口全表错开（ride 8090 系 / payment 8080 系 / yiwu 8095 / cs 8096+8099 / claim 8097 / ops 8098），六系统 + 中间件可同时运行。

## 2. M1 持久层双模（H2 / MySQL）

- `CS_DB_MODE=mysql` → run.sh 附加 `--spring.profiles.active=mysql`；`application-mysql.yml` 指向共享 MySQL，`sql.init.mode=never`（建表走 db/schema-mysql.sql）。
- **ID 生成方言适配**：IdGenerator 白名单 + 双方言——H2 用 `SEQUENCE`，MySQL 用 `cs_sequence` 号表 `UPDATE ... SET next_val=LAST_INSERT_ID(next_val+1)` 原子取号；新增业务序列须同时改白名单与三份 schema（生产 H2 / 测试 H2 / MySQL）。
- **SQL 双模兼容**：upsert 统一 `ON DUPLICATE KEY UPDATE`（H2 MODE=MySQL 支持）；聚合查询返回 **DTO 而非 Map**（规避 H2 大写列名 / MySQL 原样别名差异）；时间 BIGINT 毫秒、金额 BIGINT 分口径不变。
- `db/schema-mysql.sql` 与 H2 schema 等价且全量 `IF NOT EXISTS` 幂等。

## 3. M2 迁移工具（H2 → MySQL）

`./migrate-to-mysql.sh` + `tools/MigrateToMysql.java`（零依赖，直接用本地 Maven 仓库里的 H2/MySQL JDBC 驱动）：

- 前置检查：cs-gateway 必须在停（H2 文件独占锁）；`FORCE=1` 仅用于源是显式副本的场景。
- **H2 全程只读打开，绝不改原库**；逐表搬运 + 行数校验输出。实测存量 **752 行**全量合入容器 MySQL，行数一致。
- 序列同步：H2 各 SEQUENCE 当前值写入 `cs_sequence`，迁移后 ID 不回头。

## 4. M3 RocketMQ 事件总线（可降级）

`CS_MQ_ENABLED=true` → mq profile（`application-mq.yml`）：

- **SSE 跨实例广播**：会话事件发 `cs_sse_event` topic，所有实例消费后推给本机持有的 SseEmitter——用户连 A 实例、客服在 B 实例回复也能实时收到。
- **LLM 审计异步落库**：审计行发 `cs_llm_audit` topic 异步写；**发送/消费失败自动降级直写**，MQ 宕机不丢审计、不影响对话主链路。
- 降级开关：不启用 mq profile 时一切走进程内直连（v1.1 行为），`cs.mq.enabled` 健康页可见。

## 5. M4 多实例验证

8096 + 8099 双 cs-gateway（同库同 MQ）实测：跨实例 SSE 推送到达、双实例各自冒烟 62/62、共享 MySQL 数据一致。熔断器/预算计数仍为进程内（见 §15 边界）。

## 6. A 批：LLM 层治理

- **分级 max-tokens**：查询类意图（计价/退款/提现/支付/账号）与工具轮用 `max-tokens-query=512`，投诉/开放类最终回答用 `max-tokens-report=2048`。
- **断流标注**：流式输出中途失败的消息带标注，不伪装完整回答。
- **备用路由链**：`base-url-backup/model-backup/api-key-backup`——主端点重试耗尽且未出流时自动切备用端点（留空=不启用）。
- **promptVersion**：`cs.llm.prompt-version=v1.2.0` 随每次调用落 `cs_llm_call.prompt_version`，提示词改版可做效果对比。
- **注入防护 + 输出脱敏**：system 防注入声明强化；`mask-pii=true` 对 LLM 生成内容打码手机号/身份证/银行卡号。
- **intent 派生**：会话意图标签由问答内容派生落库，供分级与统计。

## 7. B 批：并发隔离与租户配额

- LLM 调用走**专用线程池**（与 Web 容器线程隔离，慢调用不拖垮 REST）。
- **每租户并发配额** `cs.chat.tenant-concurrency=2`（套餐可覆写，见 F3）：信号量计数，超限**立即 429**（不排队堆积），释放于 finally。

## 8. C 批：检索增强

- **RAG 查询改写**：当前句 + 上一轮用户问题做轻量指代消解（`rewriteQuery`，**0 次额外 LLM 调用**），"那退款呢"能接住上文。
- **TopK 短 TTL 缓存**：`cs.kb.cache-ttl-ms=30000`，KB 任何变更即时失效重建。
- **历史滚动摘要**：字符预算内的近期轮次保留原文 + 工具轨迹；预算外更早轮次**每轮取首句本地压缩**成一条 assistant 摘要（封顶 600 字符，零 LLM 调用），长会话不再无上限膨胀。

## 9. D 批：知识运营闭环

- **坏例队列**：差评/降级会话自动入队（原因 + 最近问答摘要 + 引用条目），`GET /api/admin/badcases` 供运营改进 KB。
- **KB 版本化/回滚**：每次改写前旧版快照入 `cs_kb_revision`；`GET /kb/{id}/versions` + `POST /kb/{id}/rollback/{revisionId}`。种子条目 **K31 按用户拍板永久保留**作版本化演示数据。
- **引用解决率**：`cs_kb.cited_count / cited_resolved_count`——引用该条目的会话最终解决（非转工单/差评）比例，`GET /kb/citation-stats`，回答"哪条 FAQ 真正管用"。

## 10. E 批：多模态 + 多语言 + 主动服务

- **图片消息/图片工单**：`POST /api/uploads`（≤5MB，存 `data/uploads/`）；附图消息改用 `cs.llm.vision-model`（留空沿用主模型），请求体 `parts=[text, image_url(base64)]`；工单可带附件，工作台缩略图可点开。mock 断言验证 vision 覆盖与 parts 结构，真实 LLM 图片问答 OK。
- **多语言跟随**：英文提问英文作答（system 规则 + 实测）。
- **主动服务事件轮询**：`cs.proactive.enabled=true`，默认 60s 轮询 ride 超时订单 / payment 失败支付单（内部只读 API，`CS_PP_POLL_BASE_URL`）；`cs_proactive_event.event_key` 唯一键幂等——同一异常只推一次；已有会话复用、无会话自动建；上游未跑自动跳过。管理端 `GET /proactive-events` 留痕。

## 11. F 批：商用缺口轻量版（六项）

**F1 开放 API 租户凭证**：管理端 `POST /open-keys {tenantCode}` 生成 `ok-<32hex>` + secret；`OpenKeyAuthFilter` 拦 `/api/open/**` 校验 `X-Open-Key`（无效 401 / 租户冻结 403 / 吊销 CAS）。开放端点：`GET /api/open/usage`（订阅状态 + 今日 token）、`/sessions`（分页，**剥离 accessToken**）、`/invoices`。

**F2 会话访问凭证**：建会话一次性下发 `accessToken`（SecureRandom 24 字节→48hex），此后消息读写/end/human 须 `X-Session-Token`；SSE 因 EventSource 无法设自定义头走 `?token=` query。**详情/列表接口剥离 token**；存量无 token 老会话放行（兼容）。无 token/错 token 403。

**F3 套餐目录 + 月度账单**：`cs.plans` 三档——free（FAQ 限次）/ standard（¥99/30天，token 10分/千，日预算 30 万，并发 2）/ pro（¥299，8分/千，日预算 200 万，并发 8）；激活码/订阅带 `plan_code`。`POST /invoices/generate {tenantCode, period=YYYY-MM}` 聚合该租户当月 **status=OK** 调用 token，按套餐单价**向上取整**计费，`uk_invoice(tenant_id, period)` + upsert **重算幂等同单**；非法账期拒绝。`GET /plans`、`GET /invoices`。

**F4 管理审计留存 + 令牌轮换**：`cs_admin_audit` 记录全部管理变更操作（租户/激活码/开通/KB 增删改回滚/工单流转/接管交还/open-key/出账/播种），旁路写入不阻断主流程，detail 截断 1024；`GET /audit-log?action=&actor=&from=&to=` 分页查询。**ADMIN_TOKEN 支持逗号分隔多令牌**——轮换窗口新旧并行，实测旧/新令牌均 200、未配置 401。

**F5 Prometheus metrics**：`GET /api/admin/metrics`（text/plain; version=0.0.4，需管理令牌）：会话/消息/工单总量与活跃、LLM 调用按状态（OK/FALLBACK/BUDGET/ABORTED）、token 总量、活跃 SSE 连接、LLM 配置/可用/熔断状态（按端点）、MQ 激活、主动服务开关。

**F6 健康页 + CI**：`health.html` 组件徽章表（服务/数据库模式/RocketMQ/LLM/ride·payment 上游/主动服务）5s 轮询 `GET /api/admin/health` + metrics 文本预览；run.sh 就绪输出健康页 URL。`.github/workflows/ci.yml`：checkout@v4 + setup-java@v4（temurin 17，maven 缓存）+ `./mvnw -B -ntp package` + 上传 cs-gateway.jar 工件。

## 12. 契约与代码变更清单

**新增接口**：`/api/open/usage|sessions|invoices`（X-Open-Key）；`/api/uploads`；管理端 `/audit-log`、`/plans`、`/invoices`、`/invoices/generate`、`/open-keys`（POST/GET）、`/open-keys/{key}/revoke`、`/metrics`、`/health`、`/kb/{id}/versions`、`/kb/{id}/rollback/{revisionId}`、`/kb/citation-stats`、`/badcases`、`/proactive-events`。

**响应字段**：SessionDTO+`accessToken`（仅建会话返回）；SubscriptionDTO/ActivationCodeDTO+`planCode`；MessageDTO/TicketDTO+`attachments`；LlmCallDTO+`promptVersion`。错误码 +A0401（401）/A0403（403）。

**schema 增量**（H2 幂等 ALTER 自动生效；MySQL 手动执行）：`cs_llm_call.prompt_version`、`cs_kb.cited_count/cited_resolved_count`、`cs_message.attachments`、`cs_ticket.attachments`、`cs_session.access_token`、`cs_subscription.plan_code`、`cs_activation_code.plan_code`；新表 `cs_kb_revision`、`cs_open_key`、`cs_invoice`、`cs_admin_audit`、`cs_proactive_event`；新序列 `seq_invoice`（MySQL 号表 +('invoice',0)）。

**配置新键**：`cs.llm.max-tokens-query/-report`、`base-url-backup/model-backup/api-key-backup`、`vision-model`、`prompt-version`、`mask-pii`；`cs.chat.tenant-concurrency`、`cs.kb.cache-ttl-ms`、`cs.proactive.*`、`cs.pp.poll-base-url`、`cs.plans.*`；环境变量 `CS_DB_MODE`、`CS_MQ_ENABLED`、`CS_MYSQL_URL/USER/PASSWORD`、`CS_MQ_NAMESERVER`、`ADMIN_TOKEN`（多令牌）。

**脚本适配**：smoke-test.py（62 项：+会话凭证断言，全部会话调用带 X-Session-Token）；llm-eval.py（自动创建独立评测租户 + 手工开通订阅，避开其他租户当日预算；会话凭证自动注入）。

## 13. 全量验证记录（2026-11-18）

| 项 | 结果 |
|---|---|
| `./mvnw -o package` 单测 | **48/48**（kb 7 + llm 2 + core 39：ChatService 19 + CommercialFeatures 8 + SubscriptionPay 2 + Ticket 4 + Subscription 6）BUILD SUCCESS |
| REST 冒烟（8096，MySQL+MQ） | **62/62** |
| REST 冒烟（8099，MySQL+MQ） | **62/62** |
| LLM 评测 llm-eval.py（真实 DeepSeek） | **17/17**（独立评测租户 EVAL-4da5ea51，含多轮记忆/越权/接管全链路） |
| F1 开放 API live | 生成/usage/sessions 分页+token 剥离/invoices/无 key 401/无效 401/吊销后 401 ✓ |
| F2 会话凭证 live | 建会话带 token/无 token 403/错 token 403/正确 200/详情剥离 ✓ |
| F3 账单 live | plans 三档/ride-pro 2026-11 出账（calls>0 amount>0）/重算幂等同单/非法账期拒绝/列表 ✓ |
| F4 审计+轮换 live | audit-log 含 OPENKEY_CREATE/INVOICE_GENERATE、总量≥10；8099 双令牌重启旧/新均 200、未配置 401 ✓ |
| F5 metrics live | text/plain 0.0.4 + 全部指标族 ✓ |
| F6 健康页 | API 结构（db=mysql/mq=active/upstream）✓；浏览器 health.html 徽章渲染 + 6.5s 内 1 次轮询 + metrics 预览 ✓ |
| E 批 live（前序） | 多语言英文问答 ✓ / 真实 LLM 图片 OK ✓ / mock vision parts 断言 ✓ / 主动服务 2 事件幂等 ✓ |
| M2 迁移 | 752 行全量合入，行数校验一致，H2 原库未动 |
| M4 多实例 | 跨实例 SSE 到达 ✓ |
| 数据保护 | 全程未删改 `data/` 与 H2 库；预算耗尽租户不重置，改用独立租户 |

## 14. 踩坑与修复（供后续维护者）

1. **rebuild 前必须先停运行实例**：`mvnw package` 覆盖 jar 会让运行中 JVM 的类加载变僵尸（NoClassDefFound 随机炸）。流程固定：stop → package → run。
2. **MySQL profile 不自动建表**：`sql.init.mode=never`，schema 变更须手动 ALTER 运行库 + 同步 db/schema-mysql.sql，否则启动报表/列不存在。
3. **EventSource 设不了自定义头**：SSE 凭证只能走 `?token=` query；其余会话端点走 X-Session-Token 头。smoke/eval/前端三处都要适配，漏一处即 403。
4. **JUnit `assertEquals(int, Long)` 装箱陷阱**：Integer≠Long 恒不等，断言期望值用 long 字面量（15L）。
5. **聚合查询返回 Map 的方言坑**：H2 列名大写、MySQL 原样别名——统一返回 DTO（Bean 映射列名大小写不敏感）。
6. **评测租户预算**：ride-pro 当日 token 预算耗尽（used=309982/300000）时不重置数据——llm-eval 改建独立 EVAL 租户 + 管理端手工开通，预算按租户隔离天然归零。
7. **JDK 版本**：系统默认 JDK 过新会杀 Lombok，构建固定 JAVA_HOME 指向 corretto-18（run.sh/mvnw 已内置回退逻辑）。

## 15. 明确没做的（边界）

- 熔断器/并发配额/预算计数仍在**进程内**：多实例下各算各的（演示可接受；严格全局限流需外置 Redis，栈里已有容器可接）。
- 账单为**演示口径**（OK 调用 token × 套餐单价向上取整），未接真实出账/发票/税务。
- 开放 API 只读三端点，未做 rate-limit 与 secret 签名（secret 已生成预留，HMAC 签名是下一步）。
- CI 仅构建 + 单测 + 工件，未接部署流水线。
- 向量检索/分词器升级、WebSocket、统一登录：沿用 v1.1 边界。