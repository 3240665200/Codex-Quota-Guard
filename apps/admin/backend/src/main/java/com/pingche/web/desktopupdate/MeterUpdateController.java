package com.pingche.web.desktopupdate;

import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/meter-updates")
public class MeterUpdateController {
    private final MeterReleaseService releases;
    public MeterUpdateController(MeterReleaseService releases){this.releases=releases;}
    @GetMapping("/info/{target}/{arch}/{current}") public ResponseEntity<?> info(@PathVariable String target,@PathVariable String arch,@PathVariable String current){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(releases.info(target,arch,current));}
    @GetMapping("/manifest/{target}/{arch}/{current}") public ResponseEntity<?> manifest(@PathVariable String target,@PathVariable String arch,@PathVariable String current){
        var value=releases.manifest(target,arch,current,ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
        return value==null?ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build():ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
    @GetMapping("/assets/{id}/{filename}") public ResponseEntity<Resource> file(@PathVariable UUID id,@PathVariable String filename){
        var asset=releases.download(id,filename);
        return ResponseEntity.ok().contentLength(asset.size()).contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(asset.filename()).build().toString()).body(new FileSystemResource(asset.path()));
    }
}
