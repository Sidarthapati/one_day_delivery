package com.oneday.grid;

import com.google.ortools.sat.CpModel;
import org.junit.jupiter.api.Test;

class OrToolsSmokeTest {

    @Test
    void orToolsNativeLibLoads() {
        new CpModel();
    }
}
