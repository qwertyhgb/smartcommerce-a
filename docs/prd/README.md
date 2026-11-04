# SmartCommerce AI 产品需求（PRD）

- 工单：SC-0101（Must，1.5h）
- 状态：已定稿（2026-11-03 定稿；2026-11-04 第五轮复核勘误已并入，修订记录见 §7.5）
- 关联：`docs/adr/0001-mvp-scope.md`（W16 范围冻结）、开发手册第 0–6 章（重点：3.3 状态机、3.4 幂等、第 4 章 API 契约、5.4 工具授权）
- 范围：三条核心用户故事 —— 下单、售后、订单查询。每条故事定义角色/触发、输入/输出、业务不变量、异常场景、验收用例，并明确订单写操作边界与 AI 边界
- 约定：需求按 Must / Should / Could 标记；与 ADR-0001 冲突时以 ADR-0001 为准

## 0. 全局约定（三条故事共用）

### 0.1 角色与鉴权

| 角色 | 说明 |
|---|---|
| USER | 买家，注册登录后只能操作本人数据 |
| ADMIN | 管理员，商品管理、最小管理端 |
| SUPPORT | 客服，售后工单审核 |

- 身份一律从 Spring Security 上下文解析；请求体/参数中的 userId 一律忽略。
- 未认证请求由 Spring Security 过滤器拦截（401），不进入业务逻辑；401/403 响应须通过自定义 `AuthenticationEntryPoint`/`AccessDeniedHandler` 输出统一错误结构 `{code, message, requestId, details?}`（实现归 SC-0104）。
- SUPPORT 对对象级授权：仅可读**关联了售后工单**（`after_sale_tickets`）的订单，禁止任意全量订单查询（防 `select * from orders where id=?` 式实现，见 3.1）。

### 0.2 幂等与错误契约

- **面向终端账户（USER/SUPPORT/ADMIN）的写接口**要求请求头 `Idempotency-Key`（推荐 UUID，≤ 64 字符）；服务端 `request_dedup` 表对 `(user_id, endpoint, idempotency_key)` 建唯一约束（对齐手册 3.4 与 3.1 表）。**系统间回调端点不适用**：`mock-payments/callback` 的调用方是"可信本地模拟器"、无用户身份，其幂等由状态机条件更新 + 回调序号保证（见 1.7 取舍 7），不走 `request_dedup`。
- **请求摘要**：对 `(endpoint, 规范化 JSON 请求体)` 计算 SHA-256 hex（`request_hash CHAR(64)`）。规范化 = 字段按字典序排列、null 与缺省字段统一、服务端忽略的字段（如客户端价格）不参与计算。**`endpoint` 记录路由模板**（如 `/api/v1/orders/{id}/cancel`），而非含资源 ID 的实际路径——否则同键不同订单可绕过幂等。
- **同键同摘要**：重放返回**首次响应快照**（`response_ref` 存首次响应体，对齐手册 3.4"相同请求的重试返回同一业务结果"）；订单后续状态变化由前端凭 orderId 调详情接口获取。重放仅复用业务响应体，`requestId` 以**当前请求**生成为准（保证日志回放链路正确）。不引入"执行中"占位语义（手册 3.4 该项为可选项）——首个事务未提交时，并发方阻塞在 DB 唯一约束锁上，事务结束后按摘要重放或 409，锁等待超时返回 503 + 重试提示（**503 为团队决策显式声明**：手册 4.1 未列 503，语义为"服务暂时繁忙、可安全重试"）。
- **同键不同摘要**：409 `IDEMPOTENCY_CONFLICT`。
- **并发同键**：由 DB 唯一约束协调键的领用；插入撞唯一约束后，**必须以当前读（`SELECT ... FOR UPDATE`）或独立新事务读取 dedup 行判定摘要**——同事务先前的普通 SELECT 在 RR 隔离级别下是快照读，看不到对方刚提交的行，会导致误判 409 甚至重复建单。不得只靠内存 Map/锁保证跨实例正确性。
- **首次失败后的重试**：幂等记录与业务写入在同一事务内提交，业务失败即回滚，同一键允许再次尝试并产生确定结果。
- `requestId` 是链路追踪 ID（每个请求唯一，用于日志回放），与幂等键是两个概念，禁止混用。
- 所有错误响应统一为 `{code, message, requestId, details?}`（`details` 可选，携带最小上下文如 `skuId`，不含敏感数据）；HTTP 语义按手册 4.1：401 未登录、403 无权限、404 不存在（越权可按威胁模型返回 404 减少枚举信息）、409 业务冲突、429 限流、500 服务端错误。内部异常不直接暴露。**400/422 判定（团队决策，手册 4.1 授权团队统一）**：本项目不启用 422——请求体解析失败与 Bean Validation 违规统一 400 `PARAM_INVALID`，业务语义拒绝走各自错误码（409/404 等）；EX-O6、EX-Q3 的 400 均由此统一。

### 0.3 金额与数据

- 金额用 `DECIMAL(18,2)` 存储（手册第 0 章硬规则），Java 侧 `BigDecimal`；禁止 float/double。
- 时间统一 UTC 存储、API 用带时区 ISO8601 表示（手册第 0 章）。
- **对外 ID 策略**：`skuId` = `products.sku_code`（字符串业务码）、`orderId` = 订单业务号（字符串，形如 `ord-xxx`），均对齐手册 4.2 示例；数据库自增主键不对外暴露（防顺序枚举）。内部关联（`order_items.sku_id` 等）落库用数值主键，由服务端经 `sku_code` 解析。
- 表结构变更只走 Flyway；核心交易 SQL 用原生 MyBatis `#{}` 绑定；落库字段名对齐手册 3.1（如 `order_items.qty`、`after_sale_tickets.detail`），API DTO 字段名不受此约束。

