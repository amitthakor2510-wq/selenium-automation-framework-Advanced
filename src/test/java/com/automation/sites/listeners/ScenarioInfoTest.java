package com.automation.sites.listeners;

import com.automation.sites.listeners.QuarantineRegistry.Scenario;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ScenarioInfo reads a Cucumber scenario out of TestNG parameters - tested with look-alike classes, no Cucumber needed. */
class ScenarioInfoTest {

    /** Same simple name and getters as Cucumber's types; that is all ScenarioInfo relies on. */
    public static class PickleWrapper {
        private final FakePickle pickle;

        PickleWrapper(FakePickle pickle) {
            this.pickle = pickle;
        }

        public FakePickle getPickle() {
            return pickle;
        }
    }

    public static class FakePickle {
        public String getName() {
            return "Login with a locked-out user";
        }

        public URI getUri() {
            return URI.create("classpath:features/login.feature");
        }

        public List<String> getTags() {
            return List.of("@smoke", "@flaky");
        }
    }

    @Test
    void readsNameFeatureAndTagsFromThePickleWrapperParameter() {
        Optional<Scenario> scenario = ScenarioInfo.from(new Object[] {new PickleWrapper(new FakePickle()), "feature"});
        assertTrue(scenario.isPresent());
        assertEquals("Login with a locked-out user", scenario.get().name());
        assertEquals("classpath:features/login.feature", scenario.get().feature());
        assertEquals(List.of("@smoke", "@flaky"), scenario.get().tags());
    }

    @Test
    void ordinaryTestParametersAreNotAScenario() {
        assertFalse(ScenarioInfo.from(new Object[] {"a", 1}).isPresent());
        assertFalse(ScenarioInfo.from(new Object[0]).isPresent());
        assertFalse(ScenarioInfo.from(null).isPresent());
    }

    @Test
    void aWrapperWithoutAPickleIsIgnoredRatherThanFailing() {
        assertFalse(ScenarioInfo.from(new Object[] {new PickleWrapper(null)}).isPresent());
    }
}
