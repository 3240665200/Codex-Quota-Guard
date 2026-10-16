package com.pingche.web.officialsession;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pingche.web.auth.JwtService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:browser-login-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrowserLoginControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @MockitoBean BrowserLoginService service;
    User admin,member;
    UUID id=UUID.randomUUID(),account=UUID.randomUUID();
    @BeforeEach void setup() {
        admin=users.saveAndFlush(User.bootstrapAdministrator(UUID.randomUUID()+"@example.test","unused-hash","Admin"));
        member=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","unused-hash","Member"));
    }
    @AfterEach void noRetiredEndpointCanReachCredentialStorage() { verifyNoInteractions(service); }
    @Test void adminAuthenticationTransportAndNoStoreCoverEveryRetiredEndpoint() throws Exception {
        for (String path : paths()) {
            mvc.perform(request(path).secure(true)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(request(path).secure(true).header("Authorization",token(member)))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(request(path).with(http -> { http.setScheme("ftp"); return http; })
                    .header("Authorization",token(admin)).header("X-Forwarded-Proto","https"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
    }
    @Test void everyRetiredEndpointReturnsGoneWithoutReadingOrWritingCredentials() throws Exception {
        for (String path : paths()) {
            mvc.perform(request(path).secure(true).header("Authorization",token(admin))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"helperId\":\""+id+"\",\"authorization\":\"Bearer synthetic-retired\",\"cookie\":\"synthetic-cookie\"}"))
                    .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGIN_HELPER_RETIRED"))
                    .andExpect(jsonPath("$.authorization").doesNotExist()).andExpect(jsonPath("$.cookie").doesNotExist())
                    .andExpect(header().string("Cache-Control","no-store"));
        }
    }
    @Test void retiredWriteEndpointsIgnoreMissingAndMalformedLegacyBodies() throws Exception {
        for (String path : paths().subList(2,6)) {
            for (String body : List.of("", "{", "{\"helperId\":null,\"authorization\":\"\"}")) {
                mvc.perform(request(path).secure(true).header("Authorization",token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGIN_HELPER_RETIRED"))
                        .andExpect(header().string("Cache-Control","no-store"));
            }
        }
    }
    private List<String> paths() { return List.of("/api/v1/admin/browser-logins", "/api/v1/admin/browser-logins/"+id,
            "/api/v1/admin/official-accounts/"+account+"/browser-login", "/api/v1/admin/browser-logins/"+id+"/claim",
            "/api/v1/admin/browser-logins/"+id+"/complete", "/api/v1/admin/browser-logins/"+id+"/cancel"); }
    private MockHttpServletRequestBuilder request(String path) { return paths().indexOf(path)<2 ? get(path) : post(path); }
    private String token(User user) { return "Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion()); }
}