### 0.3.1 对手册 3.1 概念表的受控扩展清单（W2 建表依据）

手册 3.1 是"概念字段清单"（其 241 行明确非完整 DDL）。以下扩展列源自 PRD 已承诺的输入/输出，W2（SC-0202/SC-0203）建表时一次落全，避免返工：

| 表 | 扩展列 | PRD 依据 | 说明 |
|---|---|---|---|
| `orders` | `order_no` | 0.3 对外 ID 策略 | 字符串业务号 UNIQUE；自增 `id` 不对外 |
| `orders` | `remark` | 1.2 输入 | VARCHAR(200) 可空；**落库而非删输入**（保持对用户草稿的承诺） |
| `order_items` | `title_snapshot`, `image_snapshot` | 1.3 商品快照 | 取下单瞬间；快照语义不得改实时联查 |
| `after_sale_tickets` | `type` | 2.2 输入 | MVP 仅 `REFUND_SIMULATED` |
| `after_sale_tickets` | `active_order_key` | INV-A3 | STORED 生成列 + UNIQUE（NULL 不参与唯一性） |
| `mock_payments` | `callback_seq` | 1.7 取舍 7 | 单调递增序号，SC-0902 建表 |

### 0.4 订单状态机（全局）

```text
CREATED ──模拟支付回调(验签通过)──> PAID ──本人申请售后──> REFUND_PENDING ──SUPPORT/ADMIN 审核通过──> REFUNDED
   │                                        │
   └──用户取消──> CANCELLED（终态）          └──审核拒绝──> PAID（回退，允许重新申请）
```

- 对齐手册 3.3：`CREATED → PAID/CANCELLED`、`PAID → REFUND_PENDING → REFUNDED`；另有受控扩展边 `REFUND_PENDING → PAID`（审核拒绝回退，见 2.2/EX-A7，SC-1001 复核）；其余迁移一律非法。
- 所有迁移用条件更新实现（如 `WHERE status = 'CREATED'`），affected rows = 0 即拒绝。由此保证支付回调与用户取消并发时，只产生一个合法终态。
- 库存回补只发生在"取消成功"这一条边上（见 INV-O7）；`REFUND_PENDING/REFUNDED` 的库存行为由售后规则显式决定（见 2.3 INV-A6），不得含糊地复用取消接口（手册 3.3）。
- 重复回调、重复取消必须进自动回归测试（手册 3.3）。
- 订单过期自动关单是手册 3.3 标注的 P2 扩展，不在 W16 Must，不引入额外终态（见 non-goals）。
- **取消契约（手册第 4 章接口 `POST /api/v1/orders/{id}/cancel`，USER+本人）**——行为判定矩阵（消除"非 CREATED→409"与"重复调用幂等"的歧义；W10 取消与竞争测试断言以此为准，见 SC-1002/SC-1003；接口行为细化归 SC-1001）：

| 条件 | 结果 |
|---|---|
| 本人 + `CREATED` | 200 取消成功：条件更新 → `CANCELLED`，回补库存并写 `inventory_ledger` 一条流水 |
| 本人 + `CANCELLED` | 幂等成功（200 + 当前状态）；不回补、不写新流水（手册第 4 章"重复调用幂等"） |
| 本人 + `PAID/REFUND_PENDING/REFUNDED` | 409 `ORDER_NOT_CANCELLABLE`（已进入支付/售后流程，不可取消） |
| 非本人 / 订单不存在 | 404 |
| 同 `Idempotency-Key` 重放 | 走 `request_dedup` 首次响应快照（0.2） |

## 1. 故事一：下单（多 SKU）— Must

### 1.1 角色与触发

- 角色：USER（已登录、账号正常）。
- 前置：请求内全部 SKU 存在且在售。
- 触发：`POST /api/v1/orders`，请求头带 `Idempotency-Key`。

### 1.2 输入

| 字段 | 必填 | 规则 |
|---|---|---|
| items[].skuId | 是 | 字符串业务码（`products.sku_code`，见 0.3），对应商品必须在售；同一 SKU 不得重复出现（重复即 400） |
| items[].quantity | 是 | ≥ 1，≤ 参数校验上限（默认 999，**走应用配置**，非硬编码） |
| items 长度 | 是 | ≥ 1，≤ 单订单条目上限（默认 50，与 999 同为参数校验，非业务限购） |
| remark | 否 | 长度 ≤ 200 |
| 客户端价格等字段 | — | 服务端一律忽略，不采信 |

### 1.3 输出

- 成功（201）：`{orderId, status: CREATED, totalAmount, currency: CNY}`（字段对齐手册 4.2 示例；金额序列化为字符串如 `"139.80"`，防 JS 浮点精度丢失）；总额 = Σ(数据库单价 × 数量)，`DECIMAL(18,2)`。`currency` 为 API DTO 字段（MVP 固定 CNY），**不落 `orders` 表**（手册 3.1 表无此字段）。`orderId` 为字符串业务号（0.3）。订单明细保存商品快照（名称/单价/图片，取下单瞬间，对应 `order_items.unit_price_snapshot`）。
- 订单查询所需的收货信息 = 虚构演示静态数据，不是用户输入、不建地址模块（见 1.7 取舍 5）。
- 失败：统一错误码；不生成订单、不扣库存。

### 1.4 业务不变量

