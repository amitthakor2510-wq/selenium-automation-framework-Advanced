package com.automation.sites.listeners;

import org.testng.IClassListener;
import org.testng.ISuiteListener;
import org.testng.ITestClass;
import org.testng.ISuite;

import javax.management.MBeanServer;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resets the JaCoCo runtime before each test class and dumps its execution data right after —
 * one {@code .exec} file per test class, under {@code target/jacoco-per-test/} — so
 * {@code com.automation.core.coverage.CoverageMapBuilder} can later say "these are the classes
 * this specific test class actually executed", independent of whether any of them show up as a
 * static bytecode reference. See {@code TEST_IMPACT_ANALYSIS.md} → "Coverage-based fallback".
 *
 * <p><b>Only active when {@code -Djacoco.jmx=true}</b> (this project's {@code jacoco.jmx}
 * property, off by default — see its comment in {@code pom.xml}) registered the
 * {@code org.jacoco:type=Runtime} JMX MBean this class talks to. Every method here checks for
 * that MBean first and silently does nothing at all if it isn't there — an ordinary
 * {@code mvn test} run must behave identically whether or not this listener happens to be on the
 * suite (see {@code AlterSuiteForCoverageMapListener}, which is what actually attaches it, only
 * when {@code -Dcoverage.map.enabled=true} too).
 *
 * <p><b>Concurrency is the one thing this cannot safely paper over.</b> JaCoCo's runtime data is
 * one shared accumulator per JVM — if two test classes execute at the same time on different
 * threads (this project's own {@code parallel="classes"} TestNG suites do exactly that), a
 * reset/dump cycle attributed to "test class A" would actually contain whatever class B happened
 * to touch in that same window, and vice versa: silently wrong data, which is far worse for a
 * tool feeding test-impact decisions than no data at all. This listener detects that condition
 * (more than one class active at once) and marks the whole capture run
 * {@value com.automation.core.coverage.CoverageMapBuilder#UNRELIABLE_MARKER} the first time it
 * happens, rather than ever emit output that looks trustworthy but isn't.
 * {@code AlterSuiteForCoverageMapListener} avoids this in the first place by forcing the suite to
 * {@code parallel="none"} whenever this listener is active — the detection here is a backstop in
 * case something else re-parallelizes it, not the primary defense.
 */
public class JacocoPerTestCoverageListener implements IClassListener, ISuiteListener {

    private static final Path OUTPUT_DIR = Path.of("target", "jacoco-per-test");
    private static final Object LOCK = new Object();
    private static final AtomicInteger ACTIVE_CLASS_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean UNRELIABLE = new AtomicBoolean(false);
    private static final AtomicBoolean MBEAN_UNAVAILABLE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean MBEAN_AVAILABLE_LOGGED = new AtomicBoolean(false);
    // DIAGNOSTIC: this listener was previously silent on success, which made
    // it impossible to tell "capture worked, nothing to report" apart from
    // "capture silently no-op'd" from CI console output alone — see the
    // coverage-map pipeline investigation this counter/summary was added
    // for. Kept intentionally lightweight (no behavior change on the happy
    // path besides these prints) so it's safe to leave in permanently.
    private static final AtomicInteger FILES_WRITTEN = new AtomicInteger(0);

    private volatile JacocoRuntimeMXBean runtime;

    @Override
    public void onBeforeClass(ITestClass testClass) {
        synchronized (LOCK) {
            int active = ACTIVE_CLASS_COUNT.incrementAndGet();
            if (active > 1) {
                markUnreliable("Detected " + active + " test classes executing concurrently "
                    + "(most recently entering: " + testClass.getName() + "). JaCoCo's runtime "
                    + "data is one shared accumulator per JVM, so per-class attribution from "
                    + "this run can't be trusted. Re-run with the suite forced to "
                    + "parallel=\"none\" (AlterSuiteForCoverageMapListener does this "
                    + "automatically when -Dcoverage.map.enabled=true) to get a usable map.");
            }
            if (UNRELIABLE.get()) {
                return;
            }
            JacocoRuntimeMXBean mbean = runtime();
            if (mbean == null) {
                return;
            }
            try {
                mbean.reset();
            } catch (RuntimeException e) {
                logMBeanIssue("reset() before " + testClass.getName(), e);
            }
        }
    }

    @Override
    public void onAfterClass(ITestClass testClass) {
        synchronized (LOCK) {
            try {
                if (!UNRELIABLE.get()) {
                    JacocoRuntimeMXBean mbean = runtime();
                    if (mbean != null) {
                        try {
                            byte[] data = mbean.getExecutionData(true);
                            writeExecFile(testClass.getName(), data);
                        } catch (RuntimeException | IOException e) {
                            logMBeanIssue("dumping after " + testClass.getName(), e);
                        }
                    }
                }
            } finally {
                ACTIVE_CLASS_COUNT.decrementAndGet();
            }
        }
    }

    private void writeExecFile(String testClassFqcn, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Path target = OUTPUT_DIR.resolve(testClassFqcn + ".exec");
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        int count = FILES_WRITTEN.incrementAndGet();
        System.out.println("[coverage-capture] Wrote " + target.toAbsolutePath()
            + " (" + data.length + " bytes) — " + count + " file(s) written so far this run.");
    }

    /**
     * Prints a final, unambiguous summary once the suite finishes — so a CI
     * log always shows definitively whether capture worked (non-zero count,
     * with the resolved absolute output directory) or not (zero, with the
     * reason: MBean never found, or every class hit the concurrent-suite
     * UNRELIABLE guard), instead of relying on the complete absence of any
     * per-class message to mean "everything was fine."
     */
    @Override
    public void onFinish(ISuite suite) {
        int count = FILES_WRITTEN.get();
        System.out.println("[coverage-capture] Suite \"" + suite.getName() + "\" finished — "
            + count + " per-class .exec file(s) written to " + OUTPUT_DIR.toAbsolutePath()
            + (UNRELIABLE.get() ? " (run marked UNRELIABLE — see reason above; no map will be built from these)"
                : count == 0 ? " (no files written — check for an earlier \"MBean not found\" message above; "
                    + "capture was likely never active for this run)"
                : ""));
    }

    @Override
    public void onStart(ISuite suite) {
        // No setup needed before the suite starts — reset()/dump() happen
        // per-class in onBeforeClass/onAfterClass. Required by ISuiteListener.
    }

    private void markUnreliable(String reason) {
        if (!UNRELIABLE.compareAndSet(false, true)) {
            return; // already marked — don't overwrite with a less useful/later reason
        }
        try {
            Files.createDirectories(OUTPUT_DIR);
            Files.writeString(OUTPUT_DIR.resolve("UNRELIABLE.marker"), reason + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[coverage-capture] Also failed writing the UNRELIABLE marker itself: " + e.getMessage());
        }
        System.err.println("[coverage-capture] " + reason);
    }

    private JacocoRuntimeMXBean runtime() {
        JacocoRuntimeMXBean current = runtime;
        if (current != null) {
            return current;
        }
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("org.jacoco:type=Runtime");
            if (!mbs.isRegistered(name)) {
                logMBeanUnavailableOnce();
                return null;
            }
            current = MBeanServerInvocationHandler.newProxyInstance(mbs, name, JacocoRuntimeMXBean.class, false);
            runtime = current;
            if (MBEAN_AVAILABLE_LOGGED.compareAndSet(false, true)) {
                System.out.println("[coverage-capture] org.jacoco:type=Runtime MBean found — "
                    + "per-test coverage capture is active for this run.");
            }
            return current;
        } catch (Exception e) {
            logMBeanUnavailableOnce();
            return null;
        }
    }

    private static void logMBeanUnavailableOnce() {
        if (MBEAN_UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
            System.out.println("[coverage-capture] org.jacoco:type=Runtime MBean not found — "
                + "per-test coverage capture is disabled for this run (this is expected unless "
                + "-Djacoco.jmx=true was passed; see Scripts/build-coverage-map.sh). Test "
                + "execution is unaffected.");
        }
    }

    private static void logMBeanIssue(String when, Exception e) {
        System.err.println("[coverage-capture] Problem " + when + ": " + e
            + " — continuing without coverage data for this class. Test execution is unaffected.");
    }

    /**
     * Local proxy for JaCoCo's {@code org.jacoco:type=Runtime} JMX MBean (registered by the
     * agent when started with {@code jmx=true}). Deliberately not a dependency on JaCoCo's own
     * agent/runtime artifact for one small interface — this is plain JMX reflection against a
     * well-documented, long-stable operation set (see JaCoCo's own
     * {@code org.jacoco.examples.MBeanClient}).
     */
    public interface JacocoRuntimeMXBean {
        String getVersion();

        String getSessionId();

        void setSessionId(String id);

        /** Returns the current execution data in JaCoCo's binary .exec format; if {@code reset}
         *  is true, the runtime's accumulator is cleared immediately after the snapshot is taken
         *  (so the returned data still reflects everything since the last reset, and the next
         *  call starts clean). */
        byte[] getExecutionData(boolean reset);

        void dump(boolean reset);

        void reset();
    }
}
