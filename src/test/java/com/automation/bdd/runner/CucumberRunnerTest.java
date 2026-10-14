package com.automation.bdd.runner;

import com.automation.core.report.ExtentManager;
import com.automation.sites.listeners.TestListener;
import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.ITestContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;

/**
 * Entry point for every Gherkin feature of every site. TestNG discovers this class like any
 * other test class, so BDD runs through the same Surefire/suite-XML/Allure path as the rest of
 * the framework.
 *
 * <p><b>Site filtering.</b> All features live under one folder, so the runner only runs the
 * scenarios tagged with the active site: {@code -Dsite=demoqa} runs {@code @demoqa} scenarios.
 * (Without this, a SauceDemo scenario would be run against whatever site is active.) Every
 * feature must therefore carry its site's exact name as a tag - same spelling as
 * {@code config/<site>.properties}, e.g. {@code @saucedemo}, {@code @SAHMAT}.
 *
 * <p>Narrow further with {@code -Dcucumber.filter.tags="@smoke"}; it is AND-ed with the site
 * tag, never replaces it.
 *
 * <p>How many scenarios run at once is the suite XML's {@code data-provider-thread-count}
 * (1 = sequential).
 */
@CucumberOptions(
    features = "src/test/resources/features",
    glue = {"com.automation.bdd"},
    plugin = {
        "pretty",
        "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm",
        "json:target/cucumber-reports/cucumber.json"
    }
)
@Listeners({TestListener.class})   // same listener BaseTest/BaseApiTest attach: produces the Extent HTML report
public class CucumberRunnerTest extends AbstractTestNGCucumberTests {

    static {
        // Runs before Cucumber reads its options (that happens in the inherited @BeforeClass).
        String site = System.getProperty("site", "saucedemo");
        String user = System.getProperty("cucumber.filter.tags", "").trim();
        String expr = "@" + site + (user.isEmpty() ? "" : " and (" + user + ")");
        System.setProperty("cucumber.filter.tags", expr);
    }

    /** Names the Extent report folder after the suite (as BaseTest/BaseApiTest do), not the generic "suite". */
    @BeforeMethod(alwaysRun = true)
    public void registerSuiteNameForReports(ITestContext context) {
        ExtentManager.setActiveSuiteName(context.getSuite().getName());
    }

    @Override
    @DataProvider(parallel = true)
    public Object[][] scenarios() {
        // parallel=true lets the suite XML's data-provider-thread-count take effect.
        return super.scenarios();
    }
}
