package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import com.pingche.web.weblogin.ManagedLoginBrowser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:web-login-integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebLoginIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired OfficialAccountRepository accounts;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired OfficialConnectionService connections;
    @Autowired WebLoginService service;
    @MockitoBean OfficialSessionClient client;
    @MockitoBean ManagedLoginBrowser browser;
    ManagedLoginBrowser.Session session;
    User admin,member;
    OfficialAccount account;

    @BeforeEach void setup() {
        session=mock(ManagedLoginBrowser.Session.class);
        when(browser.available()).thenReturn(true);
        when(browser.open(any(),any())).thenReturn(session);
        when(session.frame()).thenReturn(new ManagedLoginBrowser.Frame(1024,768,"c3ludGhldGlj","https://chatgpt.com/?private=redacted",true,null));
        when(session.capture()).thenReturn(new ManagedLoginBrowser.Credentials("__Secure-next-auth.session-token=synthetic-cookie","Bearer synthetic-new","synthetic-account"));
        when(client.fetch(any(),any())).thenReturn(snapshot("synthetic-user"));
        admin=users.saveAndFlush(User.bootstrapAdministrator(UUID.randomUUID()+"@example.test","unused-hash","Admin"));
        member=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","unused-hash","Member"));
        account=accounts.saveAndFlush(new OfficialAccount("Synthetic account "+UUID.randomUUID(),100,member.getId(),Instant.now()));
    }
    @AfterEach void cleanup() {service.cancelOwner(admin.getId());}

    @Test void allEndpointsRequireAdminSupportedTransportAndNeverCache() throws Exception {
        for(String suffix:List.of("","/frame")) {
            String path="/api/v1/admin/web-logins/"+UUID.randomUUID()+suffix;
            mvc.perform(get(path).secure(true)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(path).secure(true).header("Authorization",token(member))).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(path).with(http -> { http.setScheme("ftp"); return http; }).header("Authorization",token(admin)).header("X-Forwarded-Proto","https")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED")).andExpect(header().string("Cache-Control","no-store"));
        }
        for(String suffix:List.of("/input","/complete","/cancel")) {
            String path="/api/v1/admin/web-logins/"+UUID.randomUUID()+suffix;
            mvc.perform(post(path).secure(true).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(post(path).secure(true).header("Authorization",token(member)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(post(path).with(http -> { http.setScheme("ftp"); return http; }).header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        }
        mvc.perform(post(createUrl()).secure(true).header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":null}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        verify(browser,never()).open(any(),any());
    }

    @Test void candidateFailureLeavesEverySavedCredentialAndSnapshotUnchanged() {
        importOld();
        var before=jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId());
        var job=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(connections.read(account.getId()).version(),null));
        when(client.fetch(any(),any())).thenThrow(new DomainException("OFFICIAL_CHALLENGE_REQUIRED","private-upstream-body",HttpStatus.BAD_GATEWAY));
        assertThat(service.complete(admin.getId(),job.id()).state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId())).isEqualTo(before);
        verify(session).close();
    }

    @Test void verifiedSavePublishesSnapshotAndRotatesBindingRevisionAtomically() throws Exception {
        importOld();
        var before=jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId());
        var job=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(connections.read(account.getId()).version(),null));
        mvc.perform(get(jobUrl(job.id())+"/frame").secure(true).header("Authorization",token(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pageOrigin").value("https://chatgpt.com")).andExpect(jsonPath("$.readyToSave").value(true))
                .andExpect(jsonPath("$.cookie").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        String result=mvc.perform(post(jobUrl(job.id())+"/complete").secure(true).header("Authorization",token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.readyToSave").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("synthetic-new","synthetic-cookie","private=","authorization");
        var after=jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId());
        assertThat(after.get("revision")).isNotEqualTo(before.get("revision"));
        assertThat(after.get("authorization_cipher")).isNotEqualTo(before.get("authorization_cipher"));
        assertThat(after.get("status")).isEqualTo("CONNECTED");
        assertThat(connections.sessions(account.getId()).account().userId()).isEqualTo("synthetic-user");
    }

    @Test void mismatchedVerifiedUserAndConcurrentVersionChangeCannotOverwriteOldAuthorization() {
        importOld();
        var before=jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId());
        var job=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(connections.read(account.getId()).version(),null));
        when(client.fetch(any(),any())).thenReturn(snapshot("another-user"));
        assertThat(service.complete(admin.getId(),job.id()).errorCode()).isEqualTo("OFFICIAL_ACCOUNT_MISMATCH");
        assertThat(jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId())).isEqualTo(before);
        when(client.fetch(any(),any())).thenReturn(snapshot("synthetic-user"));
        var next=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(connections.read(account.getId()).version(),null));
        connections.save(account.getId(),new OfficialConnectionRequest("Bearer concurrent-replacement",null,"synthetic-account",null,connections.read(account.getId()).version()));
        var replacement=jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId());
        assertThat(service.complete(admin.getId(),next.id()).errorCode()).isEqualTo("OFFICIAL_CONNECTION_VERSION_CONFLICT");
        assertThat(jdbc.queryForMap("select * from official_account_connections where official_account_id=?",account.getId())).isEqualTo(replacement);
    }

    @Test void ownerIsolationAndInputValidationCoverTheLiveBrowser() throws Exception {
        var job=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(0L,null));
        User other=users.saveAndFlush(User.bootstrapAdministrator(UUID.randomUUID()+"@example.test","unused-hash","Other"));
        mvc.perform(get(jobUrl(job.id())+"/frame").secure(true).header("Authorization",token(other))).andExpect(status().isNotFound());
        mvc.perform(post(jobUrl(job.id())+"/input").secure(true).header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CLICK\",\"x\":1024,\"y\":0}")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(jobUrl(job.id())+"/input").secure(true).header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"KEY\",\"key\":\"a\",\"ctrl\":true}")).andExpect(status().isNoContent());
        verify(session,times(1)).input(any());
        mvc.perform(post(jobUrl(job.id())+"/cancel").secure(true).header("Authorization",token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test void systemLogoutCancelsAndClosesTheOwnersBrowser() throws Exception {
        var job=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(0L,null));
        mvc.perform(post("/api/v1/auth/logout").secure(true).header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"synthetic-unused-token\"}")).andExpect(status().isNoContent());
        assertThat(service.get(admin.getId(),job.id()).state()).isEqualTo("CANCELLED");
        verify(session).close();
    }

    @Test void ownerLockContentionFailsClosedWithoutWaitingWhileTheJobIsLocked() throws Exception {
        var job=service.create(admin.getId(),account.getId(),new BrowserLoginService.StartRequest(0L,null));
        CompletableFuture<WebLoginService.Job> pending=null;
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select id from users where id=? for update")) {
                lock.setObject(1,admin.getId());
                lock.executeQuery().close();
            }
            try {
                pending=CompletableFuture.supplyAsync(() -> service.complete(admin.getId(),job.id()));
                var result=pending.get(1,TimeUnit.SECONDS);
                assertThat(result.state()).isEqualTo("FAILED");
                assertThat(result.errorCode()).isEqualTo("BROWSER_LOGIN_ACCESS_REVOKED");
            } finally {connection.rollback();if(pending!=null)pending.get(5,TimeUnit.SECONDS);}
        }
        assertThat(connections.read(account.getId()).configured()).isFalse();
        verify(session).close();
    }

    private void importOld() {
        connections.save(account.getId(),new OfficialConnectionRequest("Bearer synthetic-old",null,"synthetic-account",null,0L));
        connections.refresh(account.getId());
    }
    private OfficialSessionSnapshot snapshot(String user) {return new OfficialSessionSnapshot(new OfficialAccountInfo(user,"owner@example.test","Owner","synthetic-account","Personal","plus","owner",false,true),new OfficialSessions(List.of(),true));}
    private String token(User user) {return "Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion());}
    private String createUrl() {return "/api/v1/admin/official-accounts/"+account.getId()+"/web-login";}
    private String jobUrl(UUID id) {return "/api/v1/admin/web-logins/"+id;}
}
