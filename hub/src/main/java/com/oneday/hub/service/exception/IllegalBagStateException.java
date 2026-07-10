package com.oneday.hub.service.exception;

/** The bag is not in the state this operation requires (e.g. add to a sealed bag, dispatch an open one). → 409. */
public class IllegalBagStateException extends RuntimeException {
    public IllegalBagStateException(String detail) {
        super(detail);
    }
}
