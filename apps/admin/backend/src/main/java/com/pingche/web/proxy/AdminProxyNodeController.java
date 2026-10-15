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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/proxy-nodes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminProxyNodeController {
    private final ProxyNodeService service;
    private final ManagementTransportGuard transportGuard;
    public AdminProxyNodeController(ProxyNodeService service, ManagementTransportGuard transportGuard) {
        this.service = service;
        this.transportGuard = transportGuard;
    }
    @GetMapping
    public ResponseEntity<List<ProxyNodeResponse>> list(HttpServletRequest http) { secure(http); return noStore(service.list()); }
    @PostMapping
    public ResponseEntity<ProxyNodeResponse> create(@Valid @RequestBody ProxyNodeRequest request, HttpServletRequest http) {
        secure(http); return noStore(service.create(request));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ProxyNodeResponse> update(@PathVariable UUID id, @Valid @RequestBody ProxyNodeRequest request, HttpServletRequest http) {
        secure(http); return noStore(service.update(id, request));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest http) {
        secure(http); service.delete(id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/import")
    public ResponseEntity<ProxyNodeService.ImportResult> importNodes(@Valid @RequestBody ProxyImportRequest request, HttpServletRequest http) {
        secure(http); return noStore(service.importNodes(request));
    }
    @PostMapping("/{id}/test")
    public ResponseEntity<ProxyNodeService.TestResult> test(@PathVariable UUID id, HttpServletRequest http) {
        secure(http); return noStore(service.test(id));
    }
    private void secure(HttpServletRequest request) {
        if (!transportGuard.isAllowed(request)) throw new DomainException("PROXY_HTTPS_REQUIRED", "当前请求协议不符合服务器传输配置。", HttpStatus.FORBIDDEN);
    }
    private <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