- **INV-O1 价格铁律**：实付金额永远由服务端按数据库价格 × 数量对全部 items 求和计算；客户端提交的价格字段不采信。
- **INV-O2 不超卖**：库存扣减必须条件更新，affected rows = 0 即失败（SQL 中 `sku_id` 为内部数值主键，服务端先按 `sku_code` 解析 `internalSkuId` 再执行，见 0.3）：

```sql
UPDATE inventory
SET available = available - #{quantity}
WHERE sku_id = #{skuId} AND available >= #{quantity}
```

对请求中每个 SKU 逐项执行，**items 一律按 `sku_code` 字典序加锁**，保证并发事务全局加锁顺序一致、避免交叉持锁死锁；**在售校验与库存扣减必须在同一事务内以当前读判定**（先 `SELECT ... FOR UPDATE` 锁定商品行校验 `status='ON_SALE'` 再扣减，防并发下架的 TOCTOU 竞态导致"下架仍成交"，违反 EX-O2）；任一项 affected rows = 0 → 整单失败、已执行的扣减随事务全部回滚（多 SKU 场景即 SC-0806 “其中一项库存不足”，见 EX-O11；并发争抢与死锁场景纳入 SC-0806 测试清单）。

- **INV-O3 幂等铁律**：`(user_id, endpoint, idempotency_key)` 唯一；同键同摘要重放返回首次结果；同键不同摘要返回 409；并发同键由 DB 唯一约束协调（规则见 0.2）。
- **INV-O4 状态铁律**：订单初始 `CREATED`；只有验签通过的模拟支付回调能迁移到 `PAID`；用户、客户端、AI 均无权直接改状态。
- **INV-O5 归属铁律**：订单绑定下单用户本人；一切后续操作先校验归属。
- **INV-O6 一致性铁律**：创建订单 + 全部 items 扣减库存 + 写幂等记录在同一本地事务；任一步失败整体回滚，不允许“扣了部分库存没订单”。
- **INV-O7 回补幂等**：未支付取消成功时回补库存，同样用条件更新 + 幂等保护；重复取消不重复回补；回补必须与状态迁移同事务，且每次回补写 `inventory_ledger` 流水（`reason` 明确）。超时自动关单不在 Must（手册 3.3 P2）。
- **INV-O8 金额精度**：单价与总额均为 2 位小数、数量为整数，乘积精确无舍入、无抹零；全程 `BigDecimal`，禁止 float/double 中转（见 0.3）。

### 1.5 异常场景

- EX-O1 库存不足：某 SKU 可用 1 件买 2 件 → 409 `INVENTORY_NOT_ENOUGH`（手册 4.1/4 章表），无订单生成，全部库存不变。
- EX-O2 商品下架：请求内任一 SKU 已下架 → `PRODUCT_NOT_SELLABLE`，不创建订单。
- EX-O3 幂等重放：同键同摘要重复请求 → 返回首次订单结果，不重复建单、不重复扣库存。
- EX-O4 幂等冲突：同键不同摘要 → 409 `IDEMPOTENCY_CONFLICT`，不创建订单。
- EX-O5 未登录/Token 过期：Spring Security 过滤器拦截 401，不进入业务逻辑。
- EX-O6 数量非法：0、负数、超上限 → 400 参数校验失败。
- EX-O7 SKU 非法：`sku_code` 不存在 → 404 `SKU_NOT_FOUND`，不创建订单。
- EX-O8 中途宕机：本地事务回滚，无脏数据；用户同键重试拿到确定结果。
- EX-O9 回调与取消竞争：支付回调与用户取消并发到达 → 条件更新裁决，仅一个合法终态（PAID 或 CANCELLED）；迟到方拒绝并记录日志。
- EX-O10 回调伪造/重复/乱序（手册 US-05 三件套）：伪造 → 验签失败（签名/密钥不匹配）直接拒绝并记录日志，不变更任何状态；重复 → 对已 PAID 订单幂等处理，不变更状态、不重复发事件；乱序 → mock 回调携带单调递增序号，迟到的旧序号回调直接拒绝、不覆盖新状态（SC-0904 防时序乱序）。
- EX-O11 多 SKU 部分库存不足：2 个 SKU 其中 1 个不足 → 整单失败 409，另一 SKU 的扣减随事务回滚，无孤儿订单。
- EX-O12 并发同键：两个相同 `Idempotency-Key` 的请求并发到达 → DB 唯一约束协调，只建一单、只扣一次；迟到方按摘要相同/不同返回重放或 409。
- EX-O13 并发抢库存（不同用户、不同幂等键）：两个用户并发抢同 SKU 最后 1 件 → 条件更新裁决仅一单成功、库存不为负；失败方 409 `INVENTORY_NOT_ENOUGH`，不生成订单、不扣库存（手册 US-03）。

### 1.6 验收用例

- 正常：Given 登录用户、2 个 SKU 均在售库存充足、全新幂等键 When 下单 Then 201；订单 `CREATED`、`totalAmount` = 服务端价 × 数量之和、`currency=CNY`、两个 SKU 各减对应数量。
- 异常1（库存）：Given 2 个 SKU 其中 1 个可用库存不足 When 下单 Then 409 `INVENTORY_NOT_ENOUGH`；无任何订单生成，两个 SKU 库存均不变（SC-0806 Must 场景）。
- 异常2（幂等）：Given 同键同摘要请求两次 Then 只生成一个订单、只扣一次库存、两次返回同一订单号；同键不同摘要 Then 409；并发同键 Then 仍只建一单。
- 异常3（改价）：Given 实际价格 199 When 客户端篡改价格为 0.01 提交 Then 服务端忽略该字段按 199 计入总额，绝无 0.01 元成交可能。
- 异常4（竞争）：Given 支付回调与用户取消并发到达 Then 仅一个终态成立（PAID 或 CANCELLED），另一侧被拒绝且日志可查。
- 异常5（并发争抢）：Given 两个用户（不同幂等键）并发抢同 SKU 最后 1 件 Then 仅一单成功、库存不为负，失败方 409 且无订单生成（手册 US-03）。
- 异常6（重复取消）：Given 同一 CREATED 订单 When 连续取消两次 Then 仅第一次回补库存，`inventory_ledger` 只有一条回补流水，库存不重复回补（手册 3.3 自动回归用例）。

