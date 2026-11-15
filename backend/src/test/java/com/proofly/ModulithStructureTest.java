package com.proofly;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

    @Test
    void verifiesModularBoundaries() {
        ApplicationModules modules = ApplicationModules.of(ProoflyApplication.class);
        modules.verify();
    }
}
