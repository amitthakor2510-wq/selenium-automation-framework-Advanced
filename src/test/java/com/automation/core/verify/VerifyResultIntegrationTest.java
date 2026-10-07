package com.automation.core.verify;

import org.junit.jupiter.api.Test;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;
import org.testng.TestNG;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real (in-process) TestNG suite to prove the contract {@code TestListener} relies on:
 * a result flipped to FAILURE inside {@code afterInvocation} is what TestNG then reports to its
 * listeners. The tiny listener below does exactly the one call TestListener makes.
 */
class VerifyResultIntegrationTest {

    /** Same call {@code TestListener.afterInvocation} makes for every test method. */
    public static class FoldSoftFailures implements IInvokedMethodListener {
        @Override
        public void afterInvocation(IInvokedMethod method, ITestResult result) {
            if (method.isTestMethod()) {
                Verify.applyToResult(result);
            }
        }
    }

    /** Fixture executed by TestNG below - deliberately not named *Test so no runner picks it up. */
    public static class SoftFixture {
        @org.testng.annotations.Test
        public void allChecksPass() {
            Verify.verifyTrue(true, "ok", FailureHandling.CONTINUE_ON_FAILURE);
        }

        @org.testng.annotations.Test
        public void softFailuresFailTheTestAtTheEnd() {
            Verify.verifyEquals("a", "b", "first", FailureHandling.CONTINUE_ON_FAILURE);
            Verify.verifyEquals("c", "d", "second", FailureHandling.CONTINUE_ON_FAILURE);
        }

        @org.testng.annotations.Test
        public void optionalFailureDoesNotFailTheTest() {
            Verify.verifyTrue(false, "nice to have", FailureHandling.OPTIONAL);
        }

        @org.testng.annotations.Test
        public void hardFailureKeepsEarlierSoftFailures() {
            Verify.verifyTrue(false, "soft one", FailureHandling.CONTINUE_ON_FAILURE);
            Verify.verifyTrue(false, "hard one", FailureHandling.STOP_ON_FAILURE);
        }

        @org.testng.annotations.Test
        public void failuresDoNotLeakIntoTheNextTest() {
            Verify.verifyTrue(true, "clean slate", FailureHandling.CONTINUE_ON_FAILURE);
        }
    }

    @Test
    void testNgReportsTheFoldedResult() {
        TestListenerAdapter results = new TestListenerAdapter();
        TestNG testng = new TestNG(false);
        testng.setUseDefaultListeners(false);
        // The repo registers real listeners (ReportPortal, coverage map, ...) through
        // META-INF/services; this nested run must not load them, so give it an empty classpath
        // to search for service providers.
        testng.setServiceLoaderClassLoader(new URLClassLoader(new URL[0], null));
        testng.setTestClasses(new Class<?>[] {SoftFixture.class});
        testng.setPreserveOrder(true);
        testng.addListener(new FoldSoftFailures());
        testng.addListener(results);
        testng.run();

        assertEquals(List.of("allChecksPass", "failuresDoNotLeakIntoTheNextTest", "optionalFailureDoesNotFailTheTest"),
            names(results.getPassedTests()).stream().sorted().collect(Collectors.toList()));
        assertEquals(List.of("hardFailureKeepsEarlierSoftFailures", "softFailuresFailTheTestAtTheEnd"),
            names(results.getFailedTests()).stream().sorted().collect(Collectors.toList()));

        ITestResult soft = find(results.getFailedTests(), "softFailuresFailTheTestAtTheEnd");
        assertTrue(soft.getThrowable() instanceof VerificationError, String.valueOf(soft.getThrowable()));
        assertTrue(soft.getThrowable().getMessage().contains("2 verification(s) failed"));

        ITestResult hard = find(results.getFailedTests(), "hardFailureKeepsEarlierSoftFailures");
        assertTrue(hard.getThrowable().getMessage().contains("hard one"));
        assertEquals(1, hard.getThrowable().getSuppressed().length);
    }

    private static List<String> names(List<ITestResult> results) {
        return results.stream().map(r -> r.getMethod().getMethodName()).collect(Collectors.toList());
    }

    private static ITestResult find(List<ITestResult> results, String method) {
        return results.stream().filter(r -> r.getMethod().getMethodName().equals(method)).findFirst().orElseThrow();
    }
}
