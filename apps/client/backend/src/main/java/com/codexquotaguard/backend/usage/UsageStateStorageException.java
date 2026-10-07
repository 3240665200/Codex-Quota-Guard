package com.codexquotaguard.backend.usage;

/** Internal persistence failure that never contains JSONL content or credentials. */
public final class UsageStateStorageException extends RuntimeException {

    public UsageStateStorageException() {
        super(null, null, false, false);
    }
}
