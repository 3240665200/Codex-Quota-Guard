package com.pingche.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void createsAndParsesSignedToken() {
        JwtService service = new JwtService(
                "test-secret-that-is-longer-than-thirty-two-bytes",
                Duration.ofMinutes(5),
                "test-issuer"
        );
        UUID userId = UUID.randomUUID();

        String token = service.create(userId, "USER", 7L);

        assertThat(service.parseUserId(token)).isEqualTo(userId);
        assertThat(service.parseIdentity(token).sessionVersion()).isEqualTo(7L);
        assertThat(service.getLifetime()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsUnsafeConfigurationAndForeignIssuer() {
        assertThatThrownBy(() -> new JwtService(
                "too-short",
                Duration.ofMinutes(5),
                "test-issuer"
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new JwtService(
                "test-secret-that-is-longer-than-thirty-two-bytes",
                Duration.ofMinutes(5),
                " "
        )).isInstanceOf(IllegalStateException.class);

        JwtService issuerOne = new JwtService(
                "test-secret-that-is-longer-than-thirty-two-bytes",
                Duration.ofMinutes(5),
                "issuer-one"
        );
        JwtService issuerTwo = new JwtService(
                "test-secret-that-is-longer-than-thirty-two-bytes",
                Duration.ofMinutes(5),
                "issuer-two"
        );

        assertThatThrownBy(() -> issuerTwo.parseUserId(
                issuerOne.create(UUID.randomUUID(), "USER")
        )).isInstanceOf(RuntimeException.class);
    }
}
