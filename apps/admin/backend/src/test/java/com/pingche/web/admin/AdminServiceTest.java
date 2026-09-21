package com.pingche.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository users;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
        adminService = new AdminService(users, clock);
    }

    @Test
    void dashboardCountsAllRequestedSegments() {
        when(users.count()).thenReturn(12L);
        when(users.countByStatus(User.Status.ACTIVE)).thenReturn(9L);
        when(users.countByStatus(User.Status.DISABLED)).thenReturn(3L);
        when(users.countByRole(User.Role.ADMIN)).thenReturn(2L);
        when(users.countByCreatedAtGreaterThanEqual(
                Instant.parse("2026-09-14T00:00:00Z")
        )).thenReturn(5L);

        AdminDashboardResponse dashboard = adminService.dashboard();

        assertThat(dashboard.totalUsers()).isEqualTo(12L);
        assertThat(dashboard.activeUsers()).isEqualTo(9L);
        assertThat(dashboard.disabledUsers()).isEqualTo(3L);
        assertThat(dashboard.adminUsers()).isEqualTo(2L);
        assertThat(dashboard.newUsersLast7Days()).isEqualTo(5L);
    }

    @Test
    void updatesAnotherUserAndRejectsInvalidTargetsOrSelfLockout() {
        UUID actorId = UUID.randomUUID();
        User target = new User("target@example.com", "hash", "Target");
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(users.saveAndFlush(target)).thenReturn(target);

        var response = adminService.updateUser(
                actorId,
                target.getId(),
                User.Status.DISABLED,
                User.Role.ADMIN
        );

        assertThat(response.status()).isEqualTo("DISABLED");
        assertThat(response.role()).isEqualTo("ADMIN");
        verify(users).saveAndFlush(target);

        assertThatThrownBy(() -> adminService.updateUser(
                actorId,
                target.getId(),
                null,
                null
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR"));

        UUID missingId = UUID.randomUUID();
        when(users.findById(missingId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adminService.updateUser(
                actorId,
                missingId,
                User.Status.ACTIVE,
                null
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("USER_NOT_FOUND"));

        assertThatThrownBy(() -> adminService.updateUser(
                target.getId(),
                target.getId(),
                User.Status.DISABLED,
                null
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ADMIN_SELF_PROTECTION"));

        assertThatThrownBy(() -> adminService.updateUser(
                target.getId(),
                target.getId(),
                null,
                User.Role.USER
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ADMIN_SELF_PROTECTION"));
    }

    @Test
    void refusesToRemoveTheLastActiveAdministrator() {
        UUID actorId = UUID.randomUUID();
        User lastAdministrator = User.bootstrapAdministrator(
                "last-admin@example.com",
                "hash",
                "Last administrator"
        );
        when(users.findActiveAdministratorsForUpdate()).thenReturn(List.of(lastAdministrator));
        when(users.findById(lastAdministrator.getId()))
                .thenReturn(Optional.of(lastAdministrator));

        assertThatThrownBy(() -> adminService.updateUser(
                actorId,
                lastAdministrator.getId(),
                User.Status.DISABLED,
                null
        )).isInstanceOfSatisfying(DomainException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ADMIN_LAST_ACTIVE"));

        verify(users, never()).saveAndFlush(lastAdministrator);
    }
}
