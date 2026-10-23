package com.automation.sites.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IAnnotationTransformer;
import org.testng.annotations.ITestAnnotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies test-config.properties (see {@link TestSelection}) by setting
 * {@code enabled=false} on every @Test annotation the file switches off —
 * TestNG then never schedules it, in any suite, on any CI system.
 *
 * <p>Registered through META-INF/services/org.testng.ITestNGListener rather
 * than each suite XML's {@code <listeners>} block, so it also covers suites
 * that don't list RetryListener (the *-perf.xml ones) and any suite added
 * later without anyone having to remember to wire it. A disabled test is not
 * reported as skipped — TestNG simply doesn't see it, exactly like
 * {@code @Test(enabled = false)}. The console log names every one that was
 * switched off and why.
 */
public class TestSelectionListener implements IAnnotationTransformer {

    private static final Logger logger = LoggerFactory.getLogger(TestSelectionListener.class);

    // TestNG may run the transformer more than once for the same method (once
    // per suite that includes the class) — log each switched-off test once.
    private static final Set<String> alreadyLogged = ConcurrentHashMap.newKeySet();

    @Override
    @SuppressWarnings("rawtypes")
    public void transform(ITestAnnotation annotation, Class testClass,
                          Constructor testConstructor, Method testMethod) {
        // For an @Test on a method TestNG passes testMethod (testClass is null);
        // for a class-level @Test it passes testClass (testMethod is null).
        Class<?> owner = testMethod != null ? testMethod.getDeclaringClass() : testClass;
        if (owner == null) {
            return;
        }
        String methodName = testMethod != null ? testMethod.getName() : null;

        Optional<String> reason = TestSelection.get()
            .disabledReason(owner.getName(), methodName, annotation.getGroups());
        if (reason.isPresent()) {
            annotation.setEnabled(false);
            String label = owner.getSimpleName() + (methodName != null ? "#" + methodName : " (class-level)");
            if (alreadyLogged.add(label)) {
                logger.info("[TestSelection] Switched off {} — {}", label, reason.get());
            }
        }
    }
}
