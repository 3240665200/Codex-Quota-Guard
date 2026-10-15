package com.pingche.web.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pingche.web.admin.AdminUserLogoutService;
import com.pingche.web.auth.JwtService;
import com.pingche.web.officialsession.BrowserLoginService;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.officialsession.OfficialSessionLogoutService;
import com.pingche.web.officialsession.WebLoginService;
import com.pingche.web.proxy.ProxyNodeService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "app.require-https=false",
        "spring.datasource.url=jdbc:h2:mem:management-http-policy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class ManagementHttpIntegrationTest extends ManagementTransportIntegrationSupport {
    @Test
    void everyManagementEntryAcceptsConfiguredHttpAndHttpsWithoutLosingNoStore() throws Exception {
        for (boolean secure : new boolean[] {false, true}) {
            for (Route route : routes()) {
                mvc.perform(call(route, secure, adminToken)).andExpect(status().is(route.successStatus()))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
        }
    }

    @Test
    void httpSupportDoesNotGrantAnonymousOrOrdinaryUsersManagementAccess() throws Exception {
        for (boolean secure : new boolean[] {false, true}) {
            for (Route route : routes()) {
                mvc.perform(call(route, secure, null)).andExpect(status().isUnauthorized());
                mvc.perform(call(route, secure, memberToken)).andExpect(status().isForbidden());
            }
        }
        verifyNoManagementActions();
    }

    @Test
    void unsupportedProtocolCannotReachAnySensitiveManagementAction() throws Exception {
        for (Route route : routes()) {
            mvc.perform(call(route, true, adminToken).with(http -> { http.setScheme("ftp"); return http; }))
                    .andExpect(status().is(route.path().contains("/proxy-nodes") ? 403 : 400));
        }
        verifyNoManagementActions();
    }
}

