package com.pingche.web.mail;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LoginMailMatcher {
    public static final int MAX_BODY_CHARS = 262_144;
    private static final int MAX_SUBJECT_CHARS = 4096;
    private static final String ENGLISH_LOGIN = "(?:log[ -]?in|sign[ -]?in)";
    private static final String CHINESE_LOGIN = "(?:登录|登入|登錄)";
    private static final String CHINESE_CODE = "(?:验证码|驗證碼|代码|代碼)";
    private static final String LOGIN_CODE_LABEL = "(?:\\b" + ENGLISH_LOGIN
            + "\\s+(?:(?:verification|temporary|one-time)\\s+)?code\\b|"
            + CHINESE_LOGIN + "\\s*(?:(?:临时|臨時|一次性)\\s*)?" + CHINESE_CODE + ")";
    private static final String LOGIN_SUFFIX = "(?:\\s+(?:to|for)\\s+" + ENGLISH_LOGIN
            + "(?:\\s+(?:to\\s+)?chatgpt)?|\\s*(?:以|来|來|用于|用於|完成)\\s*"
            + CHINESE_LOGIN + "(?:\\s*chatgpt)?)";
    private static final Pattern PRODUCT = Pattern.compile("(?<![a-z0-9_])chatgpt(?![a-z0-9_])");
    private static final Pattern LOGIN_PURPOSE = Pattern.compile(LOGIN_CODE_LABEL
            + "|(?:\\bcode\\b|" + CHINESE_CODE + ")" + LOGIN_SUFFIX);
    private static final Pattern LABELED_CODE = Pattern.compile("(?:" + LOGIN_CODE_LABEL
            + "|\\b(?:your\\s+(?:(?:verification|temporary|one-time)\\s+)?code"
            + "|(?:verification|temporary|one-time)\\s+code"
            + "|(?:enter|use)\\s+(?:this|the|following)\\s+code)\\b|" + CHINESE_CODE + ")"
            + LOGIN_SUFFIX + "?\\s*(?:(?:is|是|为|為)\\s*)?:?\\s*([0-9]{6})");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final Pattern NON_LOGIN_PURPOSE = Pattern.compile(
            "\\b(?:password\\s+reset|reset(?:ting)?\\s+(?:(?:your|the)\\s+)?password"
            + "|account\\s+recovery|recover(?:ing)?\\s+(?:(?:your|the)\\s+)?account)\\b"
            + "|(?:重置|重设|重設)[^.!?。！？]{0,40}(?:密码|密碼)"
            + "|(?:密码|密碼)\\s*(?:重置|重设|重設)"
            + "|(?:账户|帐户|帳戶|账号|帐号|帳號)\\s*(?:恢复|恢復|復原|找回)");
    private static final Pattern SAFETY_REMINDER = Pattern.compile(
            "(?:\\bif\\s+(?:(?:this|that|it)\\s+(?:wasn't|was not|isn't|is not)\\s+you"
            + "|you\\s+(?:didn't|did not)\\s+(?:request|initiate))"
            + "|(?:如果|若|如)(?:不是|非))[^.!?。！？]{0,240}(?:[.!?。！？]|$)");

    public Optional<String> findCode(LoginMailMessage message, String sender, String recipient, Instant armedAt) {
        if (message == null || message.receivedAt() == null || message.receivedAt().isBefore(armedAt.truncatedTo(ChronoUnit.SECONDS))
                || message.senders() == null || message.senders().size() != 1
                || !sender.equalsIgnoreCase(message.senders().get(0))
                || message.recipients() == null || message.recipients().stream().noneMatch(recipient::equalsIgnoreCase)
                || message.body() == null || message.body().length() > MAX_BODY_CHARS
                || message.subject() != null && message.subject().length() > MAX_SUBJECT_CHARS) return Optional.empty();
        String subject = normalize(message.subject());
        String body = normalize(message.body());
        String content = subject + "\n" + body;
        if (!PRODUCT.matcher(content).find() || !LOGIN_PURPOSE.matcher(content).find()
                || NON_LOGIN_PURPOSE.matcher(subject).find()
                || NON_LOGIN_PURPOSE.matcher(SAFETY_REMINDER.matcher(body).replaceAll(" ")).find()) {
            return Optional.empty();
        }

        // Repeated MIME representations may repeat a code; a different six-digit value is ambiguous.
        String uniqueCode = null;
        Matcher codes = CODE.matcher(content);
        while (codes.find()) {
            if (!hasCodeBoundaries(content, codes.start(), codes.end())) continue;
            if (uniqueCode != null && !uniqueCode.equals(codes.group())) return Optional.empty();
            uniqueCode = codes.group();
        }
        if (uniqueCode == null) return Optional.empty();

        Matcher labeled = LABELED_CODE.matcher(content);
        while (labeled.find()) {
            if (uniqueCode.equals(labeled.group(1)) && hasCodeBoundaries(content, labeled.start(1), labeled.end(1))) {
                return Optional.of(uniqueCode);
            }
        }
        // A code-only body is safe only when the subject itself identifies a ChatGPT login code.
        return body.equals(uniqueCode) && PRODUCT.matcher(subject).find() && LOGIN_PURPOSE.matcher(subject).find()
                ? Optional.of(uniqueCode) : Optional.empty();
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\p{Pd}", "-").replace('\u2019', '\'')
                .replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasCodeBoundaries(String text, int start, int end) {
        if (start > 0 && isTokenContinuation(text.codePointBefore(start))) return false;
        if (end < text.length() && isTokenContinuation(text.codePointAt(end))) return false;
        // Do not extract the numeric part of a host name, URL, or email address.
        return !(start > 0 && text.charAt(start - 1) == '.')
                && !(end + 1 < text.length() && text.charAt(end) == '.'
                && Character.isLetterOrDigit(text.codePointAt(end + 1)));
    }

    private static boolean isTokenContinuation(int codePoint) {
        return Character.isDigit(codePoint)
                || Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN
                || "_/@?&=#%+-".indexOf(codePoint) >= 0;
    }
}
