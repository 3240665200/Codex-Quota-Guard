package com.pingche.web.mail;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit, offline-only inspection of an owner-provided file. Never prints mail or a code. */
@EnabledIfEnvironmentVariable(named = "CQG_LOCAL_EML_PROBE", matches = "APPROVED_OFFLINE_FILE")
class MailProvidedEmlProbeTest {
    @Test
    void providedMailCanBeDecodedAndMatchedWithoutPublishingItsContents() {
        boolean decoded = false;
        boolean senderMatchesConfiguration = false;
        boolean matched = false;
        boolean sixDigits = false;
        byte[] bytes = null;
        try {
            Path path = Path.of(System.getenv("CQG_LOCAL_EML_PATH"));
            if (!Files.isRegularFile(path) || Files.size(path) > MailBodyReader.MAX_BYTES) {
                throw new IllegalArgumentException();
            }
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MailBodyReader.MAX_BYTES + 1);
            }
            if (bytes.length > MailBodyReader.MAX_BYTES) throw new IllegalArgumentException();
            Instant received = Instant.now();
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes)) {
                // A local .eml cannot provide the IMAP server's INTERNALDATE. Only content is tested.
                @Override public Date getReceivedDate() { return Date.from(received); }
            };
            LoginMailMessage message = new MailBodyReader().read(mime);
            decoded = message != null && message.senders().size() == 1 && !message.recipients().isEmpty();
            if (decoded) {
                String sender = message.senders().get(0);
                String expectedSender = System.getenv("CQG_LOCAL_EML_EXPECTED_SENDER");
                senderMatchesConfiguration = expectedSender != null && expectedSender.equalsIgnoreCase(sender);
                Optional<String> code = new LoginMailMatcher().findCode(message, sender,
                        message.recipients().get(0), received.minusSeconds(1));
                matched = code.isPresent();
                sixDigits = code.map(value -> value.matches("[0-9]{6}")).orElse(false);
            }
        } catch (Exception ignored) {
            // Parser/provider exceptions may contain email content. Report only the fixed outcome below.
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
        System.out.println("LOCAL_EML_READER_ACCEPTED=" + decoded);
        System.out.println("LOCAL_EML_CONFIGURED_SENDER_MATCHES=" + senderMatchesConfiguration);
        System.out.println("LOCAL_EML_MATCH_USING_ACTUAL_SENDER=" + matched);
        System.out.println("LOCAL_EML_RETURNED_SIX_DIGIT_CODE=" + sixDigits);
        System.out.println("LOCAL_EML_RECEIPT_TIME_SIMULATED=true");
        assertTrue(decoded && matched && sixDigits, "Provided local mail did not pass the offline matching check");
    }
}
