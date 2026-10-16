package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A real loopback HTTP server, isolated database and entirely synthetic official provider. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:official-logout-liveness;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "MANAGEMENT_ALLOW_LOOPBACK_HTTP=true", "server.address=127.0.0.1"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OfficialSessionLogoutLivenessTest {
    private UUID proxyNodeId;
    @LocalServerPort int port;
    @Autowired ConfigurableApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired UserRepository users;
    @Autowired OfficialAccountRepository accounts;
    @Autowired OfficialConnectionService connections;
    @Autowired OfficialSessionLogoutService logout;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockitoBean OfficialSessionClient official;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(java.net.ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build();
    private User admin;
    private OfficialAccount account;
    private String managementAuthorization;
    private String officialAccountKey;

    @BeforeEach void setup() throws Exception {
        proxyNodeId = SyntheticProxyNodeFixture.insert(jdbc);
        try(var connection=dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:official-logout-liveness");
        }
        admin=users.saveAndFlush(User.bootstrapAdministrator("liveness-"+UUID.randomUUID()+"@example.test","unused-synthetic-hash","Synthetic admin"));
        account=accounts.saveAndFlush(new OfficialAccount("Liveness "+UUID.randomUUID(),1000,admin.getId(),Instant.now()));
        officialAccountKey="synthetic-account-"+UUID.randomUUID();
        managementAuthorization="Bearer "+jwt.create(admin.getId(),admin.getRole().name(),admin.getSessionVersion());
        connections.save(account.getId(),new OfficialConnectionRequest("Bearer synthetic-provider-token",
                "__Secure-next-auth.session-token=synthetic-provider-cookie",officialAccountKey,proxyNodeId,0L));
        when(official.fetch(any(OfficialSessionCredentials.class),eq(proxyNodeId))).thenAnswer(call -> snapshot());
        assertAdministrationStillAvailable();
    }

    @AfterEach void waitForOwnWorkers() {awaitWorkersIdle();}

    @Test void fiveAcceptedUnboundRevocationsLeaveTheSameManagementSessionAndHttpServerAlive(CapturedOutput output) throws Exception {
        when(official.revokeSession(any(),eq(proxyNodeId),anyString())).thenReturn(new OfficialSessionWriteResult("ACCEPTED",null));
        UUID plan=preview();
        JsonNode result=confirmAndAwait(plan,"ACCEPTED");
        assertThat(result.path("results").size()).isEqualTo(5);
        for(JsonNode item:result.path("results"))assertThat(item.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(result.path("requiresReauthorization").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select status from official_account_connections where official_account_id=?",String.class,account.getId())).isEqualTo("UNVERIFIED");
        assertThat(users.findById(admin.getId()).orElseThrow().getSessionVersion()).isEqualTo(admin.getSessionVersion());
        verify(official,times(5)).revokeSession(any(),eq(proxyNodeId),anyString());
        verify(official,never()).logoutAll(any(),any());
        assertAdministrationStillAvailable();
        assertThat(request("POST",url("confirm"),Map.of("planId",plan,"acknowledgeLogoutRisk",true)).statusCode()).isEqualTo(409);
        assertAdministrationStillAvailable();
        assertThat(output.getAll()).contains("Official logout audit event=STARTED planId="+plan+" mode=UNBOUND")
                .contains("Official logout audit event=WORKER_FINISHED planId="+plan+" mode=UNBOUND workerOutcome=ACCEPTED accepted=5 failed=0 unknown=0 notAttempted=0");
        assertAuditHasNoSensitiveData(output);
    }

    @ParameterizedTest
    @ValueSource(strings={"OFFICIAL_AUTH_REQUIRED","OFFICIAL_ACCESS_BLOCKED"})
    void official401And403RejectionsDoNotInvalidateManagementOrStopTheServer(String code) throws Exception {
        when(official.revokeSession(any(),eq(proxyNodeId),anyString())).thenReturn(new OfficialSessionWriteResult("REJECTED",code));
        UUID plan=preview();
        JsonNode result=confirmAndAwait(plan,"FAILED");
        assertThat(result.path("results").get(0).path("errorCode").asString()).isEqualTo(code);
        assertThat(result.path("results").get(0).path("status").asString()).isEqualTo("FAILED");
        assertThat(result.path("requiresReauthorization").asBoolean()).isFalse();
        verify(official,times(1)).revokeSession(any(),eq(proxyNodeId),anyString());
        assertThat(request("POST",url("confirm"),Map.of("planId",plan,"acknowledgeLogoutRisk",true)).statusCode()).isEqualTo(409);
        assertAdministrationStillAvailable();
    }

    @ParameterizedTest
    @ValueSource(strings={"OFFICIAL_AUTH_REQUIRED","OFFICIAL_ACCESS_BLOCKED"})
    void officialAuthenticationReadFailuresLeaveTheHttpServiceAvailable(String code,CapturedOutput output) throws Exception {
        UUID plan=preview();
        when(official.fetch(any(),eq(proxyNodeId))).thenThrow(new DomainException(code,"private-upstream-provider-body",HttpStatus.BAD_GATEWAY));
        JsonNode result=confirmAndAwait(plan,"FAILED");
        assertThat(result.path("results").get(0).path("status").asString()).isEqualTo("NOT_ATTEMPTED");
        verify(official,never()).revokeSession(any(),any(),any());
        assertAdministrationStillAvailable();
        assertThat(output.getAll()).contains("Official logout audit event=WORKER_FINISHED planId="+plan+" mode=UNBOUND workerOutcome=FAILED accepted=0 failed=0 unknown=0 notAttempted=1");
        assertAuditHasNoSensitiveData(output);
    }

    @Test void executingAndUnknownRemainFencedWhileManagementRequestsContinueToSucceed() throws Exception {
        UUID plan=preview();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(official.fetch(any(),eq(proxyNodeId))).thenAnswer(call -> {
            entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("synthetic-provider-timeout");
            return snapshot();
        });
        when(official.revokeSession(any(),eq(proxyNodeId),anyString())).thenReturn(new OfficialSessionWriteResult("UNKNOWN","OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"));
        try {
            assertThat(request("POST",url("confirm"),Map.of("planId",plan,"acknowledgeLogoutRisk",true)).statusCode()).isEqualTo(200);
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select status from official_session_logout_plans where id=?",String.class,plan)).isEqualTo("EXECUTING");
            assertAdministrationStillAvailable();
            assertFenced();
        } finally {release.countDown();}
        awaitResult(plan,"UNKNOWN");
        assertFenced();
        assertAdministrationStillAvailable();
        verify(official,times(1)).revokeSession(any(),eq(proxyNodeId),anyString());
    }

    @Test void unexpectedWorkerFailureRetainsTheFenceWithoutClosingTheHttpServer(CapturedOutput output) throws Exception {
        UUID plan=preview();
        jdbc.update("update official_session_logout_plans set preview_json=? where id=?","{private-upstream-provider-body=synthetic-session-secret}",plan);
        assertThat(request("POST",url("confirm"),Map.of("planId",plan,"acknowledgeLogoutRisk",true)).statusCode()).isEqualTo(200);
        awaitWorkersIdle();
        assertThat(jdbc.queryForObject("select status from official_session_logout_plans where id=?",String.class,plan)).isEqualTo("EXECUTING");
        assertFenced();
        assertAdministrationStillAvailable();
        verify(official,never()).revokeSession(any(),any(),any());
        assertThat(output.getAll()).contains("Official logout audit event=WORKER_FAILED planId="+plan+" mode=UNBOUND outcome=UNKNOWN failureType=OTHER_RUNTIME");
        assertAuditHasNoSensitiveData(output);
    }

    private UUID preview() throws Exception {
        var response=request("POST",url("preview"),Map.of("mode","UNBOUND"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body=json.readTree(response.body());
        assertThat(body.path("targets").size()).isEqualTo(5);
        assertThat(body.path("excluded").size()).isEqualTo(1);
        return UUID.fromString(body.path("planId").asString());
    }
    private JsonNode confirmAndAwait(UUID plan,String state) throws Exception {
        assertThat(request("POST",url("confirm"),Map.of("planId",plan,"acknowledgeLogoutRisk",true)).statusCode()).isEqualTo(200);
        return awaitResult(plan,state);
    }
    private JsonNode awaitResult(UUID plan,String state) {
        AtomicReference<JsonNode> result=new AtomicReference<>();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(30)).untilAsserted(() -> {
            var response=request("GET",url(plan.toString()),null);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode value=json.readTree(response.body());
            assertThat(value.path("status").asString()).isEqualTo(state);
            result.set(value);
        });
        awaitWorkersIdle();
        return result.get();
    }
    private void assertAdministrationStillAvailable() throws Exception {
        assertThat(context.isActive()).isTrue();
        assertThat(request("GET","/api/v1/admin/users",null).statusCode()).isEqualTo(200);
        assertThat(request("GET","/api/v1/admin/teams",null).statusCode()).isEqualTo(200);
        assertThat(context.isActive()).isTrue();
    }
    private void assertFenced() throws Exception {
        var response=request("POST",url("preview"),Map.of("mode","UNBOUND"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("OFFICIAL_LOGOUT_BUSY");
    }
    private void awaitWorkersIdle() {
        ThreadPoolExecutor executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(logout,"workers");
        await().atMost(Duration.ofSeconds(5)).until(() -> executor.getActiveCount()==0 && executor.getQueue().isEmpty());
    }
    private void assertAuditHasNoSensitiveData(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("synthetic-provider-token","synthetic-provider-cookie","synthetic-unbound-",
                "synthetic-current-manager","synthetic-session-secret","private-upstream-provider-body",admin.getEmail(),managementAuthorization);
    }
    private HttpResponse<String> request(String method,String path,Object body) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(Duration.ofSeconds(3)).header("Authorization",managementAuthorization).header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String url(String action) {return "/api/v1/admin/official-accounts/"+account.getId()+"/session-logout/"+action;}
    private OfficialSessionSnapshot snapshot() {
        List<OfficialSessionInfo> devices=new ArrayList<>();
        for(int index=1;index<=5;index++)devices.add(device("synthetic-unbound-"+index,false));
        devices.add(device("synthetic-current-manager",true));
        return new OfficialSessionSnapshot(new OfficialAccountInfo("synthetic-owner",null,null,officialAccountKey,"Synthetic account","plus","owner",false,true),new OfficialSessions(devices,true));
    }
    private OfficialSessionInfo device(String id,boolean current) {
        return new OfficialSessionInfo("synthetic-row-"+id,id,"windows","Synthetic device",null,null,null,current,false,Instant.EPOCH,Instant.EPOCH,null,null,null,List.of("Codex"));
    }
}
