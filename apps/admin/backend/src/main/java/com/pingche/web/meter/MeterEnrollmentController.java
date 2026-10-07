package com.pingche.web.meter;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/client/meter")
public class MeterEnrollmentController {
    private final MeterDeviceService devices;

    public MeterEnrollmentController(MeterDeviceService devices) { this.devices = devices; }

    @PostMapping("/enroll")
    public ResponseEntity<MeterEnrollmentResponse> enroll(Authentication authentication,
            @Valid @RequestBody MeterEnrollmentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(devices.enroll((UUID) authentication.getPrincipal(), request.deviceId()));
    }
}
