package com.pingche.web.desktopupdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.pingche.web.user.*;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class DesktopReleaseIntegrationTest {
    static final KeyPair KEYS=keys();
    static final byte[] ID={1,2,3,4,5,6,7,8};
    static final Path STORAGE=storage();
    static final byte[] PACKAGE="MZ-synthetic-installer-never-executed".getBytes(StandardCharsets.UTF_8);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.desktop-update-public-key",DesktopReleaseIntegrationTest::publicKey);
        r.add("app.desktop-update-storage-dir",STORAGE::toString);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired UserRepository users; @Autowired com.pingche.web.auth.JwtService jwt;
    User admin,member;
    @BeforeEach void before() {
        jdbc.update("delete from desktop_update_artifacts"); jdbc.update("delete from desktop_update_releases");
        admin=users.findByEmail("release-admin@example.com").orElseGet(()->users.saveAndFlush(User.bootstrapAdministrator("release-admin@example.com","synthetic-hash","Release admin")));
        member=users.findByEmail("release-user@example.com").orElseGet(()->users.saveAndFlush(new User("release-user@example.com","synthetic-hash","Release member")));
    }
    @AfterAll static void cleanup() throws Exception {
        try(var files=Files.list(STORAGE)) { for(Path file:files.toList()) { if(Files.isRegularFile(file))Files.delete(file); } }
        Files.deleteIfExists(STORAGE.resolve(".uploads"));Files.deleteIfExists(STORAGE);
    }
    String auth(User u) {return "Bearer "+jwt.create(u.getId(),u.getRole().name(),u.getSessionVersion());}
    DesktopReleaseService.Release create(String version) throws Exception {
        String result=mvc.perform(post("/api/v1/admin/desktop-releases").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version",version,"notes","Synthetic update test"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readValue(result,DesktopReleaseService.Release.class);
    }
    DesktopReleaseService.Release upload(DesktopReleaseService.Release release,String target,String arch,byte[] bytes) throws Exception {
        return json.readValue(mvc.perform(multipart("/api/v1/admin/desktop-releases/"+release.id()+"/artifacts")
                .file(new MockMultipartFile("installer",target.equals("windows")?"test.exe":target.equals("darwin")?"test.app.tar.gz":"test.AppImage","application/octet-stream",bytes))
                .file(new MockMultipartFile("signature","test.sig","text/plain",signature(bytes,release.version()).getBytes(StandardCharsets.UTF_8)))
                .param("revision",Long.toString(release.revision())).param("target",target).param("arch",arch).header("Authorization",auth(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),DesktopReleaseService.Release.class);
    }
    DesktopReleaseService.Release change(DesktopReleaseService.Release r,String action) throws Exception {
        return json.readValue(mvc.perform(post("/api/v1/admin/desktop-releases/"+r.id()+"/"+action).header("Authorization",auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":"+r.revision()+"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),DesktopReleaseService.Release.class);
    }
    @Test void signedInstallerPublishesAndMatchesVersionPlatformWithoutRequiringLogin() throws Exception {
        var release=upload(create("0.4.10"),"windows","x86_64",PACKAGE);
        var file=release.artifacts().get(0);
        mvc.perform(get("/api/v1/desktop-updates/assets/"+file.id()+"/"+file.filename())).andExpect(status().isNotFound());
        release=change(release,"publish");
        mvc.perform(get("/api/v1/desktop-updates/info/windows/x86_64/0.4.9").header("Origin","http://tauri.localhost"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://tauri.localhost"))
                .andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.latestVersion").value("0.4.10"));
        mvc.perform(get("/api/v1/desktop-updates/manifest/windows/x86_64/0.4.9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.signature").isNotEmpty()).andExpect(jsonPath("$.sizeBytes").value(PACKAGE.length));
        mvc.perform(get("/api/v1/desktop-updates/assets/"+file.id()+"/"+file.filename())).andExpect(status().isOk()).andExpect(content().bytes(PACKAGE));
        mvc.perform(get("/api/v1/desktop-updates/manifest/windows/x86_64/0.4.10")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/desktop-updates/info/windows/aarch64/0.4.9")).andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/api/v1/desktop-updates/info/darwin/x86_64/0.4.9")).andExpect(jsonPath("$.available").value(false));
        var old=upload(create("0.4.9"),"windows","x86_64",PACKAGE);change(old,"publish");
        mvc.perform(get("/api/v1/desktop-updates/info/windows/x86_64/0.4.8")).andExpect(jsonPath("$.latestVersion").value("0.4.10"));
        change(release,"withdraw");
        mvc.perform(get("/api/v1/desktop-updates/assets/"+file.id()+"/"+file.filename())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/desktop-updates/info/windows/x86_64/0.4.9")).andExpect(jsonPath("$.available").value(false));
    }
    @Test void writeOperationsRequireAdministratorAndPublishedPackagesAreImmutable() throws Exception {
        mvc.perform(get("/api/v1/admin/desktop-releases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/desktop-releases").header("Authorization",auth(member))).andExpect(status().isForbidden());
        var release=create("1.0.0");
        mvc.perform(post("/api/v1/admin/desktop-releases/"+release.id()+"/publish").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isConflict());
        release=change(upload(release,"windows","x86_64",PACKAGE),"publish");
        mvc.perform(multipart("/api/v1/admin/desktop-releases/"+release.id()+"/artifacts").file(new MockMultipartFile("installer","test.exe","application/octet-stream",PACKAGE))
                .file(new MockMultipartFile("signature","test.sig","text/plain",signature(PACKAGE,"1.0.0").getBytes(StandardCharsets.UTF_8)))
                .param("revision",Long.toString(release.revision())).param("target","windows").param("arch","x86_64").header("Authorization",auth(admin)))
                .andExpect(status().isConflict());
    }
    @Test void rejectsTamperingWrongSignedVersionAndStaleRevisionWithoutLeakingFiles() throws Exception {
        var release=create("1.2.0");
        for(String sig:List.of(signature(PACKAGE,"1.1.0"),signature("MZ-changed".getBytes(StandardCharsets.UTF_8),"1.2.0"),"invalid-signature")) {
            mvc.perform(multipart("/api/v1/admin/desktop-releases/"+release.id()+"/artifacts")
                    .file(new MockMultipartFile("installer","test.exe","application/octet-stream",PACKAGE))
                    .file(new MockMultipartFile("signature","test.sig","text/plain",sig.getBytes(StandardCharsets.UTF_8)))
                    .param("revision","0").param("target","windows").param("arch","x86_64").header("Authorization",auth(admin)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESKTOP_SIGNATURE_INVALID"));
        }
        assertThat(jdbc.queryForObject("select count(*) from desktop_update_artifacts",Long.class)).isZero();
        try(var staged=Files.list(STORAGE.resolve(".uploads"))) {assertThat(staged.toList()).isEmpty();}
        var first=upload(release,"windows","x86_64",PACKAGE);
        mvc.perform(post("/api/v1/admin/desktop-releases/"+first.id()+"/publish").header("Authorization",auth(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from desktop_update_releases where id=?",String.class,release.id())).isEqualTo("DRAFT");
    }
    @Test void supportsMacAndLinuxSignedFormatsAndRejectsTraversalAndBadPlatforms() throws Exception {
        var release=create("2.0.0");
        release=upload(release,"darwin","aarch64",new byte[]{0x1f,(byte)0x8b,0,1,2});
        release=upload(release,"linux","x86_64",new byte[]{0x7f,'E','L','F',1});
        change(release,"publish");
        mvc.perform(get("/api/v1/desktop-updates/info/darwin/aarch64/1.0.0")).andExpect(jsonPath("$.available").value(true));
        mvc.perform(get("/api/v1/desktop-updates/info/linux/x86_64/1.0.0")).andExpect(jsonPath("$.available").value(true));
        mvc.perform(get("/api/v1/desktop-updates/info/unknown/x86_64/1.0.0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/desktop-updates/info/windows/x86_64/1.0.0-rc.1")).andExpect(status().isBadRequest());
        var file=release.artifacts().get(0);
        mvc.perform(get("/api/v1/desktop-updates/assets/"+file.id()+"/wrong.exe")).andExpect(status().isNotFound());
    }
    static KeyPair keys() {try{return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    static Path storage() {try{return Files.createTempDirectory("cqg-release-test-");}catch(Exception e){throw new AssertionError(e);}}
    static String b64(byte[] data) {return Base64.getEncoder().encodeToString(data);}
    static String publicKey() {
        byte[] raw=new byte[42];raw[0]='E';raw[1]='d';System.arraycopy(ID,0,raw,2,8);
        byte[] encoded=KEYS.getPublic().getEncoded();System.arraycopy(encoded,encoded.length-32,raw,10,32);
        return b64(("untrusted comment: synthetic public key\n"+b64(raw)+"\n").getBytes(StandardCharsets.UTF_8));
    }
    static byte[] ed(byte[] data) throws Exception {var s=Signature.getInstance("Ed25519");s.initSign(KEYS.getPrivate());s.update(data);return s.sign();}
    static String signature(byte[] data,String version) throws Exception {
        Blake2bDigest digest=new Blake2bDigest(512);digest.update(data,0,data.length);byte[] hash=new byte[64];digest.doFinal(hash,0);
        byte[] sig=ed(hash);byte[] raw=new byte[74];raw[0]='E';raw[1]='D';System.arraycopy(ID,0,raw,2,8);System.arraycopy(sig,0,raw,10,64);
        String comment="timestamp:0\tfile:synthetic.exe\tversion:"+version;
        byte[] commentBytes=comment.getBytes(StandardCharsets.UTF_8);byte[] global=Arrays.copyOf(sig,sig.length+commentBytes.length);System.arraycopy(commentBytes,0,global,sig.length,commentBytes.length);
        return b64(("untrusted comment: synthetic test\n"+b64(raw)+"\ntrusted comment: "+comment+"\n"+b64(ed(global))+"\n").getBytes(StandardCharsets.UTF_8));
    }
}
