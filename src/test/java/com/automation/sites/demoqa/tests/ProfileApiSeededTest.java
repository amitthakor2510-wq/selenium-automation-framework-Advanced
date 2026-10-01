package com.automation.sites.demoqa.tests;

import com.automation.sites.demoqa.api.DemoQaAccountApi.ApiUser;
import com.automation.sites.demoqa.core.DemoQaBaseTest;
import com.automation.sites.demoqa.pages.BookStoreApplicationPage;
import com.automation.sites.demoqa.pages.ProfilePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;

/**
 * ============================================================
 * Profile page — API-seeded state (no UI registration)
 * ============================================================
 *
 * {@link BookStoreApplicationTest} walks the whole journey through the browser, including filling
 * in the registration form — which is slow, and is the step DemoQA guards with a rate-limited
 * reCAPTCHA. This class covers the Profile page without any of that: each test gets a brand-new
 * account (and, where the test needs one, a book in its collection) created over the REST API
 * BEFORE the browser is even launched, then only the behaviour under test is driven through the UI.
 * The account is deleted over the API after each test.
 *
 * Because every test seeds its own data, the tests are independent — unlike the 16-step chain in
 * BookStoreApplicationTest there is no dependsOnMethods ordering, and one failure doesn't cascade.
 *
 * Login still goes through the UI on purpose: it's the realistic way into the profile page, and
 * DemoQA's login is not the rate-limited step.
 *
 * The second half of the roadmap item this implements — "verify cleanup via API after" — is
 * deleteFromProfile_IsReflectedInApi, which checks the server's own record, not just what the
 * page shows.
 */
public class ProfileApiSeededTest extends DemoQaBaseTest {

    private static final Logger logger = LoggerFactory.getLogger(ProfileApiSeededTest.class);

    private ApiUser user;

    /**
     * Overrides BaseTest.setUp() only to put the API call FIRST: the account exists before a
     * browser is launched, so the setup cost is a REST call, not UI clicks.
     */
    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp(Method testMethod, ITestContext context) {
        user = createApiUserForMethod();
        super.setUp(testMethod, context);
    }

    @Test(groups = {"smoke", "regression"},
        description = "Profile (API-seeded) - shows the username of an account created via the API")
    public void profileShowsUsername() {
        loginAsSeededUser();

        ProfilePage profilePage = new ProfilePage(getDriver());
        profilePage.navigateToProfile();

        Assert.assertEquals(profilePage.getProfileUserName(), user.username(),
            "Profile page did not show the API-created username");
        logger.info("✓ Profile shows API-created user {}", user.username());
    }

    @Test(groups = {"regression"},
        description = "Profile (API-seeded) - a new account starts with an empty collection")
    public void newAccount_HasEmptyCollection() {
        loginAsSeededUser();

        ProfilePage profilePage = new ProfilePage(getDriver());
        profilePage.navigateToProfile();

        Assert.assertEquals(profilePage.getBookCount(), 0, "Brand-new account should have no books");
    }

    @Test(groups = {"regression"},
        description = "Profile (API-seeded) - a book added via the API is listed on the profile page")
    public void bookAddedViaApi_IsListedOnProfile() {
        String isbn = accountApi().catalogueIsbns().get(0);
        String title = accountApi().bookTitle(isbn);
        accountApi().addBook(user, isbn);

        loginAsSeededUser();
        ProfilePage profilePage = new ProfilePage(getDriver());
        profilePage.navigateToProfile();

        Assert.assertTrue(profilePage.waitForBookListed(title),
            "API-added book not found on the profile page: " + title);
        Assert.assertEquals(profilePage.getBookCount(), 1, "Expected exactly the one seeded book");
        logger.info("✓ Seeded book '{}' is listed on the profile", title);
    }

    @Test(groups = {"regression"},
        description = "Profile (API-seeded) - deleting a book in the UI removes it on the server too")
    public void deleteFromProfile_IsReflectedInApi() {
        String isbn = accountApi().catalogueIsbns().get(0);
        String title = accountApi().bookTitle(isbn);
        accountApi().addBook(user, isbn);

        loginAsSeededUser();
        ProfilePage profilePage = new ProfilePage(getDriver());
        profilePage.navigateToProfile();
        Assert.assertTrue(profilePage.waitForBookListed(title), "Seeded book should be listed before deleting");

        profilePage.deleteBookByTitle(title);

        Assert.assertFalse(profilePage.isBookListed(title), "Book still listed on the page after delete");
        // The page can look right while the server disagrees — ask the API, not just the DOM.
        Assert.assertTrue(accountApi().awaitBookRemoved(user, isbn, 3, 500),
            "UI removed the book but the API still lists it in the collection: "
                + accountApi().collectionIsbns(user));
        logger.info("✓ Book deleted in the UI is gone server-side too");
    }

    private void loginAsSeededUser() {
        BookStoreApplicationPage bookStorePage = new BookStoreApplicationPage(getDriver());
        bookStorePage.navigateToLogin();
        bookStorePage.login(user.username(), user.password());
        Assert.assertTrue(bookStorePage.isLoggedIn(), "UI login failed for API-created user " + user.username());
    }
}
