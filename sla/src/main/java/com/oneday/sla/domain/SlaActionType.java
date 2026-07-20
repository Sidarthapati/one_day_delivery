package com.oneday.sla.domain;

/** A human action recorded against an escalation (append-only {@code sla_action}). */
public enum SlaActionType {
    ACKNOWLEDGE,
    RESOLVE,
    NOTE
}
