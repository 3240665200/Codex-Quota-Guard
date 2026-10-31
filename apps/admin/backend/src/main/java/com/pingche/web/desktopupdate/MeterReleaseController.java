package com.pingche.web.desktopupdate;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/meter-releases")
public class MeterReleaseController {
    private final MeterReleaseService releases;
    public MeterReleaseController(MeterReleaseService releases){this.releases=releases;}
    @GetMapping public ResponseEntity<?> list(){return result(releases.list());}
    @GetMapping("/signing-key") public ResponseEntity<?> key(){return result(Map.of("publicKey",releases.publicKey()));}
    @PostMapping public ResponseEntity<?> create(@Valid @RequestBody DesktopReleaseController.Create body,Authentication auth){return result(releases.create(body.version(),body.notes(),(UUID)auth.getPrincipal()));}
    @PostMapping("/{id}/{action:publish|withdraw}") public ResponseEntity<?> status(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody DesktopReleaseController.Change body){return result(releases.status(id,body.revision(),action));}
    private ResponseEntity<?> result(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
