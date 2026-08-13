package com.automation.sites.core;

import com.automation.core.base.DriverProvider;
import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.core.report.AllureEnvironmentWriter;
import com.automation.core.report.ExtentManager;
import com.automation.core.utils.HumanActions;
import com.automation.sites.listeners.TestListener;
import org.openqa.selenium.WebDriver;
import org.slf4j.MDC;
import org.testng.ITestContext;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

import java.lang.reflect.Method;

/**
 * Every site's test classes extend this. Site is selected via
 * -Dsite=<siteName> (see ConfigReader); the driver navigates to
 * whatever "url" resolves to for that site's config file.
 *
 * NOTE: TestListener (a plain ITestListener) is registered here via
 * @Listeners rather than in testng-suites/*.xml <listeners>. Registering
 * it through the suite XML puts it in a different position relative to
 * Allure's auto-registered AllureTestNg listener, so by the time our
 * onTestFailure/onTestSuccess ran, Allure had already closed the test
 * case and Allure.addAttachment() silently dropped the screenshot
 * (Allure.getLifecycle().getCurrentTestCase() was already empty).
 * Declaring the listener via annotation instead fixes the ordering so
 * our attachment calls land while the test case is still open.
 *
 * RetryListener is deliberately NOT here, and must stay out. It's an
 * IAnnotationTransformer, not an ITestListener — TestNG needs to know
 * annotation transformers before it parses @Test annotations across the
 * suite (retry count, groups, enabled, etc.), which happens before
 * TestNG has even discovered this class's @Listeners annotation. Put
 * here, RetryListener.transform() silently never runs (no error, no
 * retries, no clue) — same class of ordering bug as the Allure
 * onStart() issue below, just for a different listener type. It's
 * registered instead in each testng-suites/*.xml's <listeners> block,
 * which is early enough for TestNG to actually pick it up.
 */
@Listeners({TestListener.class})
public class BaseTest implements DriverProvider {

    // Thread-safe driver for parallel execution
    protected static final ThreadLocal<WebDriver> driver = new ThreadLocal<>();

