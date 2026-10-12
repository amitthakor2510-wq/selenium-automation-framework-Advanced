package com.automation.bdd.steps;

import com.automation.bdd.hooks.Hooks;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;

/** Base class for step definitions: gives every step class the current scenario's driver. */
public abstract class StepBase {

    protected WebDriver driver() {
        return Hooks.driver();
    }

    /** For mobile page objects (BaseMobilePage takes a RemoteWebDriver); only valid in @mobile scenarios. */
    protected RemoteWebDriver remoteDriver() {
        return (RemoteWebDriver) Hooks.driver();
    }
}
