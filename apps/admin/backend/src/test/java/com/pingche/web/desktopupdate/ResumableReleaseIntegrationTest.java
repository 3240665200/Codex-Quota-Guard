package com.pingche.web.desktopupdate;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;
import com.pingche.web.user.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ResumableReleaseIntegrationTest {
    static final KeyPair DESKTOP=keys(),METER=keys();
    static final byte[] ID={9,8,7,6,5,4,3,2};
    static final Path ROOT=storage();
    static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("app.desktop-update-public-key",()->publicKey(DESKTOP));r.add("app.meter-update-public-key",()->publicKey(METER));r.add("app.desktop-update-storage-dir",ROOT::toString);}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired UserRepository users;@Autowired com.pingche.web.auth.JwtService jwt;
    @Autowired ReleaseUploadService uploads;@MockitoBean Clock clock;
    User admin,other,member;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(NOW);
        for(String table:List.of("release_upload_chunks","release_upload_sessions","desktop_update_artifacts","desktop_update_releases","meter_update_artifacts","meter_update_releases"))jdbc.update("delete from "+table);
        admin=user("resume-admin@example.com",true);other=user("resume-other@example.com",true);member=user("resume-member@example.com",false);
    }
    @AfterAll static void cleanup() throws Exception {try(var paths=Files.walk(ROOT)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){assertThat(path.toAbsolutePath().normalize()).startsWith(ROOT.toAbsolutePath().normalize());Files.delete(path);}}}
    User user(String email,boolean admin){return users.findByEmail(email).orElseGet(()->users.saveAndFlush(admin?User.bootstrapAdministrator(email,"synthetic","Synthetic admin"):new User(email,"synthetic","Synthetic member")));}
    String auth(User user){return "Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion());}
    String base(String kind){return "/api/v1/admin/"+kind+"-releases";}
    DesktopReleaseService.Release create(String kind,String version) throws Exception {return json.readValue(mvc.perform(post(base(kind)).header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version",version,"notes","Synthetic resume test")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),DesktopReleaseService.Release.class);}
    ReleaseUploadService.State start(String kind,DesktopReleaseService.Release release,byte[] file,String signature) throws Exception {
        var body=new ReleaseUploadService.Start(ReleaseComponent.valueOf(kind.toUpperCase(Locale.ROOT)),release.id(),release.revision(),"windows","x86_64","test.exe",file.length,hash(file),signature);
        return json.readValue(mvc.perform(post("/api/v1/admin/release-uploads").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),ReleaseUploadService.State.class);
    }
    ReleaseUploadService.State chunk(ReleaseUploadService.State state,int index,byte[] data) throws Exception {return json.readValue(mvc.perform(put("/api/v1/admin/release-uploads/"+state.id()+"/chunks/"+index).header("Authorization",auth(admin)).header("X-Chunk-SHA256",hash(data)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(data)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),ReleaseUploadService.State.class);}
    DesktopReleaseService.Release finish(ReleaseUploadService.State state) throws Exception {return json.readValue(mvc.perform(post("/api/v1/admin/release-uploads/"+state.id()+"/complete").header("Authorization",auth(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),DesktopReleaseService.Release.class);}
    @Test void resumesAcrossRequestsSkipsCompletedChunksAndCompletesOnlyOnce() throws Exception {
        byte[] file=new byte[ReleaseUploadService.CHUNK_BYTES+89];Arrays.fill(file,(byte)7);file[0]='M';file[1]='Z';
        var release=create("desktop","1.2.3");String signature=signature(file,"1.2.3",DESKTOP);
        var upload=start("desktop",release,file,signature);
        mvc.perform(post("/api/v1/admin/release-uploads/"+upload.id()+"/complete").header("Authorization",auth(admin))).andExpect(status().isConflict());
        var first=Arrays.copyOf(file,upload.chunkSize());
        assertThat(chunk(upload,0,first).receivedBytes()).isEqualTo(upload.chunkSize());
        assertThat(chunk(upload,0,first).receivedBytes()).isEqualTo(upload.chunkSize());
        var resumed=start("desktop",release,file,signature);
        assertThat(resumed.id()).isEqualTo(upload.id());assertThat(resumed.receivedChunks()).containsExactly(0);
        chunk(resumed,1,Arrays.copyOfRange(file,resumed.chunkSize(),file.length));
        var result=finish(resumed);assertThat(result.artifacts()).hasSize(1);assertThat(result.revision()).isEqualTo(1);
        assertThat(finish(resumed).artifacts().get(0).id()).isEqualTo(result.artifacts().get(0).id());
        assertThat(jdbc.queryForObject("select count(*) from desktop_update_artifacts",Long.class)).isEqualTo(1);
        assertThat(Files.exists(ROOT.resolve(".upload-parts").resolve(upload.id().toString()))).isFalse();
    }
    @Test void validatesChunkIntegrityOwnershipAndCancellation() throws Exception {
        byte[] file="MZ-synthetic-data".getBytes(StandardCharsets.UTF_8);var release=create("desktop","2.0.0");var upload=start("desktop",release,file,signature(file,"2.0.0",DESKTOP));
        mvc.perform(put("/api/v1/admin/release-uploads/"+upload.id()+"/chunks/0").header("Authorization",auth(admin)).header("X-Chunk-SHA256","0".repeat(64)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(file)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/release-uploads/"+upload.id()).header("Authorization",auth(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/release-uploads/"+upload.id()).header("Authorization",auth(member))).andExpect(status().isForbidden());
        chunk(upload,0,file);
        mvc.perform(delete("/api/v1/admin/release-uploads/"+upload.id()).header("Authorization",auth(admin))).andExpect(status().isNoContent());
        assertThat(Files.exists(ROOT.resolve(".upload-parts").resolve(upload.id().toString()))).isFalse();
        mvc.perform(post("/api/v1/admin/release-uploads/"+upload.id()+"/complete").header("Authorization",auth(admin))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from desktop_update_artifacts",Long.class)).isZero();
    }
    @Test void expiredOrPublishedUploadsCannotAlterArtifacts() throws Exception {
        byte[] file="MZ-synthetic-data".getBytes(StandardCharsets.UTF_8);var release=create("desktop","3.0.0");var upload=start("desktop",release,file,signature(file,"3.0.0",DESKTOP));chunk(upload,0,file);
        when(clock.instant()).thenReturn(NOW.plusSeconds(86401));uploads.expire();
        mvc.perform(post("/api/v1/admin/release-uploads/"+upload.id()+"/complete").header("Authorization",auth(admin))).andExpect(status().isConflict());
        when(clock.instant()).thenReturn(NOW);
        var renewed=start("desktop",release,file,signature(file,"3.0.0",DESKTOP));assertThat(renewed.id()).isNotEqualTo(upload.id());
        chunk(renewed,0,file);var complete=finish(renewed);
        mvc.perform(post(base("desktop")+"/"+release.id()+"/publish").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":"+complete.revision()+"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/release-uploads").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ReleaseUploadService.Start(ReleaseComponent.DESKTOP,release.id(),complete.revision()+1,"windows","x86_64","test.exe",file.length,hash(file),signature(file,"3.0.0",DESKTOP))))).andExpect(status().isConflict());
    }
    @Test void meterVersionsUseAnIndependentKeyAndMandatoryChannel() throws Exception {
        var desktop=create("desktop","0.2.13");var meter=create("meter","0.2.13");assertThat(desktop.id()).isNotEqualTo(meter.id());
        byte[] file=pe();var wrong=start("meter",meter,file,signature(file,"0.2.13",DESKTOP));chunk(wrong,0,file);
        mvc.perform(post("/api/v1/admin/release-uploads/"+wrong.id()+"/complete").header("Authorization",auth(admin))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESKTOP_SIGNATURE_INVALID"));
        var resumed=start("meter",meter,file,signature(file,"0.2.13",METER));assertThat(resumed.id()).isEqualTo(wrong.id());assertThat(resumed.receivedChunks()).containsExactly(0);
        var complete=finish(resumed);
        mvc.perform(post(base("meter")+"/"+meter.id()+"/publish").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":"+complete.revision()+"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/meter-updates/info/windows/x86_64/0.2.12")).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.mandatory").value(true));
        mvc.perform(get("/api/v1/meter-updates/manifest/windows/x86_64/0.2.12")).andExpect(status().isOk()).andExpect(jsonPath("$.mandatory").value(true));
        mvc.perform(get("/api/v1/desktop-updates/info/windows/x86_64/0.2.12")).andExpect(jsonPath("$.available").value(false));
        assertThat(jdbc.queryForObject("select count(*) from meter_update_artifacts",Long.class)).isEqualTo(1);
    }
    static byte[] pe(){byte[] file=new byte[128];file[0]='M';file[1]='Z';file[0x3c]=0x40;file[0x40]='P';file[0x41]='E';file[0x44]=0x64;file[0x45]=(byte)0x86;return file;}
    static KeyPair keys(){try{return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(Exception error){throw new AssertionError(error);}}
    static Path storage(){try{return Files.createTempDirectory("cqg-resume-test-");}catch(Exception error){throw new AssertionError(error);}}
    static String b64(byte[] value){return Base64.getEncoder().encodeToString(value);}
    static String publicKey(KeyPair key){byte[] value=new byte[42];value[0]='E';value[1]='d';System.arraycopy(ID,0,value,2,8);byte[] encoded=key.getPublic().getEncoded();System.arraycopy(encoded,encoded.length-32,value,10,32);return b64(("untrusted comment: test\n"+b64(value)+"\n").getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    static byte[] sign(KeyPair key,byte[] data)throws Exception{var signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(data);return signature.sign();}
    static String signature(byte[] bytes,String version,KeyPair key)throws Exception{var digest=new Blake2bDigest(512);digest.update(bytes,0,bytes.length);byte[] hashed=new byte[64];digest.doFinal(hashed,0);byte[] signed=sign(key,hashed),raw=new byte[74];raw[0]='E';raw[1]='D';System.arraycopy(ID,0,raw,2,8);System.arraycopy(signed,0,raw,10,64);String comment="timestamp:0\tfile:test.exe\tversion:"+version;byte[] text=comment.getBytes(StandardCharsets.UTF_8),all=Arrays.copyOf(signed,64+text.length);System.arraycopy(text,0,all,64,text.length);return b64(("untrusted comment: test\n"+b64(raw)+"\ntrusted comment: "+comment+"\n"+b64(sign(key,all))+"\n").getBytes(StandardCharsets.UTF_8));}
}
