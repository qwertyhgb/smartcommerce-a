package com.example.smartcommerce.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        Map<String, Object> details
) {

    public static ApiErrorResponse of(ErrorCode errorCode, String requestId) {
        return of(errorCode, errorCode.defaultMessage(), requestId, null);
    }

    public static ApiErrorResponse of(ErrorCode errorCode, String message, String requestId, Map<String, Object> details) {
        String resolvedMessage = message == null || message.isBlank() ? errorCode.defaultMessage() : message;
        String resolvedRequestId = requestId == null || requestId.isBlank() ? "unknown" : requestId;
        Map<String, Object> resolvedDetails = details == null || details.isEmpty() ? null : Map.copyOf(details);
        return new ApiErrorResponse(errorCode.name(), resolvedMessage, resolvedRequestId, resolvedDetails);
    }
}
