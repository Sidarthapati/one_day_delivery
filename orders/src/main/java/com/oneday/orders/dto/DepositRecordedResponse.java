package com.oneday.orders.dto;

/**
 * The result of a DA declaring a cash deposit: the deposit row plus the one-time {@code handoffOtp} the
 * DA shows the station cashier to confirm receipt. The OTP is returned only here (to the authenticated
 * DA who made the request) and is never persisted in cleartext.
 */
public record DepositRecordedResponse(CodCashDepositResponse deposit, String handoffOtp) {
}
