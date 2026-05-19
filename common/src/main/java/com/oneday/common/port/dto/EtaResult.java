package com.oneday.common.port.dto;

import java.time.Instant;

public record EtaResult(Instant etaPromised, int slaCommitmentMinutes) {}
