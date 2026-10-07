package com.pingche.web.meter;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMeterDeviceController {
    private final MeterDeviceService devices;

    public AdminMeterDeviceController(MeterDeviceService devices) { this.devices = devices; }

    @GetMapping("/{id}/meter-devices")
    public List<MeterDeviceStatusResponse> list(@PathVariable UUID id) {
        return devices.listForUser(id);
    }

    @DeleteMapping("/{id}/meter-devices/{deviceId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, @PathVariable UUID deviceId) {
        devices.revoke(id, deviceId);
        return ResponseEntity.noContent().build();
    }
}
