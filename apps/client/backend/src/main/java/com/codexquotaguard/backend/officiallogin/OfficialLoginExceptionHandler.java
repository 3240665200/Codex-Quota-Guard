package com.codexquotaguard.backend.officiallogin;

import com.codexquotaguard.backend.system.SystemSessionRequiredException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** This API never forwards exception messages or parser diagnostics to the renderer. */
@RestControllerAdvice(assignableTypes=OfficialLoginController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class OfficialLoginExceptionHandler {
    record ErrorBody(String code,String message) { }
    @ExceptionHandler(OfficialLoginException.class)
    ResponseEntity<ErrorBody> classified(OfficialLoginException failure) {
        String code=failure.code();
        HttpStatus status="OFFICIAL_LOGIN_SESSION_REQUIRED".equals(code) ? HttpStatus.UNAUTHORIZED
                : "OFFICIAL_LOGIN_ADMIN_REQUIRED".equals(code) ? HttpStatus.FORBIDDEN
                : "OFFICIAL_LOGIN_INVALID_PARAMETERS".equals(code) ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return response(status,code,"官方登录助手暂时无法完成此操作，请检查任务、浏览器和当前管理员登录后重试。");
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalid() { return response(HttpStatus.BAD_REQUEST,"OFFICIAL_LOGIN_INVALID_PARAMETERS","请求参数无效，请重新选择 Edge 或 Chrome 后重试。"); }
    @ExceptionHandler(SystemSessionRequiredException.class)
    ResponseEntity<ErrorBody> signedOut() { return response(HttpStatus.UNAUTHORIZED,"SYSTEM_SESSION_REQUIRED","请先登录系统管理员账号。"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unavailable() { return response(HttpStatus.SERVICE_UNAVAILABLE,"OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE","官方登录助手暂时不可用，请稍后重试。"); }
    private static ResponseEntity<ErrorBody> response(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorBody(code,message));
    }
}
