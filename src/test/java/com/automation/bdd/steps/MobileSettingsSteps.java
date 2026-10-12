package com.automation.bdd.steps;

import com.automation.core.verify.FailureHandling;
import com.automation.core.verify.Verify;
import com.automation.mobile.sites.settings.pages.SettingsHomePage;
import io.appium.java_client.InteractsWithApps;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** Steps for features/mobile/settings.feature - drives the Android Settings app page object. */
public class MobileSettingsSteps extends StepBase {

    private static final String SETTINGS_PACKAGE = "com.android.settings";

    private SettingsHomePage settings;

    @Given("the Android Settings app is open")
    public void theSettingsAppIsOpen() {
        // Do not rely on mobile.properties having app.package/app.activity filled in: with
        // them empty the Appium session starts on the home launcher, not Settings. Bringing
        // the app to the foreground explicitly makes this step self-contained, and is a
        // harmless no-op when the session already launched it.
        ((InteractsWithApps) remoteDriver()).activateApp(SETTINGS_PACKAGE);
        settings = new SettingsHomePage(remoteDriver());
    }

    @Then("the Settings search bar is displayed")
    public void theSearchBarIsDisplayed() {
        Verify.verifyTrue(settings.isSearchBarDisplayed(),
            "Settings home search bar displayed", FailureHandling.STOP_ON_FAILURE);
    }

    @When("the user opens Network and internet")
    public void theUserOpensNetworkAndInternet() {
        settings.openNetworkAndInternet();
    }

    @Then("the Settings home screen is no longer shown")
    public void theHomeScreenIsGone() {
        Verify.verifyTrue(settings.isSearchBarGone(),
            "Settings home search bar is gone after opening Network and internet",
            FailureHandling.STOP_ON_FAILURE);
    }
}
