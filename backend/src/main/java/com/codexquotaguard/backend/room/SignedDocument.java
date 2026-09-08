package com.codexquotaguard.backend.room;

public record SignedDocument<T>(T payload, String signature) {
}
