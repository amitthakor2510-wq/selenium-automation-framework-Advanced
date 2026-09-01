package com.automation.sites.listeners;

import org.testng.IAlterSuiteListener;
import org.testng.xml.XmlSuite;

import java.util.List;

/**
 * Auto-registered for every TestNG run via
 * {@code src/test/resources/META-INF/services/org.testng.ITestNGListener} (TestNG's own
 * {@code ServiceLoader}-based listener discovery — no {@code <listeners>} entry needed in any
 * suite XML) but a complete no-op unless {@code -Dcoverage.map.enabled=true} is passed: this
 * checks that system property first and returns immediately if it isn't set, so an ordinary
 * {@code mvn test} run is completely unaffected by this class merely being on the classpath.
 *
 * <p>When it <i>is</i> enabled, this forces {@code parallel="none"} (and
 * {@code thread-count=1}) on every {@link XmlSuite} before TestNG runs it — see
 * {@link JacocoPerTestCoverageListener}'s javadoc for why per-test-class coverage capture is
 * only meaningful when classes run one at a time, never concurrently.
 *
 * <p><b>Does NOT attach {@link JacocoPerTestCoverageListener} itself</b> (an earlier version of
 * this class did, by mutating {@code suite.getListeners()} here) — that listener is now
 * ServiceLoader-registered directly (see {@code META-INF/services/org.testng.ITestNGListener}),
 * because attaching an {@code IClassListener}/{@code ISuiteListener} by adding its class name to
 * an already-in-flight {@link XmlSuite} from inside {@link IAlterSuiteListener#alter} did not
 * reliably wire it up in practice — a real coverage-map run showed this listener's own "attached"
 * log line printing (confirming {@code alter()} itself ran) but none of
 * {@link JacocoPerTestCoverageListener}'s onStart/onBeforeClass/onAfterClass/onFinish diagnostics
 * ever firing, which is why {@code Scripts/build-coverage-map.sh} kept finding
 * {@code target/jacoco-per-test} empty. ServiceLoader registration is the same
 * proven-working mechanism this class itself uses, and it's safe unconditionally since
 * {@link JacocoPerTestCoverageListener} already no-ops completely unless the
 * {@code org.jacoco:type=Runtime} JMX MBean is actually present.
 *
 * <p>See {@code Scripts/build-coverage-map.sh} for how this flag actually gets set, and
 * {@code docs/TEST_IMPACT_ANALYSIS.md} → "Coverage-based fallback" for the end-to-end design.
 */
public class AlterSuiteForCoverageMapListener implements IAlterSuiteListener {

    static final String ENABLED_PROPERTY = "coverage.map.enabled";

    @Override
    public void alter(List<XmlSuite> suites) {
        if (!"true".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) {
            return;
        }
        for (XmlSuite suite : suites) {
            suite.setParallel(XmlSuite.ParallelMode.NONE);
            suite.setThreadCount(1);
            System.out.println("[coverage-capture] " + ENABLED_PROPERTY + "=true — suite \""
                + suite.getName() + "\" forced to parallel=\"none\" ("
                + JacocoPerTestCoverageListener.class.getName()
                + " is ServiceLoader-registered and active whenever the JaCoCo JMX MBean is present).");
        }
    }
}
