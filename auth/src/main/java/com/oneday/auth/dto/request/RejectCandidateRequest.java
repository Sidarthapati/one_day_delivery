package com.oneday.auth.dto.request;

/** Optional reason attached when a reviewer rejects an onboarding candidate. */
public record RejectCandidateRequest(String reason) {}
