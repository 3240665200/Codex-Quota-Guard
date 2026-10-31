package com.pingche.web.desktopupdate;

import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/desktop-updates")
public class DesktopUpdateController {
    private final DesktopReleaseService releases;
    public DesktopUpdateController(DesktopReleaseService releases) {this.releases=releases;}
    @GetMapping("/info/{target}/{arch}/{current}")
    public ResponseEntity<DesktopReleaseService.Info> info(@PathVariable String target,@PathVariable String arch,@PathVariable String current) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(releases.info(target,arch,current));
    }
    @GetMapping("/manifest/{target}/{arch}/{current}")
    public ResponseEntity<Map<String,Object>> manifest(@PathVariable String target,@PathVariable String arch,@PathVariable String current) {
        String base=ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        var manifest=releases.manifest(target,arch,current,base);
        return manifest==null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(manifest);
    }
    @GetMapping("/assets/{id}/{filename}")
    public ResponseEntity<Resource> download(@PathVariable UUID id,@PathVariable String filename) {
        var asset=releases.download(id,filename);
        return ResponseEntity.ok().contentLength(asset.size()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore()).eTag('"'+asset.sha256()+'"')
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(asset.filename()).build().toString())
                .body(new FileSystemResource(asset.path()));
    }
}
