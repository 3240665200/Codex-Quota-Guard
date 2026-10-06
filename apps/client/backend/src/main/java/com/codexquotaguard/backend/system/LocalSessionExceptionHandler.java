package com.codexquotaguard.backend.system;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Sends fixed, non-sensitive failures to the Tauri shell. */
@RestControllerAdvice
public final class LocalSessionExceptionHandler {

    @ExceptionHandler(LoginDeniedException.class)
    public ResponseEntity<ErrorBody> loginDenied() {
        return response(HttpStatus.UNAUTHORIZED, "SYSTEM_LOGIN_DENIED", "邮箱或密码不正确。");
    }

    @ExceptionHandler(SystemSessionRequiredException.class)
    public ResponseEntity<ErrorBody> sessionRequired() {
        return response(HttpStatus.UNAUTHORIZED, "SYSTEM_SESSION_REQUIRED", "请先登录系统账户。");
    }

    @ExceptionHandler(ControlPlaneUnavailableException.class)
    public ResponseEntity<ErrorBody> controlPlaneUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_PLANE_UNAVAILABLE", "系统服务暂时不可用，请稍后重试。");
    }

    private static ResponseEntity<ErrorBody> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message));
    }

    record ErrorBody(String code, String message) {
    }
}