### 1.7 MVP 取舍记录

1. 下单 = 多 SKU 同事务（Must，对齐手册 4.2 `items[]` 与 SC-0806）：全部成功或全部回滚；购物车“选中项→下单 items”的转换放 W8 实现，不再作为“单 SKU”取舍。
2. 库存模型：下单直接扣减 `available` + 取消成功条件回补；不采用预锁定两阶段模式。
3. 优惠券/促销 → 移出 MVP（见 non-goals）；实付 = Σ(单价 × 数量)。
4. 限购规则 → 移出 MVP；quantity 与 items 长度上限只是参数校验，不是业务限购。
5. 地址模块 → 移出 MVP（手册 3.1 核心表与第 4 章 API 均无 address）：收货信息使用虚构演示静态数据，随订单快照保存；地址管理进 non-goals 延期清单。
6. 订单过期自动关单 → 手册 3.3 P2，移出 Must（non-goals 延期清单）；W16 未支付订单由用户手动取消或保留 CREATED。
7. mock 支付回调契约：callback 携带单调递增序号（`callback_seq` 落 `mock_payments` 表，SC-0902 建表 `V7__payments.sql`、SC-0903/0904 实现）+ 演示签名（乱序防护依据，见 EX-O10）；细粒度字段与验签算法由 W8 的 SC-0903/SC-0904 细化。**支付模拟整体契约归手册第 4 章（`POST /api/v1/mock-payments` + `/callback`）与 SC-0903/SC-0904，不在三条故事范围内展开**；PRD 仅约束其不变量（INV-O4、EX-O9/O10）与幂等适用范围（0.2）。

## 2. 故事二：售后工单 — Must（模拟）

### 2.1 角色与触发

- USER：对本人已支付订单发起售后、查询进度。
- SUPPORT（或 ADMIN）：审核，推动工单与订单退款状态。
- 触发（路径对齐手册第 4 章表）：`POST /api/v1/after-sales`（提交）、`GET /api/v1/after-sales/{id}`（USER 本人 / SUPPORT 查询）、`POST /api/v1/support/tickets/{id}/review`（SUPPORT/ADMIN 审核）。

### 2.2 输入 / 输出

- 提交输入：`orderId`（本人订单）、`type`（MVP 仅 `REFUND_SIMULATED` 模拟仅退款）、`detail`（必填，≤ 500 字，字段名对齐手册 3.1 `after_sale_tickets.detail`）。
- 提交输出：工单号、状态 `SUBMITTED`；关联订单 `PAID → REFUND_PENDING`（同事务条件更新）。
- 审核输入：decision（RESOLVED / REJECTED）+ 备注；失败为统一错误契约。

工单状态机：

```text
SUBMITTED --SUPPORT 审核──> RESOLVED / REJECTED（终态）
```

- 订单侧 `PAID → REFUND_PENDING → REFUNDED`（见 0.4）：提交申请驱动 `REFUND_PENDING`；审核通过驱动 `REFUNDED`，与工单 `RESOLVED` 同事务；审核拒绝则工单 `REJECTED`、订单**同事务条件更新回退 `PAID`**（`WHERE status='REFUND_PENDING'`），允许对同一订单重新申请售后（EX-A7）。
- 仅 SUPPORT/ADMIN 可推动工单状态机；USER 只能提交本人申请和查询。

### 2.3 业务不变量

- **INV-A1 归属**：只能对本人订单发起售后；他人订单一律 404，不泄露存在性。
- **INV-A2 前置状态**：只有 `PAID` 订单可发起售后（`CREATED` 请走取消流程；`REFUND_PENDING/REFUNDED/CANCELLED` 不可重复申请，MVP 拒绝）。
- **INV-A3 单开单**：同一订单同时最多一个未完结（`SUBMITTED`）工单。MySQL 不支持部分唯一索引，用生成列 + 唯一约束实现：未完结时占位、完结自动释放；并发下第二个请求撞约束失败 409。**执行顺序固定：先插入工单占唯一键，后迁移订单状态**——并发下后来者必然撞 `AFTER_SALE_ALREADY_OPEN`，保证错误码确定：

```sql
ALTER TABLE after_sale_tickets
  ADD COLUMN active_order_key BIGINT
    GENERATED ALWAYS AS (IF(status = 'SUBMITTED', order_id, NULL)) STORED,
  ADD UNIQUE KEY uk_active_order (active_order_key);
```

（NULL 不参与唯一性：工单 RESOLVED/REJECTED 后自动解锁该订单，允许重新开单；随 Flyway 迁移落库。）

