package com.automation.bdd.steps;

import com.automation.core.verify.FailureHandling;
import com.automation.core.verify.Verify;
import com.automation.sites.saucedemo.pages.LoginPage;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * Step definitions for features/saucedemo/login.feature. Steps only drive page objects and call
 * Verify - locators and waits stay in LoginPage (see CONVENTIONS.md).
 */
public class SauceDemoLoginSteps extends StepBase {

    private LoginPage loginPage;

    @Given("the user is on the SauceDemo login page")
    public void theUserIsOnTheLoginPage() {
        loginPage = new LoginPage(driver());
        loginPage.navigateToLogin();
    }

    @When("the user logs in with username {string} and password {string}")
    public void theUserLogsIn(String username, String password) {
        loginPage.login(username, password);
    }

    @Then("the user lands on the inventory page")
    public void theUserLandsOnInventory() {
        Verify.verifyTrue(driver().getCurrentUrl().contains("inventory"),
            "redirected to inventory page", FailureHandling.STOP_ON_FAILURE);
    }

    @Then("an error message is shown")
    public void anErrorMessageIsShown() {
        Verify.verifyTrue(loginPage.isErrorDisplayed(),
            "login error message displayed", FailureHandling.STOP_ON_FAILURE);
    }
}
