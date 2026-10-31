package com.pingche.web.desktopupdate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Same release rules, separate versions, publisher key and artifact namespace. */
@Service
public class MeterReleaseService {
    private final DesktopReleaseService catalog;
    public MeterReleaseService(JdbcTemplate jdbc,Clock clock,
            @Value("${app.desktop-update-storage-dir:${java.io.tmpdir}/cqg-desktop-releases}") String storage,
            @Value("${app.meter-update-public-key:}") String publicKey) {
        String key=publicKey;
        if(key.isBlank()) {
            try(var input=new ClassPathResource("meter-update-public.key").getInputStream()) {key=new String(input.readAllBytes(),StandardCharsets.UTF_8).trim();}
            catch(Exception error) {throw new IllegalStateException("Meter publisher public key unavailable");}
        }
        catalog=new DesktopReleaseService(jdbc,new DesktopUpdateSignature(key),clock,storage,ReleaseComponent.METER);
    }
    @Transactional(readOnly=true) public List<DesktopReleaseService.Release> list(){return catalog.list();}
    @Transactional public DesktopReleaseService.Release create(String version,String notes,UUID administrator){return catalog.create(version,notes,administrator);}
    @Transactional public DesktopReleaseService.Release status(UUID id,long revision,String action){return catalog.status(id,revision,action);}
    @Transactional(readOnly=true) public DesktopReleaseService.Info info(String target,String arch,String current){return catalog.info(target,arch,current);}
    @Transactional(readOnly=true) public Map<String,Object> manifest(String target,String arch,String current,String base){return catalog.manifest(target,arch,current,base);}
    @Transactional(readOnly=true) public DesktopReleaseService.Download download(UUID id,String filename){return catalog.download(id,filename);}
    @Transactional(readOnly=true) public void checkDraft(UUID id,long revision,String target,String arch){catalog.checkDraft(id,revision,target,arch);}
    @Transactional(readOnly=true) public DesktopReleaseService.Release getRelease(UUID id){return catalog.getRelease(id);}
    @Transactional public DesktopReleaseService.Release uploadFile(UUID id,long revision,String target,String arch,String filename,Path file,String signature,UUID administrator){return catalog.uploadFile(id,revision,target,arch,filename,file,signature,administrator);}
    public String publicKey(){return catalog.publicKey();}
}
