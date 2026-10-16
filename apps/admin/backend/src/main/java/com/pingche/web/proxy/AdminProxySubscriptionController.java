package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/proxy-subscriptions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminProxySubscriptionController {
    private final ProxySubscriptionService service;
    private final ManagementTransportGuard transport;
    public AdminProxySubscriptionController(ProxySubscriptionService service, ManagementTransportGuard transport) {
        this.service = service; this.transport = transport;
    }
    @GetMapping
    public ResponseEntity<List<ProxySubscriptionResponse>> list(HttpServletRequest http) {
        secure(http); return noStore(service.list());
    }
    @PostMapping
    public ResponseEntity<ProxySubscriptionResponse> create(@Valid @RequestBody ProxySubscriptionRequest request, HttpServletRequest http) {
        secure(http); return noStore(service.create(request));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ProxySubscriptionResponse> update(@PathVariable UUID id, @Valid @RequestBody ProxySubscriptionRequest request, HttpServletRequest http) {
        secure(http); return noStore(service.update(id, request));
    }
    @PostMapping("/{id}/refresh")
    public ResponseEntity<ProxySubscriptionResponse> refresh(@PathVariable UUID id, @Valid @RequestBody ProxyVersionRequest request, HttpServletRequest http) {
        secure(http); return noStore(service.refresh(id, request.version()));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version, HttpServletRequest http) {
        secure(http); service.delete(id, version); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private void secure(HttpServletRequest request) {
        if (!transport.isAllowed(request)) throw new DomainException("PROXY_HTTPS_REQUIRED", "当前请求协议不符合服务器传输配置。", HttpStatus.FORBIDDEN);
    }
    private <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
