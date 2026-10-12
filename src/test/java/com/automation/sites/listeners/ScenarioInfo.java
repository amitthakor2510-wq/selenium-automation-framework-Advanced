package com.automation.sites.listeners;

import com.automation.sites.listeners.QuarantineRegistry.Scenario;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the Cucumber scenario (name, feature file, tags) out of a TestNG result's parameters.
 * Every scenario runs through one TestNG method, {@code runScenario(PickleWrapper, FeatureWrapper)},
 * so the scenario's identity is only in the first parameter.
 *
 * <p>Done by reflection on purpose: the shape of Cucumber's pickle types differs between
 * versions, and a plain-Java reader keeps this listener free of any compile-time dependency on
 * them - and testable with fake classes. Anything unexpected yields {@link Optional#empty()}, in
 * which case the test is simply treated as an ordinary class/method, never as an error.
 */
final class ScenarioInfo {

    private ScenarioInfo() {
    }

    static Optional<Scenario> from(Object[] parameters) {
        if (parameters == null) {
            return Optional.empty();
        }
        for (Object parameter : parameters) {
            if (parameter != null && "PickleWrapper".equals(parameter.getClass().getSimpleName())) {
                return read(parameter);
            }
        }
        return Optional.empty();
    }

    private static Optional<Scenario> read(Object wrapper) {
        try {
            Object pickle = call(wrapper, "getPickle");
            if (pickle == null) {
                return Optional.empty();
            }
            Object name = call(pickle, "getName");
            Object uri = call(pickle, "getUri");
            Object tags = call(pickle, "getTags");
            List<String> tagList = new ArrayList<>();
            if (tags instanceof Iterable<?> iterable) {
                for (Object tag : iterable) {
                    tagList.add(String.valueOf(tag));
                }
            }
            return name == null ? Optional.empty()
                : Optional.of(new Scenario(String.valueOf(name), uri == null ? "" : String.valueOf(uri), tagList));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Object call(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        try {
            method.setAccessible(true); // implementation classes of Cucumber's interfaces may be package-private
        } catch (RuntimeException ignored) {
            // fall through: a public method on a public class works without it
        }
        return method.invoke(target);
    }
}
