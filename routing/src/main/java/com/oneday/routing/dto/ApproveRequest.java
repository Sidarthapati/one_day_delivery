package com.oneday.routing.dto;

import java.util.UUID;

/**
 * Body for {@code POST /routing/plans/{planId}/approve}. {@code actorId} is the approving station
 * manager / admin (recorded as {@code route_plan.approved_by} and in the audit). Real M1 city-scope
 * enforcement is wired the same way grid's {@code ProposalController} takes its reviewer — JWT
 * integration lands with the cross-module auth pass.
 */
public record ApproveRequest(UUID actorId) {}
