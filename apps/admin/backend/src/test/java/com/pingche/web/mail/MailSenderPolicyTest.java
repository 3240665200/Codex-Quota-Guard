package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MailSenderPolicyTest {
    @Test
    void normalizesTwoAddressesAndDeduplicatesAllSupportedSeparators() {
        assertThat(MailSenderPolicy.normalize(
                " Sender@Example.com ; second@example.com\r\nSENDER@example.com， SECOND@example.com；sender@example.com "))
                .isEqualTo("sender@example.com,second@example.com");
        assertThat(MailSenderPolicy.normalize(" Sender@Example.com ")).isEqualTo("sender@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", ",;\r\n，；", "example.com", "@example.com", "sender@", "*@example.com",
            "sender@*.example.com", "sender?@example.com", "Display Name <sender@example.com>",
            "<sender@example.com>", "sender@example.com (comment)", "sender@example.com\t",
            "sender@example.com\u0000", "sender@example.com\u0085", "sender@example.com;not-an-address" })
    void rejectsInvalidConfigurationWithoutIncludingItInTheException(String configured) {
        assertThatThrownBy(() -> MailSenderPolicy.normalize(configured))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mailbox sender addresses are invalid");
        assertThat(MailSenderPolicy.matches(configured, "sender@example.com")).isFalse();
    }

    @Test
    void rejectsMoreThanTwoDistinctAddresses() {
        assertThatThrownBy(() -> MailSenderPolicy.normalize("first@example.com,second@example.com,third@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsTheOriginalConfigurationAt320Characters() {
        String domain = "a".repeat(63) + "." + "b".repeat(23) + ".example";
        String configured = "x".repeat(64) + "@" + domain + "," + "y".repeat(63) + "@" + domain;
        assertThat(configured).hasSize(320);
        assertThat(MailSenderPolicy.normalize(configured)).isEqualTo(configured);
        assertThatThrownBy(() -> MailSenderPolicy.normalize(configured + " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesOnlyEntireExplicitAddressesIgnoringCase() {
        String configured = "first@example.com,second@example.net";
        assertThat(MailSenderPolicy.matches(configured, "FIRST@EXAMPLE.COM")).isTrue();
        assertThat(MailSenderPolicy.matches(configured, "SECOND@EXAMPLE.NET")).isTrue();
        assertThat(MailSenderPolicy.matches("first@example.com", "FIRST@example.com")).isTrue();
        for (String actual : new String[] { null, "", "other@example.com", "first@sub.example.com",
                "first@example.com.evil.test", "first+tag@example.com", " first@example.com ",
                "Display Name <first@example.com>", "first@example.com,second@example.net" })
            assertThat(MailSenderPolicy.matches(configured, actual)).isFalse();
    }
}
