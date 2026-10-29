# AGENTS.md — SmartCommerce AI 项目规则

本文件是这个仓库的长期执行规则，Qoder IDE 会自动读取。开发契约以 `docs/plan/SmartCommerce_AI_v2_Development_Manual_238_Days.md` 为准；本文件规定“在这个仓库里怎么干活”。如果两者冲突，以手册为准并修正本文件。`docs/plan/` 是外部 `../plan/` 的只读快照；计划更新时以外部原文件为准并同步快照。

## 0. 每次会话的开始与结束

**开始**

1. 读 `STATUS.md`、`docs/progress/tickets.csv` 中未完成项、`docs/progress/daily/` 最近 3 个文件。
2. 执行 `git log -10 --oneline`，确认最近提交。
3. 对照 `docs/plan/SmartCommerce_AI_v2_Development_Manual_238_Days.md` 的当前工单，先向用户复述：当前周、当前工单、最近完成、阻塞、下一步、计划修改的文件。确认后再写代码。
4. 不重复已完成工作；账本与代码/Git 不一致时，以代码、测试和 Git 为准并修正账本。

**结束**

1. 更新 `STATUS.md`。
2. 追加或更新当天 `docs/progress/daily/YYYY-MM-DD.md`。
3. 更新 `docs/progress/tickets.csv` 对应行的 `status` / `evidence` / `commit` / `notes`。
4. 提交，提交信息带 `SC-xxxx`；`DONE` 必须有 evidence，不能只改状态。
5. 每周把 CSV 同步到 `docs/plan/SmartCommerce_AI_v2_238_Day_Project_Tracker.xlsx`；Release Gate 后打 tag，例如 `w01-gate`。

## 1. 任务开始前

1. 找到对应的 `SC-xxxx` 工单，阅读手册第 7 章该工单，以及 Excel 中同 ID 的行。
2. 阅读相关契约：手册第 0–6 章、`docs/adr/0000-technology-baseline.md`、`README.md`。
3. 先写清输入、输出、状态变化、异常场景、涉及的表和类，再写代码。
4. 只做当前工单范围。不提前实现后续阶段，不创建空模块、空包或“以后可能用到”的抽象。

## 2. 完成标准

- 每张工单的关闭证据 = 代码/文档路径 + 可复现的测试结果或命令，二者缺一不可。只把状态改成“已完成”不算完成。
- 每个用户故事至少 1 个正常用例 + 2 个异常用例；数据库约束、并发、幂等要有集成测试或并发测试。
- 新增或修改行为必须同步更新对应文档（ADR、API、DB、README）。
- Must 用例失败时不得标记里程碑通过；可以延期 Should/Could，不能跳过 Must。
- 遇到阻塞时记录原因、影响和下一步，不伪造“已完成”，不写空实现或 TODO 占位。

## 3. 不可破坏的约束

1. 库存、价格、订单状态、权限、金额、消息幂等必须由 Java 代码决定，不能让 LLM 凭文字决定。
2. 先保证交易正确性，再做高并发和 AI。单体架构是当前正确选择，不拆微服务。
3. AI 是不可信依赖：只能建议、检索、调用受控只读工具；不得直接修改订单、退款或绕过认证。
4. MySQL 是唯一真值；Redis 只是缓存，丢失不能影响交易正确性；RabbitMQ 是至少一次投递 + 消费幂等；Qdrant 只存公开售后知识，订单私有数据不得进入公共向量库。
5. 不做真实收款、真实物流、真实退款、真实用户数据导入；只做模拟支付。
6. 密钥、口令、token 不得写进任何会被 Git 跟踪的文件。真实值只放 `.env`（已忽略），`.env.example` 只放占位符；不要把密钥或真实用户数据贴给外部 AI。
7. 版本已锁定：Java 21、Spring Boot 4.1.2、Spring AI BOM 2.0.2、MyBatis Spring Boot Starter 4.1.0。W16 前不升级 Spring Boot 4.2 / Flyway 13；任何版本变更先更新 ADR 并跑完整测试。
8. 核心交易 SQL 使用原生 MyBatis。不与 MyBatis-Plus、JPA 混用；数据库结构变更只能通过 Flyway 迁移。

## 4. 技术约定

### Web 与 API

- Web 依赖使用 `spring-boot-starter-webmvc`（Boot 4 名称，不是 `spring-boot-starter-web`）。
- 接口统一挂在 `/api/v1`，返回 DTO，不直接暴露数据库实体。
- 入参使用 Bean Validation；错误响应统一包含错误码、消息和 `requestId`，内部异常不直接暴露给客户端。
- 下单价格、库存和订单归属由服务端从数据库读取，客户端提交的价格无效。
- 分页必须有稳定排序和页大小上限；列表默认过滤下架商品。

### 持久化

- MyBatis Mapper 使用 `#{}` 参数绑定，禁止把用户输入拼进 `${}` 或字符串 SQL。
- 事务边界放在 Service 层；不要依赖 `synchronized` 解决多实例并发。
- 库存扣减必须使用条件更新，例如：

