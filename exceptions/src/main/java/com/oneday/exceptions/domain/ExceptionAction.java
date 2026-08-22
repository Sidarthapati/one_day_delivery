package com.oneday.exceptions.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Append-only audit of every action taken on a case — who did what, when. Never mutated. */
@Entity
@Table(name = "exception_action")
@Getter
@Setter
@NoArgsConstructor
public class ExceptionAction extends BaseEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "acted_by")
    private String actedBy;

    @Column(name = "acted_by_role")
    private String actedByRole;

    @Column(name = "notes")
    private String notes;
}
