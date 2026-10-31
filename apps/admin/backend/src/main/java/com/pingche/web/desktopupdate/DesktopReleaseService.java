package com.pingche.web.desktopupdate;

import com.pingche.web.auth.DomainException;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DesktopReleaseService {
    public static final long MAX_PACKAGE_BYTES=512L*1024*1024;
    public record Artifact(UUID id,String target,String arch,String filename,String sha256,long sizeBytes,Instant uploadedAt) { }
    public record Release(UUID id,String version,String notes,String status,Instant createdAt,Instant publishedAt,long revision,List<Artifact> artifacts) { }
    public record Info(String currentVersion,String latestVersion,boolean available,String notes,Instant publishedAt,long sizeBytes,String sha256,boolean mandatory) { }
    public record Download(Path path,String filename,long size,String sha256) { }
    private final JdbcTemplate jdbc;
    private final DesktopUpdateSignature signatures;
    private final Clock clock;
    private final Path directory;
    private final ReleaseComponent component;
    private final String releaseTable, artifactTable;

    @Autowired
    public DesktopReleaseService(JdbcTemplate jdbc,DesktopUpdateSignature signatures,Clock clock,
            @Value("${app.desktop-update-storage-dir:${java.io.tmpdir}/cqg-desktop-releases}") String storage) {
        this(jdbc,signatures,clock,storage,ReleaseComponent.DESKTOP);
    }
    DesktopReleaseService(JdbcTemplate jdbc,DesktopUpdateSignature signatures,Clock clock,String storage,ReleaseComponent component) {
        this.jdbc=jdbc;this.signatures=signatures;this.clock=clock;this.component=component;
        this.releaseTable=component==ReleaseComponent.METER?"meter_update_releases":"desktop_update_releases";
        this.artifactTable=component==ReleaseComponent.METER?"meter_update_artifacts":"desktop_update_artifacts";
        this.directory=(component==ReleaseComponent.METER?Path.of(storage).resolve("meter"):Path.of(storage)).toAbsolutePath().normalize();
        if(component==ReleaseComponent.DESKTOP) {
            try { Files.createDirectories(directory.resolve(".uploads")); } catch(IOException ignored) { /* Other modules remain usable. */ }
        }
    }
    @Transactional(readOnly=true)
    public List<Release> list() {
        return jdbc.query("select * from "+releaseTable+" order by version_major desc,version_minor desc,version_patch desc limit 100",this::release);
    }
    @Transactional
    public Release create(String version,String notes,UUID administrator) {
        var v=DesktopVersion.parse(version);
        if(jdbc.queryForObject("select count(*) from "+releaseTable+" where version=?",Long.class,version)>0)
            throw conflict("该版本号已存在，请编辑草稿或使用更高版本号。");
        UUID id=UUID.randomUUID();
        jdbc.update("insert into "+releaseTable+"(id,version,version_major,version_minor,version_patch,notes,status,created_at,created_by,revision) values(?,?,?,?,?,?,'DRAFT',?,?,0)",
                id,version,v.major(),v.minor(),v.patch(),notes,Timestamp.from(clock.instant()),administrator);
        return get(id,false);
    }
    @Transactional
    public Release upload(UUID releaseId,long revision,String target,String arch,MultipartFile installer,MultipartFile signature,UUID administrator) {
        if(signature.isEmpty() || signature.getSize()>32768) throw invalid("签名文件不能为空且须小于 32 KiB。");
        try {
            return uploadPackage(releaseId,revision,target,arch,installer.getOriginalFilename(),installer.getSize(),
                    new String(signature.getBytes(),java.nio.charset.StandardCharsets.UTF_8).trim(),administrator,installer::getInputStream);
        } catch(IOException failure) {throw unavailable();}
    }
    @Transactional
    public Release uploadFile(UUID releaseId,long revision,String target,String arch,String filename,Path file,String signature,UUID administrator) {
        try { return uploadPackage(releaseId,revision,target,arch,filename,Files.size(file),signature,administrator,()->Files.newInputStream(file)); }
        catch(IOException failure) {throw unavailable();}
    }
    public long maximumBytes() {return component.maximumBytes();}
    public void checkDraft(UUID id,long revision,String target,String arch) {
        platform(target,arch);var release=get(id,false);
        if(release.revision()!=revision || !"DRAFT".equals(release.status()) || release.publishedAt()!=null) throw conflict("版本已发布或已变化，请刷新版本列表。");
    }
    @Transactional(readOnly=true)
    public Release getRelease(UUID id) {return get(id,false);}
    @FunctionalInterface private interface Source {InputStream open() throws IOException;}
    private Release uploadPackage(UUID releaseId,long revision,String target,String arch,String filename,long size,String encodedSignature,UUID administrator,Source source) {
        platform(target,arch);
        Release release=get(releaseId,true);
        if(release.revision()!=revision || !"DRAFT".equals(release.status()) || release.publishedAt()!=null) throw conflict("版本已发布或已变化，不能替换安装包。");
        if(size<=0 || size>maximumBytes()) throw invalid("安装包大小超出允许范围。");
        String original=Optional.ofNullable(filename).orElse("");
        String extension=switch(target) {case "windows"->".exe";case "darwin"->".app.tar.gz";default->".AppImage";};
        if(!original.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT)))
            throw invalid(component==ReleaseComponent.METER?"统计程序请上传独立的 .exe 文件及其 .sig 签名。":"Windows 请上传 NSIS .exe，macOS 上传 .app.tar.gz，Linux 上传 .AppImage，并提供各自的 .sig 签名。");
        UUID id=UUID.randomUUID();String storageName=id+extension;
        Path staged=directory.resolve(".uploads").resolve(id+".part"), destination=storedPath(storageName);
        try {
            Files.createDirectories(staged.getParent());
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream input=source.open();OutputStream output=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[65536];int read;
                while((read=input.read(buffer))!=-1) {
                    count+=read;if(count>maximumBytes())throw invalid("安装包超过允许大小。");
                    digest.update(buffer,0,read);output.write(buffer,0,read);
                }
            }
            if(count!=size || count<4) throw invalid("安装包大小无效或上传未完成。");
            try(InputStream input=Files.newInputStream(staged)) {
                byte[] magic=input.readNBytes(4);
                boolean valid=switch(target) {
                    case "windows"->magic[0]=='M'&&magic[1]=='Z';
                    case "darwin"->(magic[0]&255)==0x1f&&(magic[1]&255)==0x8b;
                    default->magic[0]==0x7f&&magic[1]=='E'&&magic[2]=='L'&&magic[3]=='F';
                };
                if(!valid)throw invalid("安装包内容与所选系统不匹配。");
            }
            if(component==ReleaseComponent.METER) checkPeArchitecture(staged,arch);
            signatures.verify(staged,encodedSignature,release.version());
            String sha=HexFormat.of().formatHex(digest.digest());
            var old=jdbc.queryForList("select storage_name from "+artifactTable+" where release_id=? and target=? and arch=?",releaseId,target,arch);
            Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) remove(destination); }
                @Override public void afterCommit() { old.forEach(row->remove(storedPath((String)row.get("storage_name")))); }
            });
            jdbc.update("delete from "+artifactTable+" where release_id=? and target=? and arch=?",releaseId,target,arch);
            String downloadName=(component==ReleaseComponent.METER?"Codex.Quota.Guard.Meter_":"Codex.Quota.Guard_")+release.version()+"_"+target+"-"+arch+extension;
            jdbc.update("insert into "+artifactTable+"(id,release_id,target,arch,filename,storage_name,signature,sha256,size_bytes,uploaded_at,uploaded_by) values(?,?,?,?,?,?,?,?,?,?,?)",
                    id,releaseId,target,arch,downloadName,storageName,encodedSignature,sha,count,Timestamp.from(clock.instant()),administrator);
            jdbc.update("update "+releaseTable+" set revision=revision+1 where id=?",releaseId);
            return get(releaseId,false);
        } catch(DomainException failure) {throw failure;}
        catch(IOException|GeneralSecurityException failure) {throw unavailable();}
        finally {remove(staged);}
    }
    @Transactional
    public Release status(UUID id,long revision,String action) {
        Release release=get(id,true);
        if(release.revision()!=revision) throw conflict("版本已变化，请刷新列表。");
        if(!List.of("publish","withdraw").contains(action)) throw invalid("无效的发布操作。");
        String status;
        if("publish".equals(action)) {
            if(!List.of("DRAFT","WITHDRAWN").contains(release.status()))throw conflict("该版本已发布。");
            if(release.artifacts().isEmpty())throw conflict("请先上传至少一个平台的安装包和签名。");
            for(var file:release.artifacts()) {
                var rows=jdbc.queryForMap("select storage_name from "+artifactTable+" where id=?",file.id());
                if(!Files.isRegularFile(storedPath((String)rows.get("storage_name"))))throw unavailable();
            }
            status="PUBLISHED";
        } else {
            if(!"PUBLISHED".equals(release.status()))throw conflict("仅可撤回已发布版本。");
            status="WITHDRAWN";
        }
        jdbc.update("update "+releaseTable+" set status=?,published_at=coalesce(published_at,?),revision=revision+1 where id=?",
                status,Timestamp.from(clock.instant()),id);
        return get(id,false);
    }
    @Transactional(readOnly=true)
    public Info info(String target,String arch,String currentVersion) {
        var current=DesktopVersion.parse(currentVersion);platform(target,arch);
        var row=latest(target,arch);
        if(row==null)return new Info(currentVersion,null,false,"",null,0,null,component==ReleaseComponent.METER);
        String latest=(String)row.get("version");
        return new Info(currentVersion,latest,DesktopVersion.parse(latest).compareTo(current)>0,(String)row.get("notes"),
                instant(row.get("published_at")),((Number)row.get("size_bytes")).longValue(),(String)row.get("sha256"),component==ReleaseComponent.METER);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> manifest(String target,String arch,String currentVersion,String baseUrl) {
        var info=info(target,arch,currentVersion);if(!info.available())return null;
        var row=latest(target,arch);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("version",row.get("version"));result.put("notes",row.get("notes"));result.put("pub_date",info.publishedAt().toString());
        result.put("url",baseUrl+(component==ReleaseComponent.METER?"/api/v1/meter-updates/assets/":"/api/v1/desktop-updates/assets/")+row.get("id")+"/"+row.get("filename"));
        result.put("signature",row.get("signature"));result.put("sha256",row.get("sha256"));result.put("sizeBytes",row.get("size_bytes"));result.put("mandatory",component==ReleaseComponent.METER);
        return result;
    }
    @Transactional(readOnly=true)
    public Download download(UUID id,String filename) {
        var rows=jdbc.queryForList("select a.* from "+artifactTable+" a join "+releaseTable+" r on r.id=a.release_id where a.id=? and r.status='PUBLISHED'",id);
        if(rows.isEmpty() || !filename.equals(rows.get(0).get("filename")))throw missing();
        var row=rows.get(0);Path file=storedPath((String)row.get("storage_name"));
        if(!Files.isRegularFile(file))throw missing();
        return new Download(file,filename,((Number)row.get("size_bytes")).longValue(),(String)row.get("sha256"));
    }
    public String publicKey() {return signatures.publicKey();}
    private Instant instant(Object value) {
        if(value instanceof OffsetDateTime time) return time.toInstant();
        if(value instanceof Timestamp time) return time.toInstant();
        if(value instanceof Instant time) return time;
        throw unavailable();
    }
    private Map<String,Object> latest(String target,String arch) {
        var rows=jdbc.queryForList("select a.*,r.version,r.notes,r.published_at from "+artifactTable+" a join "+releaseTable+" r on r.id=a.release_id "
                +"where r.status='PUBLISHED' and a.target=? and a.arch=? order by r.version_major desc,r.version_minor desc,r.version_patch desc limit 1",target,arch);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Release get(UUID id,boolean lock) {
        return jdbc.query("select * from "+releaseTable+" where id=?"+(lock?" for update":""),this::release,id).stream().findFirst().orElseThrow(this::missing);
    }
    private Release release(ResultSet rs,int n) throws SQLException {
        UUID id=rs.getObject("id",UUID.class);Timestamp published=rs.getTimestamp("published_at");
        var files=jdbc.query("select * from "+artifactTable+" where release_id=? order by target,arch",(row,index)->new Artifact(row.getObject("id",UUID.class),
                row.getString("target"),row.getString("arch"),row.getString("filename"),row.getString("sha256"),row.getLong("size_bytes"),row.getTimestamp("uploaded_at").toInstant()),id);
        return new Release(id,rs.getString("version"),rs.getString("notes"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),
                published==null?null:published.toInstant(),rs.getLong("revision"),files);
    }
    private void platform(String target,String arch) {
        if(target==null || arch==null || (component==ReleaseComponent.METER && !"windows".equals(target)) || !List.of("windows","darwin","linux").contains(target) || !List.of("x86_64","aarch64").contains(arch))throw invalid("不支持的系统或架构。");
    }
    private void checkPeArchitecture(Path file,String arch) throws IOException {
        try(var input=new RandomAccessFile(file.toFile(),"r")) {
            if(input.length()<64) throw invalid("统计程序不是有效的 Windows 可执行文件。");
            input.seek(0x3c);long offset=Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if(offset>input.length()-6)throw invalid("统计程序的 PE 文件头无效。");
            input.seek(offset);if(input.readInt()!=0x50450000)throw invalid("统计程序的 PE 文件头无效。");
            int machine=Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
            if(machine!=(arch.equals("x86_64")?0x8664:0xaa64))throw invalid("统计程序的 CPU 架构与所选架构不一致。");
        }
    }
    private Path storedPath(String name) {
        if(!name.matches("[a-f0-9-]{36}\\.(exe|AppImage|app\\.tar\\.gz)"))throw unavailable();
        Path path=directory.resolve(name).normalize();if(!path.startsWith(directory))throw unavailable();return path;
    }
    private void remove(Path path) {try {Files.deleteIfExists(path);}catch(IOException ignored){}}
    private DomainException invalid(String text) {return new DomainException("DESKTOP_RELEASE_INVALID",text,HttpStatus.BAD_REQUEST);}
    private DomainException conflict(String text) {return new DomainException("DESKTOP_RELEASE_CONFLICT",text,HttpStatus.CONFLICT);}
    private DomainException missing() {return new DomainException("DESKTOP_RELEASE_NOT_FOUND","版本或安装包不存在，或已撤回。",HttpStatus.NOT_FOUND);}
    private DomainException unavailable() {return new DomainException("DESKTOP_STORAGE_UNAVAILABLE","安装包存储不可用，请检查服务器更新包数据卷和目录权限。",HttpStatus.SERVICE_UNAVAILABLE);}
}
