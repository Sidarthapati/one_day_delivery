package com.oneday.airline.service;

import com.oneday.airline.domain.Awb;

import java.util.UUID;

/** The two confirmations the airport ground team gives back (§9): handed over, and fully loaded. */
public interface AwbGroundService {

    Awb handOver(UUID awbId);

    Awb markLoaded(UUID awbId);
}
