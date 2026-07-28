package com.automation.mobile.sites.settings.tests;

import com.automation.mobile.core.MobileBaseTest;
import com.automation.mobile.sites.settings.pages.SettingsHomePage;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * First real Appium test for this framework — same Page Object + TestNG +
 * Allure/Extent shape as every web test, just against the built-in Android
 * Settings app so it runs on any emulator/device with zero app-under-test
 * setup. Run it with:
 *
 *   1. appium                                  (start the Appium server)
 *   2. cp src/test/resources/config/mobile.properties.example \
 *         src/test/resources/config/mobile.properties
 *      # edit mobile.device.name to match `adb devices`, leave
 *      # mobile.app.path empty, and set:
 *      #   mobile.app.package=com.android.settings
 *      #   mobile.app.activity=.Settings
 *   3. mvn test -Dsite=mobile -DsuiteXmlFile=testng-suites/mobile-smoke.xml
 *
 * To point this at your own app instead: copy this class + SettingsHomePage
 * under com.automation.mobile.sites.<yourapp>, swap the locators for real
 * ones pulled via `appium inspector`, and set mobile.app.path (or
 * mobile.app.package/mobile.app.activity) to your app in mobile.properties.
 */
@Feature("Mobile - Settings App")
@Story("Home Screen")
public class SettingsHomeTest extends MobileBaseTest {

    @Test(groups = {"smoke", "regression", "mobile"},
        description = "Settings app launches and shows the home screen search bar")
    public void settingsHomeScreenLoads() {
        SettingsHomePage page = new SettingsHomePage(getDriver());
        Assert.assertTrue(page.isSearchBarDisplayed(),
            "Settings home screen search bar should be visible after launch");
    }

    @Test(groups = {"regression", "mobile"},
        description = "Network & internet entry opens from the Settings home list")
    public void networkAndInternetEntryOpens() {
        SettingsHomePage page = new SettingsHomePage(getDriver());
        page.openNetworkAndInternet();
        // No deep assertion on the destination screen yet — this is a
        // navigation smoke check. Extend with a NetworkAndInternetPage
        // screen object once you need to assert something on that screen.
    }
}
