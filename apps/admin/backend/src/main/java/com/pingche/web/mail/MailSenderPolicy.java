package com.pingche.web.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** The administrator's exact sender addresses; no implicit aliases or domain matching. */
public final class MailSenderPolicy {
    private static final int MAX_ADDRESSES = 2;
    private static final int MAX_CONFIG_LENGTH = 320;

    private MailSenderPolicy() { }

    public static String normalize(String configured) {
        if (configured == null || configured.length() > MAX_CONFIG_LENGTH
                || configured.chars().anyMatch(value -> Character.isISOControl(value) && value != '\r' && value != '\n'))
            throw invalid();
        Set<String> addresses = new LinkedHashSet<>();
        for (String entry : configured.split("[,;，；\\r\\n]+")) {
            String address = entry.trim();
            if (address.isEmpty()) continue;
            addresses.add(normalizeAddress(address));
            if (addresses.size() > MAX_ADDRESSES) throw invalid();
        }
        if (addresses.isEmpty()) throw invalid();
        String normalized = String.join(",", addresses);
        if (normalized.length() > MAX_CONFIG_LENGTH) throw invalid();
        return normalized;
    }

    public static boolean matches(String configured, String actual) {
        if (actual == null) return false;
        try {
            for (String allowed : normalize(configured).split(","))
                if (allowed.equalsIgnoreCase(actual)) return true;
            return false;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalizeAddress(String value) {
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.chars().anyMatch(Character::isWhitespace))
            throw invalid();
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (address.isGroup() || address.getPersonal() != null || !value.equals(address.getAddress())) throw invalid();
        } catch (AddressException ignored) {
            throw invalid();
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Mailbox sender addresses are invalid");
    }
}
