package com.pingche.web.desktopupdate;

import com.pingche.web.auth.DomainException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReleaseUploadService {
    public static final int CHUNK_BYTES=4*1024*1024;
    public record Start(ReleaseComponent component,UUID releaseId,long revision,String target,String arch,
            String filename,long sizeBytes,String sha256,String signature) { }
    public record State(UUID id,ReleaseComponent component,UUID releaseId,String status,int chunkSize,int chunkCount,
            List<Integer> receivedChunks,long receivedBytes,Instant expiresAt) { }
    private record Upload(UUID id,ReleaseComponent component,UUID releaseId,long revision,String target,String arch,String filename,
            long size,String sha,String signature,String status,int count,Instant expiresAt) { }
    private final JdbcTemplate jdbc;
    private final DesktopReleaseService desktop;
    private final MeterReleaseService meter;
    private final Clock clock;
    private final Path root;
    public ReleaseUploadService(JdbcTemplate jdbc,DesktopReleaseService desktop,MeterReleaseService meter,Clock clock,
            @Value("${app.desktop-update-storage-dir:${java.io.tmpdir}/cqg-desktop-releases}") String storage) {
        this.jdbc=jdbc;this.desktop=desktop;this.meter=meter;this.clock=clock;root=Path.of(storage).toAbsolutePath().normalize().resolve(".upload-parts");
    }
    @Transactional
    public State start(Start input,UUID administrator) {
        validate(input);
        checkDraft(input.component(),input.releaseId(),input.revision(),input.target(),input.arch());
        String fingerprint=hash((input.component()+"\n"+input.releaseId()+"\n"+input.target()+"\n"+input.arch()+"\n"+input.filename()+"\n"+input.sizeBytes()+"\n"+input.sha256().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        Instant now=clock.instant();
        jdbc.queryForObject("select id from users where id=? for update",UUID.class,administrator);
        var resumable=jdbc.queryForList("select id from release_upload_sessions where administrator_id=? and fingerprint=? and status='OPEN' and expires_at>? order by created_at desc limit 1",administrator,fingerprint,Timestamp.from(now));
        UUID id;
        if(!resumable.isEmpty()) {
            id=(UUID)resumable.get(0).get("id");
            read(id,administrator,true);
            jdbc.update("update release_upload_sessions set release_revision=?,signature=?,updated_at=?,expires_at=? where id=?",input.revision(),input.signature().trim(),Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)),id);
        } else {
            // Serialize starts for this administrator so parallel requests cannot exceed the quota.
            Long active=jdbc.queryForObject("select count(*) from release_upload_sessions where administrator_id=? and status='OPEN' and expires_at>?",Long.class,administrator,Timestamp.from(now));
            if(active!=null && active>=4)throw conflict("未完成的上传任务过多，请先放弃不再需要的任务。");
            id=UUID.randomUUID();
            jdbc.update("insert into release_upload_sessions(id,component,release_id,release_revision,target,arch,filename,size_bytes,sha256,signature,fingerprint,administrator_id,chunk_size,chunk_count,status,created_at,updated_at,expires_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?)",
                    id,input.component().name(),input.releaseId(),input.revision(),input.target(),input.arch(),input.filename(),input.sizeBytes(),input.sha256().toLowerCase(Locale.ROOT),input.signature().trim(),fingerprint,administrator,CHUNK_BYTES,(int)((input.sizeBytes()+CHUNK_BYTES-1)/CHUNK_BYTES),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)));
        }
        return state(read(id,administrator,false));
    }
    @Transactional(readOnly=true)
    public State status(UUID id,UUID administrator){return state(read(id,administrator,false));}
    @Transactional
    public State chunk(UUID id,int index,String sha,long contentLength,InputStream input,UUID administrator) {
        var upload=read(id,administrator,true);requireOpen(upload);
        if(index<0 || index>=upload.count() || sha==null || !sha.matches("[A-Fa-f0-9]{64}"))throw invalid("分块编号或校验值无效。");
        long expected=Math.min(CHUNK_BYTES,upload.size()-(long)index*CHUNK_BYTES);
        if(contentLength>=0 && contentLength!=expected)throw invalid("分块大小不符，请重新上传该分块。");
        Path directory=directory(id);Path pending=directory.resolve("pending-"+UUID.randomUUID()+".tmp");
        try {
            Files.createDirectories(directory);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long received=0;
            try(OutputStream out=Files.newOutputStream(pending,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1) {received+=count;if(received>expected)throw invalid("分块超过预期大小。");digest.update(buffer,0,count);out.write(buffer,0,count);}
            }
            String actual=HexFormat.of().formatHex(digest.digest());
            if(received!=expected || !actual.equalsIgnoreCase(sha))throw invalid("分块校验失败，请重新上传该分块。");
            var stored=jdbc.queryForList("select sha256 from release_upload_chunks where upload_id=? and chunk_index=?",id,index);
            if(!stored.isEmpty()) {
                if(!actual.equals(stored.get(0).get("sha256")))throw conflict("该分块内容与已保存数据不一致，请重新选择相同文件。");
                // Rewrite an identical chunk if storage was lost, never trust only the database bitmap.
            } else jdbc.update("insert into release_upload_chunks(upload_id,chunk_index,size_bytes,sha256) values(?,?,?,?)",id,index,(int)received,actual);
            Files.move(pending,part(id,index),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            Instant now=clock.instant();jdbc.update("update release_upload_sessions set updated_at=?,expires_at=? where id=?",Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)),id);
            return state(read(id,administrator,false));
        } catch(DomainException failure){throw failure;}
        catch(Exception failure){throw unavailable();}
        finally {remove(pending);}
    }
    @Transactional
    public DesktopReleaseService.Release finish(UUID id,UUID administrator) {
        var upload=read(id,administrator,true);
        if("COMPLETED".equals(upload.status()))return release(upload.component(),upload.releaseId());
        requireOpen(upload);
        checkDraft(upload.component(),upload.releaseId(),upload.revision(),upload.target(),upload.arch());
        var chunks=jdbc.queryForList("select chunk_index,size_bytes,sha256 from release_upload_chunks where upload_id=? order by chunk_index",id);
        if(chunks.size()!=upload.count())throw conflict("安装包尚未上传完整，请继续上传。");
        Path assembled=directory(id).resolve("assembled.bin");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(OutputStream output=Files.newOutputStream(assembled,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)) {
                for(int index=0;index<upload.count();index++) {
                    var chunk=chunks.get(index);if(((Number)chunk.get("chunk_index")).intValue()!=index)throw conflict("上传分块缺失，请继续上传。");
                    Path path=part(id,index);
                    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=((Number)chunk.get("size_bytes")).longValue())throw conflict("服务器分块缺失，请重新上传缺失分块。");
                    MessageDigest piece=MessageDigest.getInstance("SHA-256");
                    try(InputStream input=Files.newInputStream(path)) {byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){digest.update(buffer,0,count);piece.update(buffer,0,count);output.write(buffer,0,count);total+=count;}}
                    if(!HexFormat.of().formatHex(piece.digest()).equals(chunk.get("sha256")))throw conflict("服务器分块校验失败，请重新上传。");
                }
            }
            if(total!=upload.size() || !HexFormat.of().formatHex(digest.digest()).equals(upload.sha()))throw invalid("安装包整体校验失败，请确认选择了同一个安装包。");
            var result=upload.component()==ReleaseComponent.DESKTOP
                ?desktop.uploadFile(upload.releaseId(),upload.revision(),upload.target(),upload.arch(),upload.filename(),assembled,upload.signature(),administrator)
                :meter.uploadFile(upload.releaseId(),upload.revision(),upload.target(),upload.arch(),upload.filename(),assembled,upload.signature(),administrator);
            jdbc.update("update release_upload_sessions set status='COMPLETED',updated_at=? where id=?",Timestamp.from(clock.instant()),id);
            afterCommit(()->cleanup(id));
            return result;
        } catch(DomainException failure){throw failure;}
        catch(Exception failure){throw unavailable();}
        finally {remove(assembled);}
    }
    @Transactional
    public void discard(UUID id,UUID administrator) {
        var upload=read(id,administrator,true);
        if("COMPLETED".equals(upload.status()))throw conflict("安装包已完成上传，请刷新版本列表。");
        jdbc.update("update release_upload_sessions set status='EXPIRED' where id=?",id);afterCommit(()->cleanup(id));
    }
    @Scheduled(initialDelay=3600000,fixedDelay=3600000)
    @Transactional
    public void expire() {
        var ids=jdbc.queryForList("select id from release_upload_sessions where status='OPEN' and expires_at<=? limit 20",Timestamp.from(clock.instant()));
        for(var row:ids){UUID id=(UUID)row.get("id");int changed=jdbc.update("update release_upload_sessions set status='EXPIRED' where id=? and status='OPEN' and expires_at<=?",id,Timestamp.from(clock.instant()));if(changed>0)afterCommit(()->cleanup(id));}
    }
    private void validate(Start input) {
        if(input==null || input.component()==null || input.releaseId()==null || input.revision()<0 || input.sizeBytes()<4 || input.sizeBytes()>input.component().maximumBytes()
                || input.sha256()==null || !input.sha256().matches("[A-Fa-f0-9]{64}") || input.filename()==null || input.filename().length()>200 || input.filename().isBlank()
                || input.filename().contains("/") || input.filename().contains("\\") || input.filename().chars().anyMatch(Character::isISOControl)
                || input.signature()==null || input.signature().isBlank() || input.signature().getBytes(StandardCharsets.UTF_8).length>32768)throw invalid("上传文件信息无效或超过大小限制。");
    }
    private void checkDraft(ReleaseComponent component,UUID id,long revision,String target,String arch){if(component==ReleaseComponent.DESKTOP)desktop.checkDraft(id,revision,target,arch);else meter.checkDraft(id,revision,target,arch);}
    private DesktopReleaseService.Release release(ReleaseComponent component,UUID id){return component==ReleaseComponent.DESKTOP?desktop.getRelease(id):meter.getRelease(id);}
    private Upload read(UUID id,UUID administrator,boolean lock) {
        return jdbc.query("select * from release_upload_sessions where id=? and administrator_id=?"+(lock?" for update":""),(rs,n)->new Upload(rs.getObject("id",UUID.class),ReleaseComponent.valueOf(rs.getString("component")),rs.getObject("release_id",UUID.class),rs.getLong("release_revision"),rs.getString("target"),rs.getString("arch"),rs.getString("filename"),rs.getLong("size_bytes"),rs.getString("sha256"),rs.getString("signature"),rs.getString("status"),rs.getInt("chunk_count"),rs.getTimestamp("expires_at").toInstant()),id,administrator).stream().findFirst().orElseThrow(()->new DomainException("UPLOAD_NOT_FOUND","上传任务不存在或不属于当前管理员。",HttpStatus.NOT_FOUND));
    }
    private State state(Upload upload) {
        List<Integer> received="COMPLETED".equals(upload.status())?IntStream.range(0,upload.count()).boxed().toList():jdbc.query("select chunk_index from release_upload_chunks where upload_id=? order by chunk_index",(rs,n)->rs.getInt(1),upload.id()).stream().filter(index->Files.isRegularFile(part(upload.id(),index),LinkOption.NOFOLLOW_LINKS)).toList();
        long bytes=received.stream().mapToLong(index->Math.min(CHUNK_BYTES,upload.size()-(long)index*CHUNK_BYTES)).sum();
        return new State(upload.id(),upload.component(),upload.releaseId(),upload.status(),CHUNK_BYTES,upload.count(),received,bytes,upload.expiresAt());
    }
    private void requireOpen(Upload upload){if(!"OPEN".equals(upload.status()) || !upload.expiresAt().isAfter(clock.instant()))throw conflict("上传任务已过期，请重新选择安装包开始上传。");}
    private Path directory(UUID id){Path path=root.resolve(id.toString()).normalize();if(!path.getParent().equals(root))throw unavailable();return path;}
    private Path part(UUID id,int index){return directory(id).resolve("chunk-"+index+".bin");}
    private void cleanup(UUID id){Path directory=directory(id);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))return;try(var paths=Files.list(directory)){for(Path path:paths.toList())if(path.getParent().equals(directory) && !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))remove(path);}catch(IOException ignored){}remove(directory);}
    private void afterCommit(Runnable work){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){work.run();}});}
    private static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception failure){throw new IllegalStateException(failure);}}
    private void remove(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    private DomainException invalid(String message){return new DomainException("UPLOAD_INVALID",message,HttpStatus.BAD_REQUEST);}
    private DomainException conflict(String message){return new DomainException("UPLOAD_CONFLICT",message,HttpStatus.CONFLICT);}
    private DomainException unavailable(){return new DomainException("UPLOAD_STORAGE_UNAVAILABLE","上传存储不可用，请稍后重试。",HttpStatus.SERVICE_UNAVAILABLE);}
}
