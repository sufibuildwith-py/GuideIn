package io.guidein.architecture;

import io.guidein.GuideInApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@Tag("architecture")
class ModuleArchitectureTest {
    @Test
    void moduleBoundariesAreAcyclicAndOnlyApisAreImported() {
        ApplicationModules.of(GuideInApplication.class).verify();
    }
}
