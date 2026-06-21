package com.oneday.dispatch.dto.request;

/** Delivery completion; {@code codCollected} true when cash was taken (emits COD_COLLECTED). */
public record DropCompletedRequest(boolean codCollected) {
}
