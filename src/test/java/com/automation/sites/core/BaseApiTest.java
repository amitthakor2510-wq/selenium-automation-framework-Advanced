package com.automation.sites.core;

import com.automation.core.api.ApiClient;
import com.automation.core.config.ConfigReader;
import org.testng.annotations.BeforeClass;

/**
 * Base for pure-HTTP API test classes (no browser). Mirrors BaseTest's
 * role for UI tests: one place that wires up ConfigReader + the client
 * (ApiClient here, DriverFactory there) so individual test classes don't
 * each duplicate setup.
 *
 * ConfigReader.reset() runs first for the same reason BaseTest/MobileBaseTest
 * call it — picks up whatever -Dsite was passed for *this* run rather than
 * a value cached from a previous test class in the same JVM.
 */
public abstract class BaseApiTest {

    @BeforeClass(alwaysRun = true)
    public void setUpApiClient() {
        ConfigReader.reset();
        ApiClient.configure();
    }
}
