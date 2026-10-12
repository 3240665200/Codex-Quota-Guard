package com.pingche.web.mail;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailboxConfigRepository extends JpaRepository<MailboxConfig, UUID> {
    boolean existsByMailboxKeyAndOfficialAccountIdNot(String key, UUID id);
    boolean existsByRecipientEmailAndOfficialAccountIdNot(String recipient, UUID id);
}
