package com.automation.sites.demoqa.tests;

import com.automation.core.data.DataProviderFactory;
import com.automation.core.data.DataRow;
import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.RegistrationPage;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Book Store registration exercised with generated data (see
 * {@code core/data/synthetic/SyntheticDataGenerator}/{@code SyntheticDataProvider}) instead of a
 * hand-written data file — the "Synthetic/generated test data at scale" item from
 * {@code docs/roadmap.md}.
 *
 * <p>Deliberately tagged with group {@code "synthetic-data"} only — NOT {@code "regression"} or
 * {@code "smoke"} — same opt-in pattern as {@code AccessibilityTest}/{@code VisualRegressionTest}
 * (see those classes' own javadoc), for one reason specific to this test:
 * {@code RegistrationPage.isRegistrationSuccessful()}/{@code isBlockedByRecaptcha()} document
 * that DemoQA's real registration endpoint is ReCaptcha rate-limited — registering many new
 * accounts back-to-back on every CI run (on top of what {@code BookStoreApplicationTest} already
 * does) risks tripping that limit for everyone, not expanding coverage. Run explicitly with:
 *
 * <pre>  mvn test -DsuiteXmlFile=testng-suites/demoqa-synthetic-data.xml -Dsite=demoqa</pre>
 *
 * Row counts and the seed are controlled by {@code synthetic.data.count} /
 * {@code synthetic.data.seed} — see docs/configuration.md. A row that hits DemoQA's ReCaptcha
 * block is skipped (not failed), same as {@code BookStoreApplicationTest} — that's a real,
 * already-known site-side rate limit, not a defect this test is meant to catch.
 */
@Feature("Book Store - Synthetic Data")
public class RegistrationSyntheticDataTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationSyntheticDataTest.class);

    // ── Data Providers ────────────────────────────────────────────────────────

    /** Realistic-looking rows (Faker-generated names/emails/usernames/passwords). */
    @DataProvider(name = "syntheticRegistrations")
    public Object[][] syntheticRegistrations() {
        return DataProviderFactory.syntheticRegistrations();
    }

    /** One row per boundary/edge-case username value — see SyntheticDataProvider's javadoc. */
    @DataProvider(name = "syntheticRegistrationEdgeCases")
    public Object[][] syntheticRegistrationEdgeCases() {
        return DataProviderFactory.syntheticRegistrationEdgeCases();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test(
        dataProvider = "syntheticRegistrations",
        groups       = {"synthetic-data"},
        description  = "Book Store - Register with Faker-generated realistic data"
    )
    @Story("Realistic generated data")
    public void verifyRegistrationWithSyntheticData(DataRow row) {
        registerAndVerify(row, /* expectSuccess */ true);
    }

    @Test(
        dataProvider = "syntheticRegistrationEdgeCases",
        groups       = {"synthetic-data"},
        description  = "Book Store - Register with boundary/edge-case username values"
    )
    @Story("Edge-case / boundary generated data")
    public void verifyRegistrationRejectsOrHandlesEdgeCaseUsername(DataRow row) {
        // Every edge value here (empty, whitespace-only, 300-char, unicode, injection-shaped) is
        // expected to either be rejected by the form or, if DemoQA's client-side validation
        // accepts it, at minimum not crash the flow. This test asserts the second, weaker
        // guarantee only (no unhandled exception, a definite success/failure signal one way or
        // the other) — it deliberately does NOT assert every edge case is rejected, since that's
        // a claim about DemoQA's own validation rules this framework doesn't control and hasn't
        // verified for each value. Treat a row that surprises you (succeeds when you expected a
        // rejection, or vice versa) as a discovery to look into, not an automatic framework bug.
        registerAndVerify(row, /* expectSuccess */ null);
    }

    // ── Shared logic ──────────────────────────────────────────────────────────

    private void registerAndVerify(DataRow row, Boolean expectSuccess) {
        String firstName = row.getRequired("firstname");
        String lastName = row.getRequired("lastname");
        String username = row.get("username"); // may legitimately be "" or whitespace — an edge case
        String email = row.getRequired("email");
        String password = row.getRequired("password");
        String notes = row.get("notes");

        logger.info("  Row " + row.getRowIndex() + " | username='" + username + "'"
            + (notes.isEmpty() ? "" : " | " + notes));

        RegistrationPage page = new RegistrationPage(getDriver());
        page.navigateToRegistration();
        page.registerUser(firstName, lastName, username, email, password);

        if (page.isBlockedByRecaptcha()) {
            throw new SkipException(
                "Row " + row.getRowIndex() + " skipped — DemoQA's ReCaptcha rate-limit blocked "
                    + "registration (see RegistrationPage.isRegistrationSuccessful javadoc); "
                    + "not a defect in this framework or the generated data.");
        }

        boolean succeeded = page.isRegistrationSuccessful();
        if (expectSuccess != null) {
            Assert.assertEquals(succeeded, expectSuccess.booleanValue(),
                "Row " + row.getRowIndex() + " (username='" + username + "'): expected "
                    + (expectSuccess ? "success" : "failure") + " but got "
                    + (succeeded ? "success" : "failure"));
        } else {
            logger.info("  Row " + row.getRowIndex() + " (edge case) result: "
                + (succeeded ? "accepted" : "rejected"));
        }
    }
}
