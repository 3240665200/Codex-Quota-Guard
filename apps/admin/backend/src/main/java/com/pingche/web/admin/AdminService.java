package com.pingche.web.admin;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import com.pingche.web.user.UserResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final char LIKE_ESCAPE_CHARACTER = '\\';
    private static final Sort USER_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private final UserRepository users;
    private final Clock clock;

    public AdminService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        Instant sevenDaysAgo = Instant.now(clock).minus(7, ChronoUnit.DAYS);
        return new AdminDashboardResponse(
                users.count(),
                users.countByStatus(User.Status.ACTIVE),
                users.countByStatus(User.Status.DISABLED),
                users.countByRole(User.Role.ADMIN),
                users.countByCreatedAtGreaterThanEqual(sevenDaysAgo)
        );
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse listUsers(
            int page,
            int size,
            String query,
            User.Status status,
            User.Role role
    ) {
        validatePage(page, size);
        Pageable pageable = PageRequest.of(page, size, USER_SORT);
        Page<User> result = users.findAll(
                userSpecification(query, status, role),
                pageable
        );
        return new AdminUserPageResponse(
                result.getContent().stream().map(UserResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional
    public UserResponse updateUser(
            UUID actingUserId,
            UUID targetUserId,
            User.Status status,
            User.Role role
    ) {
        if (status == null && role == null) {
            throw validation("At least one of status or role is required");
        }

        List<User> activeAdministrators = mayRemoveActiveAdministrator(status, role)
                ? users.findActiveAdministratorsForUpdate()
                : List.of();
        User target = users.findById(targetUserId).orElseThrow(this::userNotFound);
        if (actingUserId.equals(targetUserId)
                && (status == User.Status.DISABLED || role == User.Role.USER)) {
            throw new DomainException(
                    "ADMIN_SELF_PROTECTION",
                    "Administrators cannot disable or demote themselves",
                    HttpStatus.CONFLICT
            );
        }

        if (isActiveAdministrator(target)
                && !willRemainActiveAdministrator(target, status, role)
                && activeAdministrators.size() <= 1) {
            throw new DomainException(
                    "ADMIN_LAST_ACTIVE",
                    "At least one active administrator must remain",
                    HttpStatus.CONFLICT
            );
        }

        target.updateAdministration(status, role);
        return UserResponse.from(users.saveAndFlush(target));
    }

    private boolean mayRemoveActiveAdministrator(User.Status status, User.Role role) {
        return status == User.Status.DISABLED || role == User.Role.USER;
    }

    private boolean isActiveAdministrator(User user) {
        return user.getStatus() == User.Status.ACTIVE && user.getRole() == User.Role.ADMIN;
    }

    private boolean willRemainActiveAdministrator(
            User user,
            User.Status status,
            User.Role role
    ) {
        User.Status resultingStatus = status == null ? user.getStatus() : status;
        User.Role resultingRole = role == null ? user.getRole() : role;
        return resultingStatus == User.Status.ACTIVE && resultingRole == User.Role.ADMIN;
    }

    private Specification<User> userSpecification(
            String query,
            User.Status status,
            User.Role role
    ) {
        Specification<User> specification = (root, criteriaQuery, criteriaBuilder) ->
                criteriaBuilder.conjunction();

        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery != null) {
            String searchPattern = "%" + escapeLike(normalizedQuery) + "%";
            specification = specification.and((root, criteriaQuery, criteriaBuilder) ->
                    criteriaBuilder.or(
                            criteriaBuilder.like(
                                    criteriaBuilder.lower(root.<String>get("email")),
                                    searchPattern,
                                    LIKE_ESCAPE_CHARACTER
                            ),
                            criteriaBuilder.like(
                                    criteriaBuilder.lower(root.<String>get("displayName")),
                                    searchPattern,
                                    LIKE_ESCAPE_CHARACTER
                            )
                    ));
        }
        if (status != null) {
            specification = specification.and((root, criteriaQuery, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("status"), status));
        }
        if (role != null) {
            specification = specification.and((root, criteriaQuery, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("role"), role));
        }
        return specification;
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw validation("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private DomainException validation(String message) {
        return new DomainException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private DomainException userNotFound() {
        return new DomainException(
                "USER_NOT_FOUND",
                "User not found",
                HttpStatus.NOT_FOUND
        );
    }
}
