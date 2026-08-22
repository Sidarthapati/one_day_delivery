package com.oneday.common.storage;

/** Wraps any failure from the object-storage backend (network, missing object, auth). */
public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ObjectStorageException(String message) {
        super(message);
    }
}
