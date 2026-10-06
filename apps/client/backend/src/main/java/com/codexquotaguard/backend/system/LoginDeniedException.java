package com.codexquotaguard.backend.system;

/** Fixed local failure for invalid product-account credentials. */
final class LoginDeniedException extends RuntimeException {

    LoginDeniedException() {
        super("SYSTEM_LOGIN_DENIED", null, false, false);
    }
}
