package com.oneday.grid.dto.request;

import java.util.UUID;

public record ProposalRejectRequest(
        UUID reviewerId,
        String notes
) {}