    @BeforeMethod(alwaysRun = true)
    public void setUp(Method testMethod, ITestContext context) {
        // Tags every SLF4J log line this thread emits from here on —
        // including DriverFactory.createDriver() below — with the test
        // that's about to run, via the same "ClassName#methodName" MDC key
        // TestListener.beforeInvocation() uses. TestNG injects the upcoming
        // @Test method into any @BeforeMethod that declares a
        // java.lang.reflect.Method parameter (its own dependency-injection
        // feature — see TestNG docs "5.19 Dependency injection"), which is
        // what makes this possible from inside @BeforeMethod at all: the
        // IInvokedMethodListener hook TestListener uses fires strictly
        // AFTER this method finishes, so relying on it alone left every
        // driver-creation log line untagged, empty "[]" MDC, during exactly
        // the window 2-3 parallel threads are most likely to be racing to
        // spin up a browser at once. TestListener's own MDC.put() moments
        // later just re-sets the identical value once the @Test body
        // itself starts — redundant, but harmless.
        MDC.put("test", testMethod.getDeclaringClass().getSimpleName() + "#" + testMethod.getName());

        // Writes environment.properties/categories.json into
        // target/allure-results once per JVM. This USED to be called from
        // TestListener.onStart(ITestContext) — but that's a <test>-level
        // callback, and TestListener is only registered via @Listeners on
        // this class, not via suite XML or ServiceLoader. TestNG fires
        // onStart() for a <test> before it discovers class-level
        // @Listeners annotations declared on test classes within that
        // <test>, so that call was silently never executing at all (no
        // exception, no warning — just dead code), which is why Allure's
        // "Environment"/"Categories" report widgets were always empty.
        // @BeforeMethod always runs regardless of listener timing, so call
        // it from here instead; writeOnce()'s internal guard keeps this
        // cheap even though it now runs before every test method.
        AllureEnvironmentWriter.writeOnce();

        // BUG FIX: same root cause as the AllureEnvironmentWriter fix just
        // above, for a completely different symptom. ExtentManager's own
        // javadoc says setActiveSuiteName() is "Set once via
        // TestListener.onStart(ITestContext)" — but per this method's
        // comment above, that <test>-level callback never actually fires
        // for a listener registered via class-level @Listeners. There was
        // no compile error and no runtime warning: setActiveSuiteName()
        // was simply never called, ever, so ExtentManager's activeSuiteName
        // field stayed null for the life of every JVM. That collapsed
        // suiteSlug() to the same literal fallback ("suite") regardless of
        // whether smoke/regression/accessibility/visual was actually
        // running, which in turn collapsed ExtentManager's per-suite
        // INSTANCES map key and reportPath down to one shared bucket per
        // site+browser. Concretely: run two different suite types
        // back-to-back against the same site+browser without `mvn clean`
        // in between (exactly the "Nightly Extra Coverage" pattern
        // ExtentManager's own class comment describes) and the second
        // suite's Extent report silently overwrote the first's at the
        // identical file path — every screenshot/pass/fail entry from the
        // first suite's run vanished from the report with no error, which
        // is indistinguishable from "screenshots don't show up for tests
        // that actually ran". Calling it here, from @BeforeMethod (which,
        // like AllureEnvironmentWriter.writeOnce() above, always runs
        // regardless of listener-discovery timing), actually wires the
        // suite name through before the first ExtentTest of the suite is
        // created.
        ExtentManager.setActiveSuiteName(context.getSuite().getName());

        // Only reset if the site actually changed (multi-site runs).
        // For single-site runs (the normal case) ConfigReader stays loaded.
        String currentSite = ConfigReader.getActiveSite();
        String requestedSite = System.getProperty("site", "demoqa");
        if (currentSite == null || !currentSite.equals(requestedSite)) {
            ConfigReader.reset();
        }
        WebDriver webDriver = DriverFactory.createDriver();
        driver.set(webDriver);
        getDriver().get(ConfigReader.get("url"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        // Outer finally clears the MDC "test" tag set in setUp()/
        // TestListener.beforeInvocation(). TestListener.afterInvocation()
        // deliberately does NOT clear it (see that method's comment) so
        // that driver.quit() below stays tagged instead of logging under
        // an empty "[]" MDC; clearing it here — the last thing this thread
        // does for this test, guaranteed via alwaysRun=true even if setUp()
        // itself failed — is what actually closes that gap.
        try {
            if (getDriver() != null) {
                // ROOT CAUSE FIX: quit() intermittently throws "Timed out
                // waiting for driver server to stop" under load (confirmed
                // by both the local and Jenkins regression runs) — that
                // exception used to just be logged and swallowed here, which
                // left the real chromedriver + browser process (and its own
                // renderer/GPU children) running for the rest of the JVM's
                // life. Enough of those pile up over a long run to exhaust
                // the box's memory/file descriptors, which is what was
                // actually causing the cascade of totally unrelated later
                // tests to fail with UnreachableBrowserException — not a
                // real app/test bug. DriverFactory.quitDriver() does the
                // identical quit() on the happy path, but forcibly kills the
                // leftover process tree on failure instead of just logging
                // it. See DriverFactory.CURRENT_DRIVER_SERVICE's comment for
                // the full history.
                try {
                    DriverFactory.quitDriver(getDriver());
                } finally {
                    driver.remove(); // Important for memory cleanup
                }
            }
        } finally {
            MDC.remove("test");
        }
    }

    public WebDriver getDriver() {
        return driver.get();
    }

    /**
     * Human pause between individual UI steps within a test. Prefer using
     * HumanActions.click()/type() from Page Objects instead of calling this
     * directly - those already pause automatically before each interaction.
     * This is here for the rare case a test needs a deliberate beat that
     * isn't tied to a single click/type (e.g. waiting for an animation to
     * settle before asserting).
     */
    protected void humanPause() {
        HumanActions.pause();
    }
}
