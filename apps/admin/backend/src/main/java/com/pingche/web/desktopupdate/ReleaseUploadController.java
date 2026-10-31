package com.pingche.web.desktopupdate;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/release-uploads")
public class ReleaseUploadController {
    private final ReleaseUploadService uploads;
    public ReleaseUploadController(ReleaseUploadService uploads){this.uploads=uploads;}
    @PostMapping public ResponseEntity<?> start(@RequestBody ReleaseUploadService.Start input,Authentication auth){return result(uploads.start(input,(UUID)auth.getPrincipal()));}
    @GetMapping("/{id}") public ResponseEntity<?> state(@PathVariable UUID id,Authentication auth){return result(uploads.status(id,(UUID)auth.getPrincipal()));}
    @PutMapping(value="/{id}/chunks/{index}",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> chunk(@PathVariable UUID id,@PathVariable int index,@RequestHeader(value="X-Chunk-SHA256",required=false) String sha,HttpServletRequest request,Authentication auth) throws IOException {
        return result(uploads.chunk(id,index,sha,request.getContentLengthLong(),request.getInputStream(),(UUID)auth.getPrincipal()));
    }
    @PostMapping("/{id}/complete") public ResponseEntity<?> complete(@PathVariable UUID id,Authentication auth){return result(uploads.finish(id,(UUID)auth.getPrincipal()));}
    @DeleteMapping("/{id}") public ResponseEntity<?> discard(@PathVariable UUID id,Authentication auth){uploads.discard(id,(UUID)auth.getPrincipal());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    private ResponseEntity<?> result(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
