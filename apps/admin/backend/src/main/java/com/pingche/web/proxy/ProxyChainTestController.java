package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/proxy-chains")
@PreAuthorize("hasRole('ADMIN')")
public class ProxyChainTestController {
    private final ProxyChainTestService service;
    private final ManagementTransportGuard transport;
    public ProxyChainTestController(ProxyChainTestService service, ManagementTransportGuard transport) {
        this.service = service; this.transport = transport;
    }
    @PostMapping("/test")
    public ResponseEntity<ProxyChainTestService.TestResult> test(@Valid @RequestBody ProxyChainTestRequest request,
            HttpServletRequest http) {
        if (!transport.isAllowed(http)) throw new DomainException("PROXY_HTTPS_REQUIRED", "当前请求协议不符合服务器传输配置。", HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.test(request));
    }
}
