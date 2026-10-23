package com.automation.sites.saucedemo.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.data.DataProviderFactory;
import com.automation.core.data.DataRow;
import com.automation.sites.core.BaseTest;
import com.automation.sites.saucedemo.pages.LoginPage;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Feature("Authentication")
@Story("Login - Data Driven")
public class LoginDataDrivenTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(LoginDataDrivenTest.class);

    // ── Data Providers ────────────────────────────────────────────────────────
    // Point to whichever file format you prefer — all produce the same result

    @DataProvider(name = "loginFromExcel")
    public Object[][] loginFromExcel() {
        return DataProviderFactory.fromFile("src/test/resources/testdata/login.xlsx");
    }

    @DataProvider(name = "loginFromCsv")
    public Object[][] loginFromCsv() {
        return DataProviderFactory.fromFile("src/test/resources/testdata/login.csv");
    }

    @DataProvider(name = "loginFromJson")
    public Object[][] loginFromJson() {
        return DataProviderFactory.fromFile("src/test/resources/testdata/login.json");
    }

    @DataProvider(name = "loginFromZip")
    public Object[][] loginFromZip() {
        return DataProviderFactory.fromFile("src/test/resources/testdata/login.zip");
    }

    @DataProvider(name = "loginFromYaml")
    public Object[][] loginFromYaml() {
        return DataProviderFactory.fromFile("src/test/resources/testdata/login.yaml");
    }

    // Ignores -Ddata.tags and the execute column — every row in the file,
    // including ones normally skipped. Handy for a one-off audit run.
    @DataProvider(name = "loginFromYamlUnfiltered")
    public Object[][] loginFromYamlUnfiltered() {
        return DataProviderFactory.fromFileUnfiltered("src/test/resources/testdata/login.yaml");
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test(
        dataProvider  = "loginFromExcel",
        groups        = {"regression", "data-driven"},
        description   = "SauceDemo - Login with data from Excel"
    )
    public void verifyLoginFromExcel(DataRow row) {
        runLoginTest(row);
    }

    @Test(
        dataProvider  = "loginFromCsv",
        groups        = {"regression", "data-driven"},
        description   = "SauceDemo - Login with data from CSV"
    )
    public void verifyLoginFromCsv(DataRow row) {
        runLoginTest(row);
    }

    @Test(
        dataProvider  = "loginFromJson",
        groups        = {"regression", "data-driven"},
        description   = "SauceDemo - Login with data from JSON"
    )
    public void verifyLoginFromJson(DataRow row) {
        runLoginTest(row);
    }

    @Test(
        dataProvider  = "loginFromZip",
        groups        = {"regression", "data-driven"},
        description   = "SauceDemo - Login with data from ZIP"
    )
    public void verifyLoginFromZip(DataRow row) {
        runLoginTest(row);
    }

    @Test(
        dataProvider  = "loginFromYaml",
        groups        = {"regression", "data-driven"},
        description   = "SauceDemo - Login with data from YAML (execute/tags filters applied)"
    )
    public void verifyLoginFromYaml(DataRow row) {
        runLoginTest(row);
    }

    @Test(
        dataProvider  = "loginFromYamlUnfiltered",
        groups        = {"regression", "data-driven", "data-audit"},
        description   = "SauceDemo - Login with every YAML row, including execute=no ones",
        enabled       = false // flip on for a one-off audit run; skipped rows are real known-issue data
    )
    public void auditAllLoginRows(DataRow row) {
        runLoginTest(row);
    }

    // ── Shared logic ──────────────────────────────────────────────────────────

    private void runLoginTest(DataRow row) {
        String username = row.getRequired("username");
        String password = row.getRequired("password");
        String expected = row.getRequired("expected");
        String notes    = row.get("notes"); // optional

        logger.info("  Row " + row.getRowIndex()
            + " | " + username + " | expected=" + expected
            + (notes.isEmpty() ? "" : " | " + notes));

        LoginPage page = new LoginPage(getDriver());
        page.navigateToLogin();
        page.login(username, password);

        if ("success".equalsIgnoreCase(expected)) {
            Assert.assertTrue(
                getDriver().getCurrentUrl().contains("inventory"),
                "Expected successful login for user: " + username
            );
        } else {
            Assert.assertTrue(
                page.isErrorDisplayed(),
                "Expected error message for user: " + username
            );
        }
    }
}
