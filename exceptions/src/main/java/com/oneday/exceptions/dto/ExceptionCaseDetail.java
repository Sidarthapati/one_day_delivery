package com.oneday.exceptions.dto;

import java.util.List;

/** A case plus its append-only action history. */
public record ExceptionCaseDetail(ExceptionCaseSummary caseSummary, List<ExceptionActionView> actions) {
}
