package com.codexquotaguard.backend.system;

/** Fixed local failure, without filesystem paths, credentials, or implementation causes. */
public final class SessionStorageException extends RuntimeException {

    public SessionStorageException() {
        super("SESSION_STORAGE_UNAVAILABLE", null, false, false);
    }
}
