# cs-pro 协作指南 —— 用户指导 AI 开发要点复盘

> 整理日期：2026-11-18 ｜ 来源：跨会话真实协作记录复盘
> 用途：新会话开工前读本文件，快速对齐用户的工作方式与本项目已拍板的决策，避免重复试探和回退。

## 一、项目定位与硬约束

- **ride-pro/payment-pro 统一智能客服入口**：Java 17 + Spring Boot 3.2 六模块单进程，端口 **8096**（第二实例 8099；原拟 8095 被 yiwu-pro 占用）。**默认 Docker MySQL**（2026-11-18 用户拍板「h2改为mysql，docker里的」，run.sh 直连共享栈）；`CS_DB_MODE=h2` 保留零依赖形态；RocketMQ 可选（CS_MQ_ENABLED=true）。**向量混合检索**（同日拍板「引入向量库」）：共享栈追加 Qdrant（**6333**）+ cs-embedding（**8100**，本地 ONNX bge-small-zh int8），不可达自动降级纯 BM25（CS_VECTOR_ENABLED=false 可关）。
- **对 ride/payment 零侵入**：只读工具 9 个（身份服务端注入 + 归属校验，KB 走 RAG 非工具）；唯一写例外 = 订阅支付（金额服务端固定）。
- **数据神圣**：停服务可以，删除/重置 data/ 与 H2 库必须先经用户明确确认。
- **密钥出仓库**：DeepSeek Key 存 `~/.config/cs-pro/llm.properties`（目录 700/文件 600），spring.config.import 显式文件导入；仓库只留 .example。
- LLM 为纯配置字符串（OpenAI 兼容协议），换供应商只改 base-url + model；当前 **deepseek-flash**（官方路由 DeepSeek-V4.1-Flash-1017）。注意官方 ID ≠ 第三方 deepseek-v4.1-flash-1017，抄错会 404。

## 二、关键指导决策（已拍板，勿回退）

