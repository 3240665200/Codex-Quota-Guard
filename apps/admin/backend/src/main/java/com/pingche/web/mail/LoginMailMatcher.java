package com.pingche.web.mail;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LoginMailMatcher {
    public static final int MAX_BODY_CHARS = 262_144;
    private static final String TITLE = "ChatGPT Log-in Code";
    private static final String PROMPT = "We noticed a suspicious log-in on your account. If that was you, enter this code:";
    private static final Pattern CODE = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])");

    public Optional<String> findCode(LoginMailMessage message, String sender, String recipient, Instant armedAt) {
        if (message == null || message.receivedAt() == null || message.receivedAt().isBefore(armedAt.truncatedTo(ChronoUnit.SECONDS))
                || message.senders() == null || message.senders().size() != 1
                || !sender.equalsIgnoreCase(message.senders().get(0))
                || message.recipients() == null || message.recipients().stream().noneMatch(recipient::equalsIgnoreCase)
                || message.body() == null || message.body().length() > MAX_BODY_CHARS) return Optional.empty();
        String body = message.body().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (!body.contains(TITLE) || !body.contains(PROMPT)) return Optional.empty();
        Matcher codes = CODE.matcher(body);
        if (!codes.find()) return Optional.empty();
        String code = codes.group(1);
        int position = codes.start();
        int afterPrompt = body.indexOf(PROMPT) + PROMPT.length();
        if (codes.find() || position < afterPrompt || !body.substring(afterPrompt, position).isBlank()) return Optional.empty();
        return Optional.of(code);
    }
}