- **INV-A4 权限**：USER 不能推动状态机（不能自行标记已处理）；仅 SUPPORT/ADMIN 可以。
- **INV-A5 模拟边界**：售后完结不产生真实退款、不动资金；审核通过仅迁移订单 `REFUND_PENDING → REFUNDED` 与工单状态。
- **INV-A6 退款库存规则（显式，对齐手册 3.3 与 SC-1001）**：v1 只有未支付取消自动回补库存；`REFUND_SIMULATED` 为仅退款（货未退回），`REFUNDED` **强制不回补库存**——货没回来就回补 = 库存虚增、为超卖埋雷。未来若引入"退货退款"（实物回归）场景，回补条件与开关由 SC-1001 规则化定义。任何库存回补必须写 `inventory_ledger` 流水（reason）并幂等，绝不通过取消接口隐式实现。
- **INV-A7 审计**：每次工单与订单状态变更记录操作人、时间、备注，不可篡改；落 `audit_log`（`actor_id,action,target_id,occurred_at`，手册 3.1），与状态更新同事务。
- **INV-A8 幂等**：发起售后与审核都要求 `Idempotency-Key`，规则同 0.2。

### 2.4 异常场景

- EX-A1 订单不存在或不属于本人 → 404。
- EX-A2 订单状态不是 PAID（CREATED/CANCELLED/REFUND_PENDING/REFUNDED）→ 409 `AFTER_SALE_NOT_ALLOWED`。
- EX-A3 该订单已有未完结工单 → 409 `AFTER_SALE_ALREADY_OPEN`（唯一约束兜底，并发也安全）。
- EX-A4 USER 调用审核接口 → 403 `FORBIDDEN`，工单与订单状态均不变。
- EX-A5 同键重复提交 → 重放首次结果；同键不同摘要 → 409。
- EX-A6 审核通过 → 工单 RESOLVED 与订单 REFUNDED 同事务；事务中途失败则两侧一起回滚，不出现“工单已结、订单还在 REFUND_PENDING”的半状态。
- EX-A7 审核拒绝 → 工单 REJECTED 与订单回退 PAID 同事务；事务失败一起回滚，绝不出现"工单已拒、订单卡在 REFUND_PENDING"的死状态。
- EX-A8 并发审核：两个 SUPPORT 同时审核同一工单 → 条件更新裁决仅一次生效，后来者 409 `AFTER_SALE_ALREADY_RESOLVED`，工单与订单状态不重复迁移。

### 2.5 验收用例

- 正常：Given 本人 PAID 订单 When USER 提交售后 Then 工单 SUBMITTED、订单 REFUND_PENDING；SUPPORT 审核通过 → 工单 RESOLVED、订单 REFUNDED；USER 查询可见状态与处理记录（操作人/时间）。
- 异常1（归属）：Given 用户 B 对用户 A 的订单发起售后 Then 404。
- 异常2（越权）：Given USER 直接调用审核接口 Then 403，工单与订单状态均不变。
- 异常3（前置状态）：Given CREATED 订单发起售后 Then 409 拒绝。
- 异常4（拒绝后重开）：Given SUPPORT 审核拒绝（订单回退 PAID）When 用户重新申请售后 Then 新工单 SUBMITTED、订单再次 REFUND_PENDING；旧工单保持 REJECTED 不变。

### 2.6 AI 边界（第二里程碑，W19+，不阻塞 W16）

- 能做：RAG 检索公开售后 FAQ（回答必须带引用；无依据拒答/转人工；已撤回政策失效）；为 SUPPORT 草拟回复建议，审核通过后才成为正式回复。
- 不能做：推动工单状态、代替审核、直接向用户发出正式回复、越出授权范围访问数据（含他人订单与订单私有字段）。

## 3. 故事三：订单查询 — Must

### 3.1 角色与触发

- USER：查本人订单详情与列表。
- 手册第 4 章表规定订单详情权限 = `USER+本人/授权客服`：SUPPORT 可经对象级授权查看**关联了售后工单**的订单（见 0.1）；独立管理端订单查询接口 → Should（W16 后评估），一旦存在必须走独立管理端鉴权，不得复用用户接口越权。
- 触发：`GET /api/v1/orders/{orderId}`（手册契约）、`GET /api/v1/orders?page=&size=`（**手册扩展接口**：第 4 章表无此路径，经评审决策保留，已登记 ADR-0001 变更记录；OpenAPI 随 SC-0304 补齐）。
- 列表接口仅按当前登录账号过滤本人订单——SUPPORT/ADMIN 调用同样只返回本人订单（角色不放宽数据范围）；管理端订单查询走独立 Should 接口。

### 3.2 输入 / 输出

- 输入：orderId（路径参数，字符串业务号）；或 page（**默认 0，起始页 = 0**）、size（默认 20，上限 50，**走应用配置**，非硬编码）。
- 输出：详情 DTO（订单号、状态、金额 + `currency`、商品快照、创建/支付时间）；收货信息为虚构演示静态数据，不返回真实个人信息；列表按 `created_at DESC, id DESC` 稳定排序。

### 3.3 业务不变量

- **INV-Q1 数据隔离**：USER 只能读本人订单；查询他人订单 → 404，响应与"订单不存在"完全一致。
- **INV-Q2 真值来源**：金额与状态以 MySQL 为准；Redis 缓存仅加速、可丢失（TTL + 故障降级读库）。
- **INV-Q3 分页契约**：稳定排序（时间倒序，id 决胜）；size 超上限 → 400；页码越界 → 返回空列表。
- **INV-Q4 越权防护**：管理端与用户端接口分离、各自鉴权；用户接口不因参数变形返回他人数据。
- **INV-Q5 泄露防护**：错误响应不含堆栈、SQL、内部异常信息。

### 3.4 异常场景

- EX-Q1 查询他人订单 → 404。
- EX-Q2 订单不存在 → 404；orderId 格式非法 → 400。
- EX-Q3 size = 10000 → 400 `PARAM_INVALID`；page 越界 → 200 空列表。
- EX-Q4 未登录 → 401。

### 3.5 验收用例