1. **租户订阅准入**：激活码首启随机生成 3 个、CAS 一码一用 + 可选 payment-pro 支付、服务端 5s 调度兜底。
2. **无 Key 降级 FAQ + 熔断**：LLM 不可用时业务不中断（曾 402 余额不足→降级生效实测）。
3. **DeepSeek 真实联调**（2026-11-17 用户充值后）：工具查钱包/订单（分→元正确）、计价问答、拒代办退款→工单草稿，零降级零熔断；同日用户要求切 **deepseek-flash**（零代码改动，3 场景实测通过）。
4. **v1.1.0 六项优化**（2026-11-18 交付，28/28 单测 + 59/59 smoke + 17/17 评测全过）：①多轮工具记忆（tool_trace + 历史预算 6000 字符）②租户日 token 预算护栏（只算 OK，BUDGET 审计+FAQ 降级）③审计 + per-endpoint 熔断（连续 5 败熔 60s）+ 分类退避重试（429/5xx/网络≤2 次；4xx/已流式不重试）+ 同轮多工具 8 线程并行④llm-eval.py golden 评测 17 项⑤人工接管（/human、排队、工作台接管/回复/交还、同状态幂等）⑥BM25 同义词扩展（12 组）。详见 UPGRADE-v1.1.0.md。
5. **工单状态机**：OPEN→PROCESSING→RESOLVED→CLOSED（无 ASSIGNED）；统计快照。
6. **仓库可见性**：用户要求 cs/yiwu 两库改 Public——**未完成**（浏览器 GitHub 登录态丢失 + 网络抖动），待登录态恢复后复核。
7. **git 听指令**：v1.1.0 + v1.2.0 全部改动于 2026-11-18 经用户明示「补充好文档然后提交」后一次性提交；此前多轮「勿擅自 commit」纪律保持不变。
8. **v1.2.0 商用化拍板（2026-11-18，用户原话）**：「按照商用的来，数据库用mysql，消息用Rockmq」+「跟保险系统我说的一样，中间件要和保险系统公用，用dock管理」→ 复用 ops-pro/deploy/middleware-compose.yml 的 claim-shared 容器栈（MySQL 8 / RocketMQ 5.1.4 / Redis / Temporal / MinIO 等 10 容器），不新建第二套；cs-pro 独占 cs_sse_event / cs_llm_audit 两 topic。**同日追加拍板「h2改为mysql，docker里的」→ run.sh 默认 CS_DB_MODE=mysql**（启动前自检 3306 可达），H2 降为可选（CS_DB_MODE=h2）；此前「默认 H2 保留」决策被此条取代。
9. **「/opt/mysql-data 也不要了」**：裸 mysqld 时代遗留数据目录经用户明示后删除（数据保护规则的唯一例外=用户明确确认）；迁移前快照留 ~/opt/cs_pro-dump-20261118.sql。
10. **「全部都做」**：A–G 七个特性批逐批实现逐批实测，不挑不拣——A LLM 治理 / B 并发隔离 / C 检索增强 / D 知识运营 / E 多模态+主动服务 / F 商用缺口轻量版 / G 全量验证+文档。
11. **K31 临时 FAQ 去留**：AskUserQuestion 给选项，用户选「保留」→ K31 永久留作 KB 版本化演示数据，不回滚不删除。**演示数据也是数据，处置先问**。
12. **v1.2.0 交付数字**（2026-11-18）：48/48 单测 + 86/86 冒烟×双实例 + 17/17 真实 LLM 评测 + F live 30 项 + 浏览器实测；详见 UPGRADE-v1.2.0.md。
13. **分页补齐（2026-11-18，用户指令「给我检查一下分页 包括邀请码，审计等等」）**：排查发现后端仅 sessions/tickets/audit-log 有真分页、前端零分页控件（codes/orders 靠 slice 假分页）、F 批新域无 UI → 后端 9 端点统一 {total,list}+page/size（codes/llm-calls/sub-orders/open-keys/invoices/tenants/subscriptions/badcases/proactive-events），前端 CS.pager 组件 + admin.html 7 页签（补审计与坏例/开放与账单）+ agent.html 工单池/坏例翻页；smoke 62→86 项（新增分页契约断言）；浏览器隐藏 iframe 实测激活码 137 条 14 页翻页无重复。**契约变更须同批改 smoke+前端**（llm-calls 裸 list→{total,list}）。
14. **向量库拍板（2026-11-18，用户指令「引入向量库」+ 追问「本地onnx能放到docker里吗」）**：AskUserQuestion 选 **Qdrant 单容器**；embedding 独立轻量容器（FastAPI+ONNX Runtime+bge-small-zh-v1.5 int8，512 维，模型烘进镜像，双实例共享）。BM25+向量 **RRF 融合（k=60）**，直答双阈值（BM25≥1.0 或 cosine≥0.65 实测校准）；**旁路降级**（容器不可达→纯 BM25+30s 熔断），取代原「不引入向量库」边界。模型下载走 **ModelScope**（hf-mirror 容器内 403/限速全踩坑，见 UPGRADE §17.4）。验证：单测 48→**55**、冒烟 86→**92×2**、停容器降级实测、语义直答端到端、浏览器实测。

## 三、纠正与教训（用户纠正 → 规则）

| 事件 | 确立的规则 |
|---|---|
| Key 写进仓库 config/（虽已 gitignore），用户指出「这个放在代码里不安全」 | 密钥一律仓库外 ~/.config/<app>/（700/600），仓库只留 .example；gitignore 只防入库不防分发/误拷/截图；Key 本身不存入记忆 |
| 用户亲自实时体验共享浏览器（会话/工单被并发操作） | 自动化用隐藏 iframe + Storage 包装隔离会话键 + fetch no-store；不劫持用户页签；借页签须授权并还原 |
| sendMessage 先落库后历史重建含本轮首条 USER → 提问重复、连环答非所问 | 历史重建必须跳过本轮首条 USER；行为漂移先查 `LLM tool ... ok=false` 日志 |
| ride-pro 已订阅致旧 smoke 额度用例不幂等 | 额度用例改用独立 SMOKE-FREE-<rand> 租户 |
| 目录导入不加载 llm.properties 的隐患 | spring.config.import 改显式文件导入 |
| evaluate_script 15s 超时（LLM 长等待） | 拆「发起」「读结果」两次调用；超时后先查 iframe 残留再重发 |
| rebuild 覆盖 jar 致运行中 JVM 类加载僵尸 | **先 stop 再 package 再 run**，流程固定 |
| MySQL profile sql.init.mode=never，新列不自动建 | schema 变更手动 ALTER 运行库 + 同步 db/schema-mysql.sql（H2 侧幂等 ALTER 自动） |
| EventSource 设不了自定义头，SSE 凭证 403 | SSE 走 ?token= query；smoke/eval/前端三处同步适配 |
| ride-pro 当日 token 预算耗尽，评测面临重置数据诱惑 | 不重置——llm-eval 改独立 EVAL 租户+管理端开通，预算按租户隔离天然归零 |
| JUnit assertEquals(int, Long) 恒不等 | 期望值用 long 字面量；聚合查询返回 DTO 避 H2/MySQL 列名大小写差异 |
| 权限分类器拦截删除/回滚组合操作 | 被拦即停并问用户，不换工具/渠道偷偷执行 |

