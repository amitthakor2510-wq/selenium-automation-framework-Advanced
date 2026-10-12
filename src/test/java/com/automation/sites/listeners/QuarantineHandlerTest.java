package com.automation.sites.listeners;

import com.automation.core.quarantine.Quarantine;
import com.automation.sites.listeners.QuarantineRegistry.Entry;
import org.junit.jupiter.api.Test;
import org.testng.ITestClass;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.internal.ConstructorOrMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quarantine decision made on real TestNG types (a result built with a dynamic proxy): the
 * FAILED-to-SKIPPED flip, expiry, the length cap, never-quarantine groups and Cucumber scenarios.
 * Date and policy are passed in, so nothing here depends on configuration or on today's date.
 */
class QuarantineHandlerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
    private static final String NEVER = "security";

    public static class Sample {
        @Quarantine(until = "2026-11-15", ticket = "QA-1", reason = "flaky")
        public void annotated() {
        }

        @Quarantine(until = "2026-01-01")
        public void expired() {
        }

        @Quarantine(until = "2099-12-31", ticket = "QA-2")
        public void farFuture() {
        }

        @Quarantine(until = "2026-11-15")
        public void securityCheck() {
        }

        public void plain() {
        }

        public void runScenario(Object pickleWrapper, Object feature) {
        }
    }

    /** Look-alike of Cucumber's PickleWrapper - ScenarioInfo only relies on the simple name and getters. */
    public static class PickleWrapper {
        private final String name;
        private final List<String> tags;

        PickleWrapper(String name, String... tags) {
            this.name = name;
            this.tags = List.of(tags);
        }

        public Object getPickle() {
            return new Pickle(name, tags);
        }
    }

    public static class Pickle {
        private final String name;
        private final List<String> tags;

        Pickle(String name, List<String> tags) {
            this.name = name;
            this.tags = tags;
        }

        public String getName() {
            return name;
        }

        public URI getUri() {
            return URI.create("classpath:features/login.feature");
        }

        public List<String> getTags() {
            return tags;
        }
    }

    private static ITestResult result(String methodName, int status, Throwable failure, String[] groups, Object... parameters)
        throws Exception {
        Method method = null;
        for (Method candidate : Sample.class.getMethods()) {
            if (candidate.getName().equals(methodName)) {
                method = candidate;
            }
        }
        final Method target = method;
        ITestNGMethod testMethod = (ITestNGMethod) Proxy.newProxyInstance(QuarantineHandlerTest.class.getClassLoader(),
            new Class[] {ITestNGMethod.class}, (proxy, call, args) -> switch (call.getName()) {
                case "getConstructorOrMethod" -> new ConstructorOrMethod(target);
                case "getMethodName" -> methodName;
                case "getGroups" -> groups;
                default -> null;
            });
        ITestClass testClass = (ITestClass) Proxy.newProxyInstance(QuarantineHandlerTest.class.getClassLoader(),
            new Class[] {ITestClass.class}, (proxy, call, args) -> call.getName().equals("getRealClass") ? Sample.class : null);
        int[] state = {status};
        Throwable[] thrown = {failure};
        return (ITestResult) Proxy.newProxyInstance(QuarantineHandlerTest.class.getClassLoader(),
            new Class[] {ITestResult.class}, (proxy, call, args) -> switch (call.getName()) {
                case "getMethod" -> testMethod;
                case "getTestClass" -> testClass;
                case "getStatus" -> state[0];
                case "setStatus" -> {
                    state[0] = (Integer) args[0];
                    yield null;
                }
                case "getThrowable" -> thrown[0];
                case "setThrowable" -> {
                    thrown[0] = (Throwable) args[0];
                    yield null;
                }
                case "getName" -> methodName;
                case "getParameters" -> parameters;
                default -> null;
            });
    }

    private static ITestResult failing(String methodName, Object... parameters) throws Exception {
        return result(methodName, ITestResult.FAILURE, new AssertionError("boom"), new String[0], parameters);
    }

    @Test
    void aFailingQuarantinedTestBecomesSkippedWithTheOriginalFailureAsCause() throws Exception {
        ITestResult result = failing("annotated");
        Optional<Entry> entry = QuarantineHandler.applyAt(result, TODAY, 90, NEVER);
        assertTrue(entry.isPresent());
        assertEquals(ITestResult.SKIP, result.getStatus());
        assertTrue(result.getThrowable() instanceof SkipException);
        assertTrue(result.getThrowable().getMessage().startsWith("Quarantined - QA-1 (until 2026-11-15)"), result.getThrowable().getMessage());
        assertTrue(result.getThrowable().getCause() instanceof AssertionError);
    }

    @Test
    void aPassingQuarantinedTestStaysPassedAndAnUnquarantinedFailureStaysFailed() throws Exception {
        ITestResult passing = result("annotated", ITestResult.SUCCESS, null, new String[0]);
        assertTrue(QuarantineHandler.applyAt(passing, TODAY, 90, NEVER).isPresent());
        assertEquals(ITestResult.SUCCESS, passing.getStatus());

        ITestResult plain = failing("plain");
        assertFalse(QuarantineHandler.applyAt(plain, TODAY, 90, NEVER).isPresent());
        assertEquals(ITestResult.FAILURE, plain.getStatus());
    }

    @Test
    void anExpiredQuarantineNoLongerProtectsTheTest() throws Exception {
        ITestResult result = failing("expired");
        assertFalse(QuarantineHandler.applyAt(result, TODAY, 90, NEVER).isPresent());
        assertEquals(ITestResult.FAILURE, result.getStatus());
    }

    @Test
    void aQuarantineLongerThanTheCapIsIgnoredSoItCannotBecomePermanent() throws Exception {
        ITestResult capped = failing("farFuture");
        assertFalse(QuarantineHandler.applyAt(capped, TODAY, 90, NEVER).isPresent(), "2099 is far beyond 90 days");
        assertEquals(ITestResult.FAILURE, capped.getStatus());

        ITestResult uncapped = failing("farFuture");
        assertTrue(QuarantineHandler.applyAt(uncapped, TODAY, 0, NEVER).isPresent(), "maxDays=0 switches the cap off");

        assertTrue(QuarantineHandler.withinPolicy(entry(TODAY.plusDays(90)), TODAY, 90));
        assertFalse(QuarantineHandler.withinPolicy(entry(TODAY.plusDays(91)), TODAY, 90));
    }

    @Test
    void aSecurityTestCanNeverBeQuarantined() throws Exception {
        ITestResult security = result("securityCheck", ITestResult.FAILURE, new AssertionError("auth bypass"),
            new String[] {"api", "Security"});
        assertFalse(QuarantineHandler.applyAt(security, TODAY, 90, NEVER).isPresent());
        assertEquals(ITestResult.FAILURE, security.getStatus(), "the vulnerability must still fail the build");

        ITestResult sameTestOtherGroup = result("securityCheck", ITestResult.FAILURE, new AssertionError("x"), new String[] {"api"});
        assertTrue(QuarantineHandler.applyAt(sameTestOtherGroup, TODAY, 90, NEVER).isPresent());

        assertTrue(QuarantineHandler.excludedByGroup(new String[] {"a", " SECURITY "}, "security, pci"));
        assertFalse(QuarantineHandler.excludedByGroup(new String[] {"a"}, "security"));
        assertFalse(QuarantineHandler.excludedByGroup(null, "security"));
        assertFalse(QuarantineHandler.excludedByGroup(new String[] {"security"}, ""), "an empty list excludes nothing");
    }

    @Test
    void cucumberScenariosAreQuarantinedIndividuallyByTheirOwnTags() throws Exception {
        String until = TODAY.plusDays(30).toString();
        ITestResult tagged = failing("runScenario", new PickleWrapper("Locked-out user", "@smoke",
            "@quarantine_until_" + until, "@ticket_QA-9"), "feature");
        assertTrue(QuarantineHandler.applyAt(tagged, TODAY, 90, NEVER).isPresent());
        assertEquals(ITestResult.SKIP, tagged.getStatus());
        assertTrue(tagged.getThrowable().getMessage().contains("QA-9"), tagged.getThrowable().getMessage());

        ITestResult other = failing("runScenario", new PickleWrapper("Valid user", "@smoke"), "feature");
        assertFalse(QuarantineHandler.applyAt(other, TODAY, 90, NEVER).isPresent(), "a neighbouring scenario still fails the build");
        assertEquals(ITestResult.FAILURE, other.getStatus());

        ITestResult tooLong = failing("runScenario", new PickleWrapper("Forever", "@quarantine_until_2099-12-31"), "feature");
        assertFalse(QuarantineHandler.applyAt(tooLong, TODAY, 90, NEVER).isPresent(), "the length cap applies to scenarios too");
    }

    private static Entry entry(LocalDate until) {
        return new Entry("t", until, "", "", "test");
    }
}
