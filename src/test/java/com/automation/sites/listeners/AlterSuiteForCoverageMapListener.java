package com.automation.sites.listeners;

import org.testng.IAlterSuiteListener;
import org.testng.xml.XmlSuite;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-registered for every TestNG run via
 * {@code src/test/resources/META-INF/services/org.testng.ITestNGListener} (TestNG's own
 * {@code ServiceLoader}-based listener discovery — no {@code <listeners>} entry needed in any
 * suite XML) but a complete no-op unless {@code -Dcoverage.map.enabled=true} is passed: this
 * checks that system property first and returns immediately if it isn't set, so an ordinary
 * {@code mvn test} run is completely unaffected by this class merely being on the classpath.
 *
 * <p>When it <i>is</i> enabled, this does two things to every {@link XmlSuite} before TestNG
 * runs it:
 * <ol>
 *   <li>Forces {@code parallel="none"} (and {@code thread-count=1}) — see
 *   {@link JacocoPerTestCoverageListener}'s javadoc for why per-test-class coverage capture is
 *   only meaningful when classes run one at a time, never concurrently.</li>
 *   <li>Adds {@link JacocoPerTestCoverageListener} to the suite's listener list, so the actual
 *   reset/dump work happens without needing every {@code testng-suites/*.xml} file edited by
 *   hand for a mode almost nobody runs.</li>
 * </ol>
 *
 * <p>See {@code Scripts/build-coverage-map.sh} for how this flag actually gets set, and
 * {@code TEST_IMPACT_ANALYSIS.md} → "Coverage-based fallback" for the end-to-end design.
 */
public class AlterSuiteForCoverageMapListener implements IAlterSuiteListener {

    static final String ENABLED_PROPERTY = "coverage.map.enabled";

    @Override
    public void alter(List<XmlSuite> suites) {
        if (!"true".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) {
            return;
        }
        String listenerClass = JacocoPerTestCoverageListener.class.getName();
        for (XmlSuite suite : suites) {
            suite.setParallel(XmlSuite.ParallelMode.NONE);
            suite.setThreadCount(1);
            if (!suite.getListeners().contains(listenerClass)) {
                List<String> listeners = new ArrayList<>(suite.getListeners());
                listeners.add(listenerClass);
                suite.setListeners(listeners);
            }
            System.out.println("[coverage-capture] " + ENABLED_PROPERTY + "=true — suite \""
                + suite.getName() + "\" forced to parallel=\"none\" and " + listenerClass + " attached.");
        }
    }
}
