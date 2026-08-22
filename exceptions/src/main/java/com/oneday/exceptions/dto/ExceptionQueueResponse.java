package com.oneday.exceptions.dto;

import java.util.List;

/** A page of the problem-solve queue. */
public record ExceptionQueueResponse(int page, int size, long total, List<ExceptionCaseSummary> items) {
}
