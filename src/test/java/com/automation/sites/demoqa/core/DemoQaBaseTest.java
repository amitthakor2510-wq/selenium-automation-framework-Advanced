package com.automation.sites.demoqa.core;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.api.DemoQaAccountApi;
import com.automation.sites.demoqa.api.DemoQaAccountApi.ApiUser;

/**
 * Base for demoqa UI tests that need an account (and possibly books) to already exist: creates
 * them over the REST API in milliseconds — before any browser is opened, if the subclass calls
 * these from an overridden {@code setUp} — and registers their deletion so the live DemoQA
 * database doesn't accumulate one leftover user per run.
 *
 * <p>Always talks to the site's REAL API ({@link DemoQaAccountApi#real()}), never the WireMock
 * server: the browser navigates to the real site, so that is where the account has to exist.
 */
public abstract class DemoQaBaseTest extends BaseTest {

    /** REST client for setup/teardown, pointed at the real site. Cheap to create, so not cached. */
    protected DemoQaAccountApi accountApi() {
        return DemoQaAccountApi.real();
    }

    /** Creates a fresh account via the API; it is deleted after the current test method. */
    protected ApiUser createApiUserForMethod() {
        DemoQaAccountApi api = accountApi();
        ApiUser user = api.createUniqueUser();
        cleanupAfterMethod("delete API-created account " + user.username(), () -> api.deleteUser(user));
        return user;
    }

    /** Creates a fresh account via the API; it is deleted once after the whole class. */
    protected ApiUser createApiUserForClass() {
        DemoQaAccountApi api = accountApi();
        ApiUser user = api.createUniqueUser();
        cleanupAfterClass("delete API-created account " + user.username(), () -> api.deleteUser(user));
        return user;
    }

    /**
     * For an account the UI itself registers (a test whose whole point is the registration form):
     * schedules its deletion after the class by credentials. Safe to call before the account
     * exists — if registration never happened or failed, cleanup finds nothing and does nothing.
     */
    protected void deleteAccountAfterClass(String username, String password) {
        DemoQaAccountApi api = accountApi();
        cleanupAfterClass("delete UI-registered account " + username,
            () -> api.deleteUserByCredentials(username, password));
    }
}