- 正常：Given 用户有 3 个订单 When 分页查列表 size=2 Then 第 1 页 2 条、第 2 页 1 条，两次请求排序一致。
- 异常1（隔离）：Given 用户 B 查询用户 A 的订单号 Then 404，与"订单不存在"响应一致。
- 异常2（参数）：Given size=10000 Then 400 `PARAM_INVALID`。
- 异常3（明细）：Given 多 SKU 订单查看详情 Then 返回全部明细行与各自单价快照，金额 = 各行之和 = `totalAmount`；订单不含真实个人信息。

### 3.6 AI 边界（第二里程碑）

- 唯一受控只读工具 `getMyOrderStatus(orderId)`：userId 取自 SecurityContext，绝不来自模型输出或用户文本；orderId 必须校验归属。
- **输出字段白名单（对齐手册 5.4 与 SC-2304）**：仅 `orderId`、`status`、非敏感时间；禁止输出收货地址、手机号、支付凭据、金额明细等敏感字段。
- AI 回答订单相关问题必须基于该工具返回，不得编造状态。

## 4. 订单写操作边界（汇总）

| 事项 | 决定权 |
|---|---|
| 价格与金额计算 | 只能服务端 |
| 库存校验、扣减与回补 | 只能服务端条件更新 |
| 商品在售状态 | 只能服务端实时查库 |
| 订单号生成与状态迁移 | 只能服务端状态机的合法边（验签回调 / 本人取消 / SUPPORT·ADMIN 审核） |
| 用户身份与订单归属 | 只能 Security 上下文解析 |
| 幂等判定 | 只能服务端幂等表 |
| 支付状态变更 | 只能验签通过的支付回调 |
| 售后工单状态 | 仅 SUPPORT/ADMIN |
| 模拟支付发起与回调验签 | 只能服务端 + 演示签名（`mock_payments`） |
| AI | 无任何写权限；仅建议、检索、受控只读工具 |

结论：客户端与 AI 提交的一切内容都只是"申请"；是否成交、价格多少、库存是否足够、状态如何流转，全部由 Java 服务端决定。

## 5. AI 能力边界（全局）

W16 **不实现** AI 接口（对齐 AGENTS.md"不提前实现后续阶段"；手册第 4 章 AI 路径随 W19+ 工单实现），能力在第二里程碑落地：

- 能做：建议与草稿（最终由人确认并经正式接口写入）；公开售后知识检索（必须带引用）；受控只读工具调用（userId 来自 SecurityContext）。
- 不能做：任何写操作（订单/库存/售后状态/退款）；决定价格金额；绕过认证；访问他人数据；把订单私有数据写入公共向量库。
- 工程要求：AiGateway/Provider 适配器隔离；测试用 FakeProvider；必须有超时、降级、转人工和 token/成本预算。

## 6. 需求优先级汇总（Must / Should / Could）

### Must（W16，对齐 ADR-0001）

| 模块 | 内容 |
|---|---|
| 认证权限 | 注册/登录/令牌校验；USER/ADMIN/SUPPORT 角色；资源归属校验 |
| 商品 | 列表/详情（稳定排序、下架过滤）；管理端创建/修改/上下架 |
| 购物车 | 加购/改数量/删除/本人数据隔离 |
| 下单 | 多 SKU 同事务下单：服务端价格、逐项条件扣库存、幂等三元组+请求摘要、状态机（含部分库存不足回滚，SC-0806） |
| 取消/补偿 | 未支付取消回补库存（条件更新+幂等+流水）；支付/取消竞争只产生一个合法终态 |
| 支付模拟 | `POST /api/v1/mock-payments` 发起 + 回调验签与幂等（**契约细化归 SC-0903/SC-0904**，PRD 只约束不变量，见 1.7 取舍 7）；重复、乱序、验签失败回调不重复变更状态 |
| 售后 | 提交/查询/SUPPORT 审核（模拟）；联动订单 REFUND_PENDING/REFUNDED；USER 不可自行完结 |
| 订单查询 | 详情/列表（列表为登记过的手册扩展接口，见 3.1）；数据隔离；分页契约 |
| 事件链路 | Outbox + 至少一个幂等消费者（支付成功事件） |
| 工程 | Flyway、统一错误契约 + requestId、单元/集成测试、OpenAPI、结构化日志 |

### Should

- 购物车选中项→下单的便捷转换（下单本身多 SKU 已是 Must，见 1.7 取舍 1）
- 管理端订单查询（ADMIN/SUPPORT 独立接口）
- 售后工单用户撤回（仅 SUBMITTED 状态）
- Redis cache-aside 缓存 + 故障降级
- DLQ 死信处理

### Could（第二里程碑/后期，不阻塞 W16）

- 订单过期自动关单（手册 3.3 P2，从 Must 移出）
- 地址管理（从故事一移出；MVP 用演示静态收货信息）
- 优惠券/促销/活动价（见 non-goals）；限购规则
- AI：RAG 售后 FAQ、`getMyOrderStatus` 只读工具、AI 回复草稿（W19+）
- 运营后台完整 UI（W18+）
- MCP 试验（W27+）、多模型接入、复杂 Agent 编排

## 7. 评审修订记录

### 7.1 第一轮：与用户草稿（2026-10-30）的差异

SC-0101 要求“先独立列业务规则，再允许 AI 检查遗漏”。用户已独立完成故事一草稿，本次整理修订如下：

1. 草稿的幂等键 `requestId` 更名为 `Idempotency-Key`；`requestId` 保留为链路追踪 ID（见 0.2）。
2. 优惠券相关输入、INV、EX-6 移出 MVP → non-goals；实付金额简化为单价 × 数量。
3. 补"同键不同体 → 409"（EX-O4）；草稿仅覆盖同键同体重放。
4. 补支付回调与取消/关单的竞争（EX-O9/O10），对齐 ADR-0001"只产生一个合法终态"。
5. 补地址归属校验（EX-O7）、金额精度约定（INV-O8 / 0.3）。
6. "网关拦截"改为"Spring Security 过滤器拦截"（单体架构无网关）。
7. 明确库存模型：下单直接扣减 + 条件回补（见 1.7 取舍 2）。
8. 限购移出 MVP；quantity 上限仅为参数校验，非业务限购。

### 7.2 第二轮：对照手册第 3–5 章与 W8/W10 的评审修订（2026-11-03）

1. 状态机改为手册 3.3 命名 `CREATED/PAID/CANCELLED/REFUND_PENDING/REFUNDED`；删除自造 `WAIT_PAY/CLOSED`；PAID 不再是终态，接入售后迁移（0.4 / INV-O4 / EX-O9/O10）。
2. 超时自动关单从 Must 降为 Could（手册 3.3 P2），相关 `CLOSED` 迁移与实现细节（`@Scheduled`）全部删除。
3. 下单改为多 SKU `items[]` 同事务（手册 4.2、SC-0806 Must 测试）；异常与验收补“部分库存不足→整单回滚”（EX-O11）。
4. 地址模块整体移出 MVP（手册 3.1/第 4 章无 address）：删 `addressId` 输入、`ADDRESS_NOT_FOUND`、脱敏规则；改演示静态收货信息，地址管理入 non-goals。EX-O7 编号同时改为“SKU 非法”（第一轮 EX-O7 的地址归属校验随地址模块一并删除）。
5. 幂等补全（手册 3.4）：请求摘要 canonical JSON + SHA-256、并发同键 DB 协调、首次失败可重试、执行中语义（0.2 / INV-O3 / EX-O12）。
6. 售后审核接口改回手册路径 `POST /api/v1/support/tickets/{id}/review`；补详情权限 `USER+本人/授权客服`（第 4 章表）。
7. 错误码改 `INVENTORY_NOT_ENOUGH`（409）；错误结构补可选 `details`；HTTP 语义按手册 4.1（含 422、越权返 404 约定）。
8. 补模拟支付契约：`POST /api/v1/mock-payments` 发起 + `POST /api/v1/mock-payments/callback` 验签幂等（手册第 4 章表、`mock_payments` 表）。
9. 售后与订单/库存关系显式化（手册 3.3、SC-1001、W10 Gate）：工单状态机与订单 REFUND 迁移联动、审核同事务；退款默认不回补库存，回补必须写 `inventory_ledger` 且独立受控（INV-A5/A6、EX-A6）。
10. AI 工具输出字段白名单：仅 `orderId/status/非敏感时间`，禁地址/手机号/支付凭据（手册 5.4、SC-2304）。

### 7.3 第三轮：深度评审修复（2026-11-03，用户确认后执行）

修复对象为本轮深度评审发现的 P0×2 / P1×3 / P2×5 / P3×5：

1. 【P0-1】售后拒绝死状态：审核拒绝改为订单同事务条件更新回退 `REFUND_PENDING → PAID`（0.4 状态机补受控扩展边、2.2、EX-A7、2.5 异常4），“允许重新开单”与 INV-A2 自洽；该回退边为手册 3.3 的受控扩展，SC-1001 复核。
2. 【P0-2】INV-A3 落 MySQL 实现：生成列 `active_order_key` + 唯一约束（NULL 不参与唯一性），替代不可行的“部分唯一索引”写法；随 Flyway 落库。
3. 【P1-1】补 EX-O13 与验收异常 5：并发抢最后一件（手册 US-03、ADR-0001 验收 #7）。
4. 【P1-2】EX-O10 拆为伪造/重复/乱序三小项（手册 US-05 三件套）；mock 回调补单调递增序号约定（1.7 取舍 7，SC-0904）。
5. 【P1-3】补验收异常 6：重复取消不重复回补（手册 3.3 自动回归用例、ADR-0001 验收 #6）。
6. 【P2-1】INV-O2 补 items 按 `skuId` 升序加锁，防多 SKU 交叉持锁死锁；纳入 SC-0806 测试清单。
7. 【P2-2】幂等“执行中 409”占位语义删除：并发同键由 DB 唯一约束锁协调，事后查表重放/409，锁超时 503（0.2；手册 3.4“执行中状态”为可选项）。
8. 【P2-3】INV-O8 金额精度重写（2 位小数乘整数精确无舍入）；1.3 补金额序列化为字符串（对齐手册 4.2 示例）。
9. 【P2-4】INV-A6 删“演示数据需要回补”口子：仅退款（货未退回）强制不回补，防库存虚增；退货退款回补由 SC-1001 规则化。
10. 【P2-5】删 `IN_REVIEW` 中间态（手册第 4 章仅 review 一个审核接口，受理无接口支撑），工单状态机收敛 `SUBMITTED → RESOLVED/REJECTED`（2.1/2.2/INV-A3/2.5）。
11. 【P3】EX-O3/O4/A5 术语统一“摘要”；7.2 补 EX-O7 编号说明；0.4 的 INV-A5 引用修正为 INV-A6；2.6 措辞修正；2.5 原异常4（前提混乱的竞争用例）改写为“拒绝后重开”。
12. 用户拍板确认（2026-11-03）：P0-1 采用方案 A（拒绝回退 PAID）；地址移出 MVP、超时关单降 Could、多 SKU 下单三项维持第二轮决策。

