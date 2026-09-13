package com.oneday.common.port.dto.bgv;

/**
 * The background-verification checks we run per candidate, mirroring the IDfy-style set seen in the KT
 * video. PAN/DL/Address are instant digital checks; DATABASE_CRIMINAL/EFIR/POLICE are (in a real vendor)
 * field/TAT checks — the port is poll-based so both kinds fit the same contract.
 */
public enum BgvCheckType {
    PAN,
    DRIVING_LICENSE,
    ADDRESS,
    DATABASE_CRIMINAL,
    EFIR,
    POLICE
}
