package com.example.smartcommerce.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    PARAM_INVALID(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未认证或身份已失效"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该资源"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "SKU 不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),

    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "幂等键冲突"),
    INVENTORY_NOT_ENOUGH(HttpStatus.CONFLICT, "商品库存不足"),
    PRODUCT_NOT_SELLABLE(HttpStatus.CONFLICT, "商品不可售"),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "当前订单状态不可取消"),
    AFTER_SALE_NOT_ALLOWED(HttpStatus.CONFLICT, "当前订单不可申请售后"),
    AFTER_SALE_ALREADY_OPEN(HttpStatus.CONFLICT, "该订单已有未完结售后工单"),
    AFTER_SALE_ALREADY_REVIEWED(HttpStatus.CONFLICT, "售后工单已被审核"),

    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后重试"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
