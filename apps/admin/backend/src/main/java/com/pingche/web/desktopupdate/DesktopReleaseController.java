package com.pingche.web.desktopupdate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/desktop-releases")
public class DesktopReleaseController {
    public record Create(@NotBlank @Size(max=32) String version,@NotNull @Size(max=4000) String notes) { }
    public record Change(@NotNull @Min(0) Long revision) { }
    private final DesktopReleaseService releases;
    public DesktopReleaseController(DesktopReleaseService releases) {this.releases=releases;}
    @GetMapping public ResponseEntity<List<DesktopReleaseService.Release>> list() {return result(releases.list());}
    @GetMapping("/signing-key") public ResponseEntity<Map<String,String>> key() {return result(Map.of("publicKey",releases.publicKey()));}
    @PostMapping public ResponseEntity<DesktopReleaseService.Release> create(@Valid @RequestBody Create request,Authentication auth) {
        return result(releases.create(request.version(),request.notes(),(UUID)auth.getPrincipal()));
    }
    @PostMapping(value="/{id}/artifacts",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DesktopReleaseService.Release> upload(@PathVariable UUID id,@RequestParam @Min(0) long revision,
            @RequestParam String target,@RequestParam String arch,@RequestPart MultipartFile installer,@RequestPart MultipartFile signature,Authentication auth) {
        return result(releases.upload(id,revision,target,arch,installer,signature,(UUID)auth.getPrincipal()));
    }
    @PostMapping("/{id}/{action:publish|withdraw}")
    public ResponseEntity<DesktopReleaseService.Release> status(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody Change change) {
        return result(releases.status(id,change.revision(),action));
    }
    private <T> ResponseEntity<T> result(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
