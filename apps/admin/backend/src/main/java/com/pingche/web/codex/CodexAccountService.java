package com.pingche.web.codex;

import com.pingche.web.auth.DomainException;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CodexAccountService {

    private static final int MAX_CREDENTIAL_BYTES = 1024 * 1024;
    private static final Sort ACCOUNT_ORDER = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private final CodexAccountRepository accounts;
    private final CodexCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CodexAccountService(
            CodexAccountRepository accounts,
            CodexCredentialCipher cipher,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.accounts = accounts;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CodexAccountListResponse list(
            int page,
            int size,
            String query,
            CodexAccount.Status status
    ) {
        String normalizedQuery = normalizeQuery(query);
        Specification<CodexAccount> specification = (root, ignored, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (normalizedQuery != null) {
                String pattern = "%" + escapeLike(normalizedQuery) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("email")), pattern, '\\'),
                        builder.like(builder.lower(root.get("displayName")), pattern, '\\')
                ));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<CodexAccount> result = accounts.findAll(
                specification,
                PageRequest.of(page, size, ACCOUNT_ORDER)
        );
        return new CodexAccountListResponse(
                result.getContent().stream().map(CodexAccountResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public CodexAccountResponse get(UUID accountId) {
        return CodexAccountResponse.from(
                accounts.findById(accountId).orElseThrow(this::notFound)
        );
    }

    @Transactional
    public CodexAccountResponse create(CodexAccountCreateRequest request) {
        String email = normalizeEmail(request.email());
        if (accounts.existsByEmail(email)) {
            throw emailExists();
        }
        String displayName = normalizeDisplayName(request.displayName());
        byte[] credential = serializeCredential(request.credential());
        UUID accountId = UUID.randomUUID();
        CodexCredentialCipher.EncryptedCredential encrypted =
                cipher.encrypt(accountId, credential);
        Instant now = Instant.now(clock);
        CodexAccount account = new CodexAccount(
                accountId,
                email,
                displayName,
                request.status() == null ? CodexAccount.Status.ACTIVE : request.status(),
                encrypted.ciphertext(),
                encrypted.iv(),
                now
        );
        try {
            return CodexAccountResponse.from(accounts.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            throw emailExists();
        }
    }

    @Transactional
    public CodexAccountResponse update(UUID accountId, CodexAccountUpdateRequest request) {
        if (request.email() == null
                && request.displayName() == null
                && request.status() == null
                && request.credential() == null) {
            throw validation("At least one Codex account field is required");
        }
        CodexAccount account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(this::notFound);
        String email = request.email() == null
                ? account.getEmail()
                : normalizeEmail(request.email());
        if (!email.equals(account.getEmail())
                && accounts.existsByEmailAndIdNot(email, accountId)) {
            throw emailExists();
        }
        String displayName = request.displayName() == null
                ? account.getDisplayName()
                : normalizeDisplayName(request.displayName());
        CodexAccount.Status status = request.status() == null
                ? account.getStatus()
                : request.status();
        Instant now = Instant.now(clock);
        account.updateProfile(email, displayName, status, now);

        if (request.credential() != null) {
            byte[] credential = serializeCredential(request.credential());
            CodexCredentialCipher.EncryptedCredential encrypted =
                    cipher.encrypt(accountId, credential);
            account.replaceCredential(encrypted.ciphertext(), encrypted.iv(), now);
        }
        try {
            return CodexAccountResponse.from(accounts.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            throw emailExists();
        }
    }

    @Transactional
    public void delete(UUID accountId) {
        CodexAccount account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(this::notFound);
        accounts.delete(account);
        accounts.flush();
    }

    private byte[] serializeCredential(JsonNode credential) {
        if (credential == null || credential.isNull() || !credential.isObject()) {
            throw validation("credential must be a JSON object");
        }
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(credential);
            if (serialized.length > MAX_CREDENTIAL_BYTES) {
                throw validation("credential must not exceed 1 MiB");
            }
            return serialized;
        } catch (JacksonException exception) {
            throw validation("credential is not valid JSON");
        }
    }

    private String normalizeEmail(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw validation("email must not be blank");
        }
        return normalized;
    }

    private String normalizeDisplayName(String displayName) {
        String normalized = displayName.trim();
        if (normalized.isEmpty()) {
            throw validation("displayName must not be blank");
        }
        return normalized;
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

    private DomainException emailExists() {
        return new DomainException(
                "CODEX_ACCOUNT_EMAIL_EXISTS",
                "A Codex account with this email already exists",
                HttpStatus.CONFLICT
        );
    }

    private DomainException notFound() {
        return new DomainException(
                "CODEX_ACCOUNT_NOT_FOUND",
                "Codex account was not found",
                HttpStatus.NOT_FOUND
        );
    }

    private DomainException validation(String message) {
        return new DomainException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}
