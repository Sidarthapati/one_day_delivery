package com.oneday.auth.domain;

/** The documents an onboarding candidate uploads for verification (image or PDF, stored in R2). */
public enum OnboardingDocType {
    AADHAAR_FRONT,
    AADHAAR_BACK,
    PAN,
    DRIVING_LICENSE,
    VOTER,
    PHOTO
}
