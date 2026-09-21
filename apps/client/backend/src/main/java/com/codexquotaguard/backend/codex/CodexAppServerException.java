package com.codexquotaguard.backend.codex;

public class CodexAppServerException extends RuntimeException {

    public CodexAppServerException(String message) {
        super(message);
    }

    public CodexAppServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
