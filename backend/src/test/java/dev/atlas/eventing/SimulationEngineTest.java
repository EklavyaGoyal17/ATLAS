package dev.atlas.eventing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SimulationEngineTest {

    @ParameterizedTest
    @EnumSource(SimulationEngine.Scenario.class)
    void recipesAreDeterministicBoundedAndPreserveTheirStatedFinalInvariant(
        SimulationEngine.Scenario scenario
    ) {
        var engine = new SimulationEngine();
        for (long seed = 0; seed < 100; seed++) {
            var first = engine.run(scenario, seed);
            assertThat(first).isEqualTo(engine.run(scenario, seed));
            assertThat(first.invariant())
                .as("%s seed %s", scenario, seed)
                .isTrue();
            assertThat(first.timeline()).hasSizeLessThan(501);
            assertThat(
                first
                    .timeline()
                    .stream()
                    .map(SimulationEngine.Event::tick)
                    .toList()
            ).isSorted();
        }
    }
}
