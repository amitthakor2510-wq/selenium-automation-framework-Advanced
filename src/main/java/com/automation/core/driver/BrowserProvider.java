package com.automation.core.driver;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;

/**
 * One implementation per supported browser. DriverFactory looks these up
 * by name from a registry instead of branching on browser name internally
 * — adding browser #5 means writing a new implementation and adding one
 * registry line, not editing createDriver()/createRemoteDriver() (which
 * previously had a switch/case for every browser, in two places, that had
 * to be kept in sync).
 */
public interface BrowserProvider {

    /** Launches a real local browser process (the non-Grid path). */
    WebDriver createLocalDriver(boolean headless);

    /**
     * Builds the Capabilities/Options object used when opening a
     * RemoteWebDriver session against Selenium Grid instead of launching
     * locally. Deliberately separate from createLocalDriver — the local
     * and remote option sets are not identical for every browser (e.g.
     * local Firefox/Edge probe for a host-specific browser binary, which
     * makes no sense for a Grid node running in its own container).
     */
    Capabilities buildRemoteOptions(boolean headless);
}
