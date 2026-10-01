package com.automation.mobile.sites.settings.pages;

import com.automation.mobile.core.BaseMobilePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;

/**
 * Screen object for the Android Settings app's home/search screen
 * (package com.android.settings, activity .Settings). Used as the "hello
 * world" example for this framework's Appium module because every Android
 * emulator/device already has it installed — no .apk or app-under-test
 * needed to try the mobile module end-to-end, matching how demoqa/saucedemo
 * let you try the web module with zero setup beyond a browser.
 *
 * Real target apps (e.g. a ministry Android app) get their own package
 * under com.automation.mobile.sites.<app>.pages the same way — pull real
 * resource-ids / accessibility labels via `appium inspector` or
 * `uiautomatorviewer` instead of the ones hardcoded below.
 */
public class SettingsHomePage extends BaseMobilePage {

    private static final By SEARCH_BOX =
        AppiumBy.id("com.android.settings:id/search_action_bar_title");
    private static final By NETWORK_INTERNET_ENTRY =
        AppiumBy.androidUIAutomator("new UiSelector().textContains(\"Network\")");

    public SettingsHomePage(RemoteWebDriver driver) {
        super(driver);
    }

    /** True once the Settings home list has rendered — the smoke assertion for this screen. */
    public boolean isSearchBarDisplayed() {
        return isDisplayed(SEARCH_BOX);
    }

    /** Opens the "Network & internet" row from the home list. */
    public void openNetworkAndInternet() {
        WebElement entry = waitClickable(NETWORK_INTERNET_ENTRY);
        entry.click();
    }
}