@SpringBootTest(properties = {
        "app.require-https=true",
        "spring.datasource.url=jdbc:h2:mem:management-https-policy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class ManagementHttpsIntegrationTest extends ManagementTransportIntegrationSupport {
    @Test
    void configuredHttpsRequirementRedirectsHttpEvenWithSpoofedForwardingHeaders() throws Exception {
        for (Route route : routes()) {
            mvc.perform(call(route, false, adminToken).header("Forwarded", "proto=https;for=127.0.0.1")
                            .header("X-Forwarded-Proto", "https"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://")));
        }
        verifyNoManagementActions();
    }

    @Test
    void configuredHttpsStillRequiresAdministratorAndPreservesSuccessfulSecureRequests() throws Exception {
        for (Route route : routes()) {
            mvc.perform(call(route, true, null)).andExpect(status().isUnauthorized());
            mvc.perform(call(route, true, memberToken)).andExpect(status().isForbidden());
            mvc.perform(call(route, true, adminToken)).andExpect(status().is(route.successStatus()))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }
}

@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ManagementTransportIntegrationSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @MockitoBean OfficialConnectionService connections;
    @MockitoBean BrowserLoginService browserLogins;
    @MockitoBean WebLoginService webLogins;
    @MockitoBean OfficialSessionLogoutService sessionLogouts;
    @MockitoBean AdminUserLogoutService userLogouts;
    @MockitoBean ProxyNodeService proxies;
    String adminToken;
    String memberToken;
    private static final String ID = "7341562a-1ce2-4bc5-bc22-c3e74504c039";
    private static final String ACCOUNT = "/api/v1/admin/official-accounts/" + ID;
    private static final String BROWSER = "/api/v1/admin/browser-logins/" + ID;
    private static final String WEB = "/api/v1/admin/web-logins/" + ID;
    private static final String LOGOUT = ACCOUNT + "/session-logout";
    private static final String USER = "/api/v1/admin/users/" + ID + "/force-logout-all";
    private static final String PROXY = "/api/v1/admin/proxy-nodes";

    @BeforeEach
    void identities() {
        User admin = users.saveAndFlush(User.bootstrapAdministrator(UUID.randomUUID() + "@example.test", "synthetic-unused", "Admin"));
        User member = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "synthetic-unused", "Member"));
        adminToken = "Bearer " + jwt.create(admin.getId(), admin.getRole().name(), admin.getSessionVersion());
        memberToken = "Bearer " + jwt.create(member.getId(), member.getRole().name(), member.getSessionVersion());
    }

    void verifyNoManagementActions() {
        verifyNoInteractions(connections, browserLogins, webLogins, sessionLogouts, userLogouts, proxies);
    }

    MockHttpServletRequestBuilder call(Route route, boolean secure, String token) {
        var builder = request(route.method(), route.path()).secure(secure).with(http -> {
            http.setScheme(secure ? "https" : "http");
            http.setRemoteAddr("192.0.2.10");
            http.setLocalAddr("192.0.2.20");
            http.setServerName("admin.example.test");
            return http;
        });
        if (token != null) builder.header("Authorization", token);
        if (route.body() != null) builder.contentType(MediaType.APPLICATION_JSON).content(route.body());
        return builder;
    }

    List<Route> routes() {
        String connection = "{\"authorization\":\"Bearer synthetic\",\"requestedAccountId\":\"synthetic-account\",\"version\":0}";
        String confirmation = "{\"planId\":\"" + ID + "\",\"acknowledgeLogoutRisk\":true}";
        String proxy = "{\"label\":\"Synthetic\",\"uri\":\"http://proxy.example.test:8080\",\"enabled\":true,\"version\":0}";
        return List.of(
                route("GET", ACCOUNT + "/connection", null), route("PUT", ACCOUNT + "/connection", connection),
                new Route(HttpMethod.DELETE, ACCOUNT + "/connection", null, 204),
                route("POST", ACCOUNT + "/connection/refresh", null), route("GET", ACCOUNT + "/sessions", null),
                route("POST", ACCOUNT + "/browser-login", "{\"version\":0}"),
                route("GET", "/api/v1/admin/browser-logins", null), route("GET", BROWSER, null),
                route("POST", BROWSER + "/claim", "{\"helperId\":\"" + ID + "\"}"),
                route("POST", BROWSER + "/complete", "{\"helperId\":\"" + ID + "\",\"authorization\":\"Bearer synthetic\",\"requestedAccountId\":\"synthetic-account\"}"),
                route("POST", BROWSER + "/cancel", null), route("POST", ACCOUNT + "/web-login", "{\"version\":0}"),
                route("GET", WEB, null), route("GET", WEB + "/frame", null),
                new Route(HttpMethod.POST, WEB + "/input", "{\"type\":\"key\",\"key\":\"Tab\"}", 204),
                route("POST", WEB + "/complete", null), route("POST", WEB + "/cancel", null),
                route("POST", LOGOUT + "/preview", "{\"mode\":\"ALL\"}"), route("GET", LOGOUT + "/current", null),
                route("GET", LOGOUT + "/" + ID, null), route("POST", LOGOUT + "/confirm", confirmation),
                route("POST", LOGOUT + "/" + ID + "/acknowledge-unknown", "{\"acknowledgeUncertainOutcome\":true}"),
                route("POST", USER + "/preview", null), route("POST", USER + "/confirm", confirmation),
                route("GET", USER + "/current", null), route("GET", USER + "/" + ID, null),
                route("GET", PROXY, null), route("POST", PROXY, proxy), route("PUT", PROXY + "/" + ID, proxy),
                new Route(HttpMethod.DELETE, PROXY + "/" + ID, null, 204),
                route("POST", PROXY + "/import", "{\"content\":\"http://proxy.example.test:8080\"}"),
                route("POST", PROXY + "/" + ID + "/test", null));
    }

    private Route route(String method, String path, String body) { return new Route(HttpMethod.valueOf(method), path, body, 200); }
    record Route(HttpMethod method, String path, String body, int successStatus) { }
}
