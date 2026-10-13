package com.automation.sites.listeners;

import com.automation.core.mail.RunSummary;
import org.junit.jupiter.api.Test;
import org.testng.IClass;
import org.testng.IResultMap;
import org.testng.ISuite;
import org.testng.ISuiteResult;
import org.testng.ITestContext;
import org.testng.ITestResult;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives {@link PostRunReportListener#summarise} with hand-made TestNG result objects. */
class PostRunReportListenerTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (p, m, a) -> switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> type.getSimpleName() + "Proxy";
                default -> handler.apply(m.getName(), a);
            });
    }

    private static ITestResult result(String name, boolean retried, Throwable error) {
        IClass cls = proxy(IClass.class, (n, a) -> "getRealClass".equals(n) ? PostRunReportListenerTest.class : null);
        return proxy(ITestResult.class, (n, a) -> switch (n) {
            case "getName" -> name;
            case "wasRetried" -> retried;
            case "getThrowable" -> error;
            case "getTestClass" -> cls;
            default -> null;
        });
    }

    private static IResultMap map(List<ITestResult> results) {
        return proxy(IResultMap.class, (n, a) -> switch (n) {
            case "size" -> results.size();
            case "getAllResults" -> (Set<ITestResult>) results.stream().collect(Collectors.toSet());
            default -> null;
        });
    }

    @Test
    void countsMatchTestNgButRetriedSkipsAreNotCounted() {
        ITestContext ctx = proxy(ITestContext.class, (n, a) -> switch (n) {
            case "getPassedTests" -> map(List.of(result("p1", false, null), result("p2", false, null)));
            case "getFailedTests" -> map(List.of(result("f1", false,
                new AssertionError("expected password=hunter2"))));
            case "getFailedButWithinSuccessPercentageTests" -> map(List.of());
            case "getSkippedTests" -> map(List.of(result("retryAttempt", true, null),
                result("quarantined", false, null)));
            default -> null;
        });
        ISuiteResult sr = proxy(ISuiteResult.class, (n, a) -> "getTestContext".equals(n) ? ctx : null);
        ISuite suite = proxy(ISuite.class, (n, a) -> switch (n) {
            case "getResults" -> Map.of("t", sr);
            case "getName" -> "My Suite";
            default -> null;
        });

        RunSummary s = PostRunReportListener.summarise(suite, Instant.now());
        assertEquals(2, s.passed());
        assertEquals(1, s.failed());
        assertEquals(1, s.skipped(), "the retried attempt is not a skip");
        assertEquals(4, s.total());
        assertEquals("My Suite", s.suite());
        assertEquals("FAILED", s.status());
        assertEquals("f1", s.failures().get(0).name());
        assertTrue(s.failures().get(0).message().contains("AssertionError"));
        assertTrue(!s.failures().get(0).message().contains("hunter2"), s.failures().get(0).message());
    }
}
