package com.oneday.dispatch.domain;

/**
 * Why the DA is stepping away — extensible: a new "kind of break" is just a new enum value here (and,
 * if it shouldn't consume the personal allowance, a {@code dispatch.disposition.non-counting-reasons}
 * config entry). All BREAK-category reasons behave identically underneath (hold territory, slot-checked).
 */
public enum DispositionReason {
    LUNCH,
    REST,
    EV_CHARGING,
    COMPANY_WORK,   // the canonical AUXILIARY reason
    OTHER
}
