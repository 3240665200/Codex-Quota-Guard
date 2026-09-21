package com.pingche.web.common;

import java.util.List;

public record ApiError(
        String code,
        String message,
        String requestId,
        List<FieldViolation> details
) {

    public record FieldViolation(String field, String reason) {
    }
}