```sql
UPDATE inventory
SET available = available - #{quantity}
WHERE sku_id = #{skuId} AND available >= #{quantity}
```

- 订单创建幂等键至少包含 `(user_id, endpoint, idempotency_key)`；同一个键配不同请求体必须返回冲突。
- 订单状态只能通过明确的状态机迁移，禁止任意字段更新。
- 数据库迁移文件放在 `src/main/resources/db/migration/`，命名 `V<序号>__<说明>.sql`。

### 缓存与消息

- Redis 只做 cache-aside，设置 TTL；Redis 故障时降级到数据库，不改变交易结果。
- 订单支付成功等事件先写 Outbox，再异步投递；消费者必须有 Inbox/唯一键保证幂等。
- 不承诺“消息绝不丢失”或 Exactly Once；按至少一次投递设计，并测试重复消费、乱序和 DLQ。

### AI

- 模型调用通过 AiGateway/Provider 适配器隔离；测试使用 FakeProvider，不调用真实模型、不消耗真实额度。
- RAG 回答必须带引用；知识库无依据时明确拒答或转人工；已撤回的政策不能继续作为当前政策。
- 工具调用只读，`userId` 必须来自 Spring Security 上下文，不能来自模型输出或用户文本。
- AI 功能必须有超时、降级、转人工和 token/成本预算。

### 测试

- 使用 JUnit 6（`org.junit.jupiter`）；Mock 替换用 `@MockitoBean`，不用已移除的 `@MockBean`。
- 数据库集成测试使用 Testcontainers 2：`testcontainers-mysql` + `org.testcontainers.mysql.MySQLContainer`，不要用 H2 替代 MySQL 特有行为。
- 并发测试用 `CountDownLatch`/线程池制造真实竞争，断言最终库存和有效订单数。
- 每个测试必须可重复执行，不依赖执行顺序、外部网络或真实密钥。

## 5. 常用命令

```bash
# 编译与测试
./mvnw test
./mvnw -Dtest=类名 test

# 启动应用
./mvnw spring-boot:run

# 本地基础设施（在仓库根目录执行）
docker compose --env-file .env -f infra/compose.yaml up -d --wait
docker compose --env-file .env -f infra/compose.yaml --profile cache up -d --wait
docker compose --env-file .env -f infra/compose.yaml --profile cache --profile mq up -d --wait
docker compose --env-file .env -f infra/compose.yaml --profile cache --profile mq --profile vector up -d --wait
```

MySQL 监听 `127.0.0.1:3306`，库名和账号见 `.env`。集成测试需要 Docker 正在运行。

## 6. Git 约定

- 一张工单对应一个提交或一个 PR；不把无关重构、格式化混进业务提交。
- 提交信息使用 `类型(模块): SC-xxxx 说明`，例如：

```text
feat(order): SC-0803 create order with atomic stock deduction
fix(inventory): SC-0705 prevent oversell under concurrency
test(order): SC-0804 add idempotency concurrency test
docs(adr): SC-0201 record catalog model decision
```

- 提交前至少运行相关测试，并检查 `git diff`；失败必须说明原因。
- 不提交 `.env`、`target/`、IDE 配置；不提交密钥、真实用户数据或压测造假数据。
- `docs/plan/` 是开发契约，不要修改；确需调整计划时先询问项目所有者。

## 7. AI 辅助编码等级

按手册第 8 章执行 L0–L4：

- 核心交易、库存、幂等、鉴权、消息可靠性、AI 工具授权的实现：只用 L0–L2，不直接使用 L4 生成整块代码。
- DTO、映射、样板配置、文档草稿、小测试：可以使用 L3–L4，但必须逐行审查、运行测试并能解释。
- 使用 AI 前先自己列业务规则和失败场景；AI 输出只作为建议，必须用编译、测试和官方文档验证。

## 8. 明确不做

- 不做微服务全家桶、服务网格、千万 QPS 目标。
- 不做真实支付/物流/退款，不导入真实用户数据。
- W27 前不做 MCP 试验；MVP 前不做完整运营后台界面、多模型同时接入、复杂 Agent 编排。
- 不在没有测量数据时声称性能、RAG 质量或成本结论。

## 9. 关键路径

| 内容 | 位置 |
|---|---|
| 238 天开发契约 | `docs/plan/SmartCommerce_AI_v2_Development_Manual_238_Days.md` |
| 进度追踪表 | `docs/plan/SmartCommerce_AI_v2_238_Day_Project_Tracker.xlsx` |
| 版本基线 | `docs/adr/0000-technology-baseline.md` |
| 当前状态 | `STATUS.md` |
| 工单账本 | `docs/progress/tickets.csv` |
| 每日记录 | `docs/progress/daily/` |
| 交接提示词 | `docs/progress/handoff-prompt.md` |
| 本地基础设施 | `infra/compose.yaml` |
| 业务代码 | `src/main/java/com/example/smartcommerce/` |
| 数据库迁移 | `src/main/resources/db/migration/` |
