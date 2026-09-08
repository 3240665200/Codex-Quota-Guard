package com.codexquotaguard.backend.room;

public class RoomStorageException extends RuntimeException {

    public RoomStorageException(String message) {
        super(message);
    }

    public RoomStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
