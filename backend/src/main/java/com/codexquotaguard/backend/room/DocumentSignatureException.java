package com.codexquotaguard.backend.room;

public class DocumentSignatureException extends RuntimeException {

    public DocumentSignatureException(String message) {
        super(message);
    }

    public DocumentSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
