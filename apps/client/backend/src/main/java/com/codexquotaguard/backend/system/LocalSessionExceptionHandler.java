package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.codex.OfficialCodexRateLimitsUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Sends fixed, non-sensitive failures to the Tauri shell. */
@RestControllerAdvice
public final class LocalSessionExceptionHandler {

    @ExceptionHandler(SessionBindingRequiredException.class)
    public ResponseEntity<ErrorBody> bindingRequired() {
        return response(HttpStatus.FORBIDDEN, "SESSION_BINDING_REQUIRED", "请先完成本设备官方会话绑定。");
    }

    @ExceptionHandler(SessionBindingException.class)
    public ResponseEntity<ErrorBody> sessionBindingUnavailable() {
        return response(HttpStatus.CONFLICT, "SESSION_BINDING_UNAVAILABLE",
                "本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。");
    }

    @ExceptionHandler(SessionStorageException.class)
    public ResponseEntity<ErrorBody> sessionStorageUnavailable() {
        return response(HttpStatus.LOCKED, "SESSION_STORAGE_UNAVAILABLE",
                "本机登录存储不可用，请关闭其他客户端并检查用户目录权限后重试。");
    }

    @ExceptionHandler(LoginAssistanceException.class)
    public ResponseEntity<ErrorBody> loginAssistance(LoginAssistanceException exception) {
        return switch (exception.reason()) {
            case MAIL_BUSY -> response(HttpStatus.CONFLICT, "MAIL_LISTENER_BUSY", "该账户已有用户正在监听，请稍后重试。");
            case MAIL_RATE_LIMITED -> response(HttpStatus.TOO_MANY_REQUESTS, "MAIL_RATE_LIMITED", "操作过于频繁，请稍后重试。");
            default -> response(HttpStatus.FORBIDDEN, "MAIL_REJECTED", "无法使用登录辅助，请检查团队与邮箱配置后重试。");
        };
    }

    @ExceptionHandler(LoginDeniedException.class)
    public ResponseEntity<ErrorBody> loginDenied() {
        return response(HttpStatus.UNAUTHORIZED, "SYSTEM_LOGIN_DENIED", "邮箱或密码不正确。");
    }

    @ExceptionHandler(SystemSessionRequiredException.class)
    public ResponseEntity<ErrorBody> sessionRequired() {
        return response(HttpStatus.UNAUTHORIZED, "SYSTEM_SESSION_REQUIRED", "请先登录系统账户。");
    }

    @ExceptionHandler(ControlPlaneRequestRejectedException.class)
    public ResponseEntity<ErrorBody> controlPlaneRequestRejected() {
        return response(HttpStatus.BAD_REQUEST, "CONTROL_PLANE_REQUEST_REJECTED",
                "中央后台拒绝请求，请检查请求内容后重试。");
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> invalidParameters() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_PARAMETERS", "请求参数无效，请刷新后重试。");
    }

    @ExceptionHandler(ControlPlaneUnavailableException.class)
    public ResponseEntity<ErrorBody> controlPlaneUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_PLANE_UNAVAILABLE", "系统服务暂时不可用，请稍后重试。");
    }

    @ExceptionHandler(MeterPairingRequiredException.class)
    public ResponseEntity<ErrorBody> meterPairingRequired() {
        return response(HttpStatus.CONFLICT, "METER_PAIRING_REQUIRED",
                "统计程序配对失败；当前 Windows 用户可能已绑定其他系统账户，请联系管理员检查设备绑定。");
    }

    @ExceptionHandler(OfficialCodexRateLimitsUnavailableException.class)
    public ResponseEntity<ErrorBody> officialLimitsUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "OFFICIAL_CODEX_LIMITS_UNAVAILABLE",
                "请先在官方 Codex 应用或 CLI 登录；登录后重试读取官方额度。");
    }

    private static ResponseEntity<ErrorBody> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message));
    }

    record ErrorBody(String code, String message) {
    }
}