## 四、通用协作原则（四系统一致）

1. **开箱即用**：零配置、一键启动脚本，交付前实际编译+测试+启动+UI 验证。
2. **方案拍板制**：给权衡选项（含推荐与理由）由用户拍板升级幅度，不擅自上重方案。
3. **数据保护**：删/重置数据先确认；验证尽量用隔离实例，演示库只留种子数据。
4. **密钥出仓库**：敏感配置放 `~/.config/<app>/`（700/600），仓库只留 .example；gitignore 不算安全。
5. **实测文化**：单测 + 冒烟 + 混沌（SIGSTOP/杀进程制造崩溃窗口）+ 浏览器全流程，全绿才算交付。
6. **设计类按商用标准**：架构/方案咨询默认生产级 + 主流正式框架（与构建类零依赖偏好区分）；确定性代码管判定与资金，LLM 只做理解/建议/解释。
7. **共享浏览器守则**：用户会实时操作内置浏览器；自动化用隐藏 iframe + localStorage 隔离 + no-store，不劫持用户页签；长等待拆两次调用（15s 超时）。
8. **中文交流 + 教学式回答**：操作类给可复制命令并实测演示；概念类通俗解释 + 代码 file:line；框架机制讲完整装配链路并指明定义处。
9. **git 听指令**：提交/推送均须用户明示；不 force push；不提交 data/logs/密钥。
10. **并行会话复查**：多会话同改一工作区，动手前先查现状。

## 五、指令模板与协作优化

**一行指令模板**：【构建/设计/运维】+ 目标 + 标准档位 + 完成定义 + 授权范围。
例：「构建，给 cs 加满意度回访工单，零依赖，完成=单测+smoke+评测+DESIGN 同步，提交推送一起做，删数据先问我。」

**已识别的优化方向**（2026-11-18 复盘）：
- 会话分工：一个会话只负责一个仓库，或开工声明范围；真并行用 worktree/分支隔离。
- 版本锚点：全量验证后打 git tag（如 cs-v1.1.0-verified）。
- README/DESIGN 同步自动化（脚本或 pre-commit 检查）。
- 测试数据与演示数据分离（推广 yiwu 的隔离实例模式）。
- 设计交付落盘成文档，不只留在对话里。
- 收掉脆弱依赖（正式 node 运行时、仓库可见性收尾、PAT 备选）。

## 六、运行与构建速查

- 构建：`JAVA_HOME=corretto-18`（release=17；PATH java 26 编不了），run.sh 自动探测；**rebuild 前先 ./stop.sh**。
- 验证：单测 55 项、`smoke-test.py` 92 项、`llm-eval.py` golden 17 项（自动建独立评测租户）；预算/熔断演练用 env（CS_LLM_DAILYTOKENBUDGETPERTENANT / CS_LLM_BASEURL=dead）。
- 商用模式：`docker compose -f ../ops-pro/deploy/middleware-compose.yml up -d` → `docker compose -f deploy/vector-compose.yml up -d --build`（向量检索，可选；同 claim-shared 栈，**勿 --remove-orphans**）→ `mysql … < db/schema-mysql.sql` → `./migrate-to-mysql.sh`（可选，先停服务）→ `CS_MQ_ENABLED=true ./run.sh`（**mysql 已是默认**，无需 CS_DB_MODE）；第二实例 CS_PORT=8099；零依赖回退 CS_DB_MODE=h2（向量自动降级 BM25）。共享栈密码在仓库外 ~/.config/claim-shared/env；embedding 模型预下载 `deploy/embedding/download-model.sh`（ModelScope 源）。
- 入驻 payment-pro：`./integrate-payment.sh`（payEnabled=false 时 pp 8443 需在跑）。
- LLM 日志关键字：`LLM OK model=deepseek-flash`、`LLM tool ... ok=true/false`。
- 文档套件：README（概览）/ DESIGN（权威契约）/ UPGRADE-v1.1.0 / UPGRADE-v1.2.0（实施与验证记录）/ 本文件（协作复盘），交付前同步。