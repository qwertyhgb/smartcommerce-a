# 错误契约与错误码

- 工单：SC-0104
- 生效日期：2026-11-06
- 关联：PRD §0.2、开发手册 4.1、`docs/adr/0001-mvp-scope.md`
- 实现：`common/api`（响应/错误码/业务异常）+ `common/web`（requestId、全局异常处理）

## 1. 响应结构

所有业务错误统一返回 JSON：

```json
{
  "code": "PARAM_INVALID",
  "message": "请求参数不合法",
  "requestId": "req_...",
  "details": {
    "name": "name 不能为空"
  }
}
```

- `code`：稳定错误码，客户端按它分支；不要按 `message` 判断。
- `message`：面向用户的简短说明，不包含堆栈、SQL、类名等内部信息。
- `requestId`：本次请求的链路 ID，同时出现在响应头 `X-Request-Id` 和服务端日志中。
- `details`：可选，只放最小上下文（字段名、资源标识等），不放密码、token、支付凭据、完整请求体。

HTTP 状态语义：

| 状态 | 含义 |
|---|---|
| 400 | 请求解析失败、参数校验失败（本项目不使用 422） |
| 401 | 未登录或身份失效（SC-0401 起启用） |
| 403 | 已登录但无权限（资源级鉴权） |
| 404 | 资源不存在；越权查询按威胁模型统一返回 404，减少枚举 |
| 405 | 请求方法不支持 |
| 409 | 业务冲突（库存不足、幂等冲突、状态不允许等） |
| 429 | 限流（后续 AI/高频接口） |
| 500 | 服务端内部错误，响应不暴露内部细节 |
| 503 | 服务暂不可用（如幂等锁等待超时），可重试 |

## 2. requestId

- 客户端可带 `X-Request-Id`，服务端只接受 `^[A-Za-z0-9._-]{8,64}$`；缺失或非法时生成 UUID。
- 服务端把 requestId 写入：
  - 响应头 `X-Request-Id`；
  - 错误响应体 `requestId`；
  - 服务端日志的 MDC `requestId`。
- requestId 只用于链路追踪，不是认证凭据；不能用来证明用户身份。
- 不记录完整请求体、密码、token、支付凭据；日志只保留方法、路径、状态码、错误码和 requestId。

## 3. 错误码

| code | HTTP | 使用场景 |
|---|---:|---|
| `PARAM_INVALID` | 400 | 参数校验失败、请求体格式错误、参数类型错误、必填参数缺失 |
| `UNAUTHORIZED` | 401 | 未认证或令牌失效（SC-0401 起启用） |
| `FORBIDDEN` | 403 | 已认证但无权限 |
| `NOT_FOUND` | 404 | 通用资源或路径不存在 |
| `SKU_NOT_FOUND` | 404 | `sku_code` 不存在 |
| `METHOD_NOT_ALLOWED` | 405 | HTTP 方法不支持 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同一幂等键配不同请求摘要 |
| `INVENTORY_NOT_ENOUGH` | 409 | 库存不足，订单不创建、库存不变 |
| `PRODUCT_NOT_SELLABLE` | 409 | 商品下架/不可售 |
| `ORDER_NOT_CANCELLABLE` | 409 | 当前订单状态不允许取消 |
| `AFTER_SALE_NOT_ALLOWED` | 409 | 当前订单状态不允许申请售后 |
| `AFTER_SALE_ALREADY_OPEN` | 409 | 同一订单已有未完结售后工单 |
| `AFTER_SALE_ALREADY_REVIEWED` | 409 | 售后工单已被并发审核 |
| `SERVICE_UNAVAILABLE` | 503 | 幂等锁等待超时或服务繁忙，可重试 |
| `INTERNAL_ERROR` | 500 | 未预期的服务端异常 |

新增错误码的规则：

1. 先在 `ErrorCode` 增加枚举，再更新本文件；
2. 同一个 `code` 的语义不能改变；`message` 可以调整；
3. 业务模块抛 `BusinessException(ErrorCode, message, details)`，不要手写响应；
4. 新增码至少补一条测试，确保 HTTP 状态和响应结构正确。

## 4. 示例

### 400 参数校验失败

```json
{
  "code": "PARAM_INVALID",
  "message": "请求参数不合法",
  "requestId": "3f1c...",
  "details": {
    "quantity": "必须大于等于 1"
  }
}
```

### 404 路径不存在

```json
{
  "code": "NOT_FOUND",
  "message": "资源不存在",
  "requestId": "3f1c..."
}
```

### 409 库存不足

```json
{
  "code": "INVENTORY_NOT_ENOUGH",
  "message": "商品库存不足",
  "requestId": "3f1c...",
  "details": {
    "skuId": "sku-001"
  }
}
```

### 500 内部错误

```json
{
  "code": "INTERNAL_ERROR",
  "message": "服务器内部错误",
  "requestId": "3f1c..."
}
```

服务端日志保留异常堆栈和 requestId；客户端只拿到上面的通用结构。不要为了“方便排查”把异常 message、SQL 或类名返回给客户端。

## 5. 实现约束

- 全局异常处理在 `GlobalExceptionHandler`；业务代码只抛异常，不自己拼错误 JSON。
- requestId 过滤器优先级最高，先于业务和安全过滤器执行。
- 4xx 记录 WARN（不含堆栈）；5xx 记录 ERROR（含堆栈，仅服务端日志）。
- 测试见 `ErrorContractTest`：覆盖 400/404/500、业务 409、requestId 透传与生成、内部细节不泄露。
- 401/403 的认证实现属于 SC-0401/SC-0405；本工单只固定错误结构和错误码。
