package com.pingche.web.mail;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.stereotype.Component;

@Component
public class MailBodyReader {
    static final int MAX_BYTES = 262_144;

    LoginMailMessage read(Message message) throws MessagingException, IOException {
        if (message.getSize() < 0 || message.getSize() > MAX_BYTES) return null;
        java.util.Date received = message.getReceivedDate();
        return new LoginMailMessage(addresses(message.getFrom()), addresses(message.getAllRecipients()),
                message.getSubject(), body(message, 0, new Budget()), received == null ? null : received.toInstant());
    }

    String body(Part part, int depth, Budget budget) throws MessagingException, IOException {
        if (depth > 8 || ++budget.parts > 32) throw new IOException("Mailbox message exceeds limits");
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) return "";
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            byte[] bytes;
            try (InputStream input = part.getInputStream()) {
                bytes = input.readNBytes(MAX_BYTES - budget.bytes + 1);
            }
            budget.bytes += bytes.length;
            if (budget.bytes > MAX_BYTES) throw new IOException("Mailbox message exceeds limits");
            String charset = new ContentType(part.getContentType()).getParameter("charset");
            Charset encoding;
            try { encoding = charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset); }
            catch (IllegalArgumentException ignored) { return ""; }
            String text = new String(bytes, encoding);
            return part.isMimeType("text/html") ? htmlText(text) : text;
        }
        if (!part.isMimeType("multipart/*")) return "";
        Object content = part.getContent();
        if (!(content instanceof Multipart multipart) || multipart.getCount() > 32)
            throw new IOException("Mailbox message exceeds limits");
        if (part.isMimeType("multipart/alternative")) {
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (child.isMimeType("text/plain") && child.getFileName() == null
                        && !Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) {
                    String text = body(child, depth + 1, budget);
                    if (!text.isBlank()) return text;
                }
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = body(multipart.getBodyPart(i), depth + 1, budget);
                if (!text.isBlank()) return text;
            }
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            result.append(body(multipart.getBodyPart(i), depth + 1, budget)).append('\n');
            if (result.length() > LoginMailMatcher.MAX_BODY_CHARS) throw new IOException("Mailbox message exceeds limits");
        }
        return result.toString();
    }

    String htmlText(String html) throws IOException {
        StringBuilder text = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private int ignored;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) ignored++;
                if (ignored == 0 && tag.isBlock()) text.append(' ');
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) ignored = Math.max(0, ignored - 1);
                if (ignored == 0 && tag.isBlock()) text.append(' ');
            }
            @Override public void handleText(char[] data, int pos) { if (ignored == 0) text.append(data); }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (ignored == 0 && tag == HTML.Tag.BR) text.append(' ');
            }
        }, true);
        return text.toString();
    }

    private List<String> addresses(Address[] addresses) {
        if (addresses == null || addresses.length > 50) return List.of();
        List<String> result = new ArrayList<>();
        for (Address address : addresses) if (address instanceof InternetAddress internet) result.add(internet.getAddress());
        return result;
    }
    static final class Budget { int bytes; int parts; }
}
