package com.pingche.web.officialsession;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pingche.web.auth.JwtService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
    @Test void adminAuthenticationTransportAndNoStoreCoverJobs() throws Exception {
        mvc.perform(get("/api/v1/admin/browser-logins").secure(true)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/admin/browser-logins").secure(true).header("Authorization",token(member)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/browser-logins").header("Authorization",token(admin)).header("X-Forwarded-Proto","https"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        verifyNoInteractions(service);
    }
    @Test void ownerComesFromAuthenticatedPrincipalAndResponseIsNotCached() throws Exception {
        when(service.get(admin.getId(),id)).thenReturn(new BrowserLoginService.Job(id,account,"Synthetic","WAITING_FOR_HELPER",Instant.now().plusSeconds(600),null,null));
        mvc.perform(get("/api/v1/admin/browser-logins/"+id).secure(true).header("Authorization",token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WAITING_FOR_HELPER"))
                .andExpect(header().string("Cache-Control","no-store"));
        verify(service).get(admin.getId(),id);
    }
    @Test void invalidCompletionCannotReachCredentialStorage() throws Exception {
        mvc.perform(post("/api/v1/admin/browser-logins/"+id+"/complete").secure(true).header("Authorization",token(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"helperId\":null,\"authorization\":\"\",\"requestedAccountId\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(service);
    }
    private String token(User user) { return "Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion()); }
}