### 7.4 第四轮：契约冲突修复（2026-11-03，外部深度评审 + 手册原文逐条复核）

复核结论：评审指控 8 项抽查全部属实（含金额 `DECIMAL(18,2)` 手册 210 行、`sku-001` 字符串示例 337 行、`after_sale_tickets/detail` 233 行、UTC/ISO8601 211 行、40+20 评测 406 行）。修复清单：

1. 【P0-1】金额精度 `DECIMAL(12,2)` → `DECIMAL(18,2)`（0.3/1.3，手册第 0 章硬规则）。
2. 【P0-2】对外 ID 策略拍板：`skuId`=`sku_code` 字符串业务码、`orderId`=字符串业务号，自增主键不暴露（0.3 新增；1.2/1.3/EX-O7 同步）；加锁顺序由 `skuId` 升序改 `sku_code` 字典序（INV-O2）。
3. 【P0-3】订单列表接口拍板保留：3.1 显式标注"手册扩展接口"+ 登记 ADR-0001 变更记录 + OpenAPI 随 SC-0304 补齐。
4. 【P0-4】支付模拟归属拍板：不加故事四；0.2 幂等条款改为"面向用户的写接口"，回调端点排除（幂等=条件更新+序号）；1.7 取舍 7 与 §6 Must 行标注契约归 SC-0903/0904。
5. 【P1-1】RR 快照读陷阱：0.2 写明撞唯一约束后以当前读（FOR UPDATE）或新事务判定摘要。
6. 【P1-2】response_ref 拍板：重放返回首次响应快照（对齐手册 3.4"同一业务结果"）。
7. 【P1-3】售后回退边 `REFUND_PENDING → PAID` 与订单列表扩展一并登记 ADR-0001 变更记录（满足 ADR 硬规则 5）。
8. 【P1-4】SUPPORT 对象级授权：仅可读关联售后工单的订单（0.1/3.1），禁全量查询。
9. 【P1-5】400/422 判定表：不启用 422，解析失败与 Bean Validation 统一 400 `PARAM_INVALID`（0.2，手册 4.1 授权团队决策）。
10. 【P1-6】取消契约补入 0.4：引用手册接口路径 + 行为约束（幂等/错误码），细化归 SC-1001。
11. 【P1-7】TOCTOU：INV-O2 在售校验与扣减同事务当前读判定（先锁商品行校验状态）。
12. 【P1-8】表名/字段对齐手册 3.1：`after_sale_tickets`、`detail`、落库 `qty`；`currency` 声明为 DTO 字段不落 orders 表（0.3/1.3/INV-A3）。
13. 【P2】INV-A7 审计落 `audit_log` 同事务；幂等键 UUID ≤64、`request_hash CHAR(64)`、endpoint 用路由模板（防同键不同资源绕过）；`callback_seq` 落 `mock_payments`；UTC/ISO8601 入 0.3；999/50 上限走应用配置；售后并发"先占唯一键后迁移状态"定错误码确定性；订单对外业务号防枚举；"W16 只预留接口"→"W16 不实现"；401/403 统一结构约束 `AuthenticationEntryPoint`（SC-0104）。
14. 【P3】分页起始页 0 显式声明；non-goals 评测改"40 普通 + 20 攻击"（手册 5.5）；non-goals §2 补 Should 项交叉引用；状态行更新。
15. 用户拍板确认（2026-11-03）：skuId=字符串业务码、列表接口保留+登记、支付模拟声明归属、重放=首次响应快照（四项均按推荐方案）。

### 7.5 第五轮：复核勘误（2026-11-04，对照手册原文复核，P1×2 / P2×5 / P3×5）

1. 【P1-1】OpenAPI 归属引用修正：SC-0303（实为商品上下架状态迁移，手册 629 行）→ **SC-0304**（生成 OpenAPI 文档，手册 633 行）；3.1、7.4 条 3、ADR-0001 变更记录三处统一。
2. 【P1-2】取消契约判定矩阵（0.4）：消除"非 CREATED→409"与"重复调用幂等"的矛盾——本人 `CANCELLED` 走幂等 200（手册 304 行"重复调用幂等"），409 只用于 `PAID` 系不可取消状态；W10（SC-1002/SC-1003 取消与竞争测试）断言以此为准。
3. 【P2-1】`mock_payments` 建表归属修正：建表 = **SC-0902**（`V7__payments.sql`，手册 847 行），SC-0903/0904 为实现（1.7 取舍 7）。
4. 【P2-2】新增 0.3.1"对 3.1 概念表的受控扩展清单"：`orders.order_no/remark`、`order_items.title/image_snapshot`、`after_sale_tickets.type/active_order_key`、`mock_payments.callback_seq`，W2（SC-0202/0203）一次落全；`remark` 拍板落库而非删输入（保持对用户草稿的承诺）。
5. 【P2-3】INV-O2 注明 SQL 内 `sku_id` 为内部数值主键、先按 `sku_code` 解析再扣减（防字符串误绑数值列）。
6. 【P2-4】幂等重放仅复用业务响应体，`requestId` 以当前请求为准（0.2）。
7. 【P2-5】幂等适用范围改"面向终端账户（USER/SUPPORT/ADMIN）的写接口"，覆盖 INV-A8 审核幂等（0.2）。
8. 【P3】503 显式声明为团队决策（手册 4.1 未列）；0.1 的 401/403 结构补 `details?`；non-goals §2 移除 Should 行、注 1 改为统一在 PRD §6 跟踪；新增 EX-A8 并发审核 409 `AFTER_SALE_ALREADY_RESOLVED`；3.1 定死列表接口 SUPPORT/ADMIN 仅返回本人订单。
