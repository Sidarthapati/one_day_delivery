package com.oneday.routing.service;

import com.oneday.routing.service.model.CustodyResult;
import com.oneday.routing.service.model.VanCustodyCommand;

// The four custody transfer points (§11.1, M6-D-014): LOAD (hub→van), DELIVER (van→DA),
// COLLECT (DA→van), UNLOAD (van→hub). Each scan is written to M8's append-only ledger and
// advances the bound manifest item's status; the scan itself is the signal M4 maps to a state
// transition (HANDED_TO_DROP_VAN / DROP_COLLECTED / HANDED_TO_PICKUP_VAN / AT_ORIGIN_HUB).
// Enforces custody continuity (C12): a scan only advances an item from its legal predecessor state.
public interface CustodyService {

    CustodyResult record(VanCustodyCommand command);
}
