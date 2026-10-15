package com.pingche.web.weblogin;

import java.util.Set;

public final class BrowserInputPolicy {
    private static final Set<String> KEYS = Set.of("Tab", "Enter", "Backspace", "Delete", "Escape", "ArrowLeft",
            "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End", "PageUp", "PageDown", "Space");
    private BrowserInputPolicy() { }

    public static void validate(ManagedLoginBrowser.Input input) {
        if (input == null || input.type() == null) throw invalid();
        switch (input.type()) {
            case "OPEN_SESSIONS" -> {
                if (input.x() != null || input.y() != null || input.deltaY() != null || input.key() != null || input.text() != null
                        || input.ctrl() || input.shift()) throw invalid();
            }
            case "CLICK", "WHEEL" -> {
                if (input.x() == null || input.x() < 0 || input.x() >= ManagedLoginBrowser.WIDTH
                        || input.y() == null || input.y() < 0 || input.y() >= ManagedLoginBrowser.HEIGHT
                        || input.key() != null || input.text() != null || input.ctrl() || input.shift()) throw invalid();
                if ("CLICK".equals(input.type()) && input.deltaY() != null) throw invalid();
                if ("WHEEL".equals(input.type()) && (input.deltaY() == null || input.deltaY() == 0 || Math.abs((long) input.deltaY()) > 1000)) throw invalid();
            }
            case "TEXT" -> {
                if (input.text() == null || input.text().isEmpty() || input.text().length() > 2048
                        || input.text().codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)
                        || input.x() != null || input.y() != null || input.deltaY() != null || input.key() != null
                        || input.ctrl() || input.shift()) throw invalid();
            }
            case "KEY" -> {
                if (input.x() != null || input.y() != null || input.deltaY() != null || input.text() != null || input.key() == null) throw invalid();
                if (input.ctrl()) { if (!"a".equals(input.key()) || input.shift()) throw invalid(); }
                else if (!KEYS.contains(input.key()) || input.shift() && !"Tab".equals(input.key())) throw invalid();
            }
            default -> throw invalid();
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("WEB_LOGIN_INPUT_INVALID"); }
}
