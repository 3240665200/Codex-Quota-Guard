package com.pingche.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void doesNothingWhenBootstrapCredentialsAreBothAbsent() throws Exception {
        new AdminBootstrap(users, passwordEncoder, "", "", "Administrator").run(null);

        verifyNoInteractions(users, passwordEncoder);
    }

    @Test
    void rejectsPartiallyConfiguredBootstrapCredentials() {
        assertThatThrownBy(() -> new AdminBootstrap(
                users,
                passwordEncoder,
                "admin@example.com",
                "",
                "Administrator"
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured together");

        assertThatThrownBy(() -> new AdminBootstrap(
                users,
                passwordEncoder,
                "",
                "admin-bootstrap-password",
                "Administrator"
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured together");
    }

    @Test
    void rejectsInvalidBootstrapEmailAndShortPassword() {
        assertThatThrownBy(() -> new AdminBootstrap(
                users,
                passwordEncoder,
                "not-an-email",
                "admin-bootstrap-password",
                "Administrator"
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid email");

        assertThatThrownBy(() -> new AdminBootstrap(
                users,
                passwordEncoder,
                "admin@example.com",
                "too-short",
                "Administrator"
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");

        assertThatThrownBy(() -> new AdminBootstrap(
                users,
                passwordEncoder,
                "admin@example.com",
                "admin-bootstrap-password",
                "   "
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISPLAY_NAME");
    }

    @Test
    void createsNormalizedFirstAdministratorWithBcryptCostTwelve() throws Exception {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(users.existsByRole(User.Role.ADMIN)).thenReturn(false);

        new AdminBootstrap(
                users,
                bcrypt,
                "  ADMIN@Example.COM ",
                "admin-bootstrap-password",
                " Bootstrap Admin "
        ).run(null);

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(created.capture());
        User administrator = created.getValue();
        assertThat(administrator.getEmail()).isEqualTo("admin@example.com");
        assertThat(administrator.getDisplayName()).isEqualTo("Bootstrap Admin");
        assertThat(administrator.getRole()).isEqualTo(User.Role.ADMIN);
        assertThat(administrator.getStatus()).isEqualTo(User.Status.ACTIVE);
        assertThat(administrator.getPasswordHash())
                .matches("\\$2[aby]\\$12\\$.*");
        assertThat(bcrypt.matches(
                "admin-bootstrap-password",
                administrator.getPasswordHash()
        )).isTrue();
    }

    @Test
    void refusesToSilentlyPromoteAnExistingNonAdministrator() {
        User existingUser = new User(
                "admin@example.com",
                "hash",
                "Existing user"
        );
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> new AdminBootstrap(
                users,
                passwordEncoder,
                "admin@example.com",
                "admin-bootstrap-password",
                "Administrator"
        ).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an administrator");

        verify(users, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void acceptsAnExistingAdministratorWithoutChangingIt() throws Exception {
        User existingAdministrator = User.bootstrapAdministrator(
                "admin@example.com",
                "hash",
                "Administrator"
        );
        when(users.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(existingAdministrator));

        new AdminBootstrap(
                users,
                passwordEncoder,
                "admin@example.com",
                "admin-bootstrap-password",
                "Administrator"
        ).run(null);

        verify(users, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void doesNotCreateAnotherAdminWhenOneAlreadyExists() throws Exception {
        when(users.findByEmail("new-admin@example.com")).thenReturn(Optional.empty());
        when(users.existsByRole(User.Role.ADMIN)).thenReturn(true);

        new AdminBootstrap(
                users,
                passwordEncoder,
                "new-admin@example.com",
                "admin-bootstrap-password",
                "Administrator"
        ).run(null);

        verify(users, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(passwordEncoder);
    }
}
