package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.sites.demoqa.pages.BookStoreApplicationPage;
import com.automation.sites.demoqa.pages.ProfilePage;
import com.automation.sites.demoqa.pages.RegistrationPage;
import org.testng.Assert;
import org.testng.annotations.*;

import java.util.List;
import java.util.UUID;

/**
 * ============================================================
 * Book Store Application — Complete E2E Flow (UI + Profile)
 * ============================================================
 *
 * Flow (one shared browser session, sequential):
 *
 *  Test 1  → Register page loads
 *  Test 2  → Fill & submit registration form (auto-generated unique user)
 *  Test 3  → Back to Login from register page
 *  Test 4  → Invalid login → error message shown
 *  Test 5  → Valid login with the newly registered user
 *  Test 6  → Book Store loads and shows books
 *  Test 7  → Search "Git" filters the list
 *  Test 8  → Click first book → detail page opens
 *  Test 9  → Back to store
 *  Test 10 → Search + open specific book "Git Pocket Guide"
 *  Test 11 → Profile shows the logged-in user's name
 *  Test 12 → New user's book collection is empty
 *  Test 13 → Add a book to the collection (via /books → detail → "Add To Your Collection")
 *  Test 14 → Added book is listed on the profile page
 *  Test 15 → Delete book from profile removes it from the collection
 *  Test 16 → Logout → redirects to /login
 *
 * ONE BROWSER SESSION:
 *   @BeforeClass opens Chrome once and stores driver in BaseTest's ThreadLocal
 *   so TestListener can capture screenshots on failure.
 *   @AfterClass closes it after Test 16.
 *   Tests 6–16 reuse the logged-in session from Test 5.
 *
 *   setUp() and tearDown() from BaseTest are suppressed here
 *   because this test manages its own lifecycle via @BeforeClass/@AfterClass.
 *
 * NOTE ON PROFILE:
 *   The /profile page has NO "Add Book" button (confirmed via diagnostics —
 *   only one clickable element exists on that page, a homepage link). The
 *   actual flow to add a book is:
 *     1. Navigate to /books
 *     2. Click a book from the list
 *     3. Click "Add To Your Collection" on the book's detail page
 *     4. That action redirects back to /profile
 *   This class now owns that flow end-to-end (previously split out into a
 *   separate ProfileTest class using the same registered session).
 */
public class BookStoreApplicationTest extends BaseTest {

    // ── Auto-generated credentials (unique per run to avoid "username taken") ──
    private static final String UNIQUE_ID           = UUID.randomUUID().toString().substring(0, 8);
    private static final String REGISTERED_FNAME    = "Auto";
    private static final String REGISTERED_LNAME    = "Tester";
    private static final String REGISTERED_USERNAME = "AutoTest_" + UNIQUE_ID;
    private static final String REGISTERED_EMAIL    = "autotest_" + UNIQUE_ID + "@mailtest.com";
    private static final String REGISTERED_PASSWORD = "Password123!@";

    // Set by verifyAddBookToCollection, read by the two profile checks that follow it
    private static String addedBookTitle;

    private BookStoreApplicationPage bookStorePage;
    private RegistrationPage registrationPage;
    private ProfilePage profilePage;

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    @BeforeClass(alwaysRun = true)
    public void openBrowser() {
        ConfigReader.reset();
        // Store in BaseTest ThreadLocal so TestListener.onTestFailure can grab it
        driver.set(DriverFactory.createDriver());
        getDriver().get(ConfigReader.get("url"));

        bookStorePage    = new BookStoreApplicationPage(getDriver());
        registrationPage = new RegistrationPage(getDriver());
        profilePage      = new ProfilePage(getDriver());

        System.out.println("=== Book Store E2E Test Started ===");
        System.out.println("  Username : " + REGISTERED_USERNAME);
        System.out.println("  Email    : " + REGISTERED_EMAIL);
        System.out.println("  Password : " + REGISTERED_PASSWORD);
    }

    @AfterClass(alwaysRun = true)
    public void closeBrowser() {
        if (getDriver() != null) {
            try { getDriver().quit(); } catch (Exception ignored) {}
            driver.remove();
        }
        System.out.println("=== Book Store E2E Test Finished ===");
    }

    // Suppress per-method driver creation — this class owns the session
    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() { /* intentionally blank */ }

    @Override
    @AfterMethod(alwaysRun = true)
    public void tearDown() { /* intentionally blank */ }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 1 — Registration page loads
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 1,
            groups      = {"smoke", "regression"},
            description = "Book Store - Register page loads the form"
    )
    public void verifyRegisterPageLoads() {
        registrationPage.navigateToRegistration();

        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("register"),
                "Should be on /register, got: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 1 PASS — Register page loaded: " + getDriver().getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 2 — Fill and submit registration form
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 2,
            groups           = {"regression"},
            description      = "Book Store - Register new user with auto-generated unique credentials",
            dependsOnMethods = "verifyRegisterPageLoads"
    )
    public void registerNewUser() {
        registrationPage.registerUser(
                REGISTERED_FNAME,
                REGISTERED_LNAME,
                REGISTERED_USERNAME,
                REGISTERED_EMAIL,
                REGISTERED_PASSWORD
        );

        Assert.assertTrue(
                registrationPage.isRegistrationSuccessful(),
                "Registration did not succeed. URL: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 2 PASS — Registered user: " + REGISTERED_USERNAME);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 3 — Back to Login from registration page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 3,
            groups           = {"regression"},
            description      = "Book Store - Back to Login link navigates to /login",
            dependsOnMethods = "registerNewUser"
    )
    public void navigateBackToLogin() {
        // If registration auto-redirected to /login, we're already there
        if (!getDriver().getCurrentUrl().contains("login")) {
            registrationPage.clickBackToLogin();
        }

        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("login"),
                "Should be on /login, got: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 3 PASS — On login page: " + getDriver().getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 4 — Invalid login shows error
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 4,
            groups           = {"regression"},
            description      = "Book Store - Invalid credentials show error message",
            dependsOnMethods = "navigateBackToLogin"
    )
    public void verifyInvalidLogin() {
        bookStorePage.navigateToLogin();
        bookStorePage.login("invalidUser_" + UNIQUE_ID, "wrongPassword123");

        String errorMsg = bookStorePage.getLoginErrorMessage();
        System.out.println("  Login error message: " + errorMsg);

        Assert.assertFalse(
                bookStorePage.isLoggedIn(),
                "Should NOT be logged in with invalid credentials"
        );
        Assert.assertFalse(
                errorMsg.isEmpty(),
                "Expected an error message for invalid login but got none"
        );
        Assert.assertTrue(
                errorMsg.toLowerCase().contains("invalid") || errorMsg.toLowerCase().contains("incorrect"),
                "Unexpected error message text: " + errorMsg
        );
        System.out.println("✓ Test 4 PASS — Invalid login correctly rejected");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 5 — Valid login with registered user
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 5,
            groups           = {"smoke", "regression"},
            description      = "Book Store - Login with registered user succeeds",
            dependsOnMethods = "verifyInvalidLogin"
    )
    public void verifyValidLogin() {
        bookStorePage.navigateToLogin();
        bookStorePage.login(REGISTERED_USERNAME, REGISTERED_PASSWORD);

        Assert.assertTrue(
                bookStorePage.isLoggedIn(),
                "Login failed for user: " + REGISTERED_USERNAME
        );

        String loggedInUser = bookStorePage.getLoggedInUserName();
        System.out.println("✓ Test 5 PASS — Logged in as: " + loggedInUser);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 6 — Book Store loads and shows books
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 6,
            groups           = {"smoke", "regression"},
            description      = "Book Store - Store page loads and displays books",
            dependsOnMethods = "verifyValidLogin"
    )
    public void verifyBookStoreLoads() {
        bookStorePage.navigateToBookStore();

        int bookCount = bookStorePage.getVisibleBookCount();
        System.out.println("  Books visible: " + bookCount);

        Assert.assertTrue(
                bookCount > 0,
                "Expected books in store but found 0"
        );
        System.out.println("✓ Test 6 PASS — Store shows " + bookCount + " books");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 7 — Search filters book list
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 7,
            groups           = {"regression"},
            description      = "Book Store - Search 'Git' filters the book list",
            dependsOnMethods = "verifyBookStoreLoads"
    )
    public void verifySearchFiltersBooks() {
        bookStorePage.navigateToBookStore();
        bookStorePage.searchBook("Git");

        int filteredCount = bookStorePage.getVisibleBookCount();
        List<String> titles = bookStorePage.getBookTitles();

        System.out.println("  Books after search 'Git': " + filteredCount);
        System.out.println("  Titles: " + titles);

        Assert.assertTrue(
                filteredCount > 0,
                "Expected at least 1 book matching 'Git'"
        );
        Assert.assertTrue(
                titles.stream().anyMatch(t -> t.toLowerCase().contains("git")),
                "No book title contains 'git'. Titles: " + titles
        );
        System.out.println("✓ Test 7 PASS — Search filtered to " + filteredCount + " books");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 8 — Click first book opens detail page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 8,
            groups           = {"regression"},
            description      = "Book Store - Clicking first book opens detail page",
            dependsOnMethods = "verifySearchFiltersBooks"
    )
    public void verifyClickFirstBookOpensDetail() {
        bookStorePage.navigateToBookStore();
        bookStorePage.clickFirstBook();

        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("/books?search="),
                "Expected book detail URL, got: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 8 PASS — Detail page URL: " + getDriver().getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 9 — Back to store from detail page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 9,
            groups           = {"regression"},
            description      = "Book Store - Back to Book Store button returns to store",
            dependsOnMethods = "verifyClickFirstBookOpensDetail"
    )
    public void verifyBackToStoreNavigation() {
        bookStorePage.clickBackToBookStore();

        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("/books"),
                "Expected to return to /books, got: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 9 PASS — Back to store: " + getDriver().getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 10 — Search for specific book and open it
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 10,
            groups           = {"regression"},
            description      = "Book Store - Search 'Git Pocket Guide' and open it",
            dependsOnMethods = "verifyBackToStoreNavigation"
    )
    public void verifyOpenSpecificBook() {
        bookStorePage.navigateToBookStore();
        bookStorePage.searchBook("Git Pocket Guide");

        List<String> titles = bookStorePage.getBookTitles();
        System.out.println("  Books found: " + titles);

        Assert.assertFalse(
                titles.isEmpty(),
                "Expected 'Git Pocket Guide' in results but list is empty"
        );

        bookStorePage.clickBookByTitle("Git Pocket Guide");

        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("/books?search="),
                "Expected book detail page, got: " + getDriver().getCurrentUrl()
        );

        String isbn = bookStorePage.getBookDetailValue("ISBN");
        String author = bookStorePage.getBookDetailValue("Author");
        System.out.println("  ISBN: " + isbn + " | Author: " + author);

        Assert.assertFalse(isbn.isEmpty(), "ISBN should not be empty on detail page");
        System.out.println("✓ Test 10 PASS — Opened 'Git Pocket Guide', ISBN: " + isbn);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 11 — Profile shows the logged-in user's name
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 11,
            groups           = {"smoke", "regression"},
            description      = "Profile - Page shows the logged-in user's name",
            dependsOnMethods = "verifyOpenSpecificBook"
    )
    public void verifyProfileShowsUserName() {
        profilePage.navigateToProfile();
        String displayedName = profilePage.getProfileUserName();
        System.out.println("  Profile shows: " + displayedName);
        Assert.assertEquals(displayedName, REGISTERED_USERNAME,
                "Profile page did not show the expected username");
        System.out.println("✓ Test 11 PASS — Profile shows correct username");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 12 — New user's book collection is empty
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 12,
            groups           = {"regression"},
            description      = "Profile - New user's book collection is empty",
            dependsOnMethods = "verifyProfileShowsUserName"
    )
    public void verifyEmptyCollectionForNewUser() {
        profilePage.navigateToProfile();
        int bookCount = profilePage.getBookCount();
        System.out.println("  Books on profile: " + bookCount);
        Assert.assertEquals(bookCount, 0, "Brand-new user should have an empty collection");
        System.out.println("✓ Test 12 PASS — Collection empty for new user");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 13 — Add a book to the user's collection
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The /profile page has NO "Add Book" button (confirmed via diagnostics —
     * only one clickable element exists on that page, a homepage link). The
     * actual flow to add a book is:
     *   1. Navigate directly to /books
     *   2. Click a book from the list
     *   3. Click "Add To Your Collection" on the book's detail page
     *   4. That action redirects back to /profile
     */
    @Test(
            priority         = 13,
            groups           = {"smoke", "regression"},
            description      = "Profile - Add a book to the user's collection",
            dependsOnMethods = "verifyEmptyCollectionForNewUser"
    )
    public void verifyAddBookToCollection() {
        bookStorePage.navigateToBookStore();

        addedBookTitle = bookStorePage.getBookTitles().get(0);
        System.out.println("  Adding to collection: " + addedBookTitle);

        bookStorePage.clickFirstBook();
        bookStorePage.addBookToCollection();

        // CONFIRMED from a real run: accepting the "Book added to your
        // collection" alert does NOT redirect anywhere — the driver stays
        // on this same book detail page. Test 14 navigates to /profile
        // itself to verify the book actually landed in the collection.
        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("/books?search="),
                "Expected to remain on book detail page after adding, got: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 13 PASS — Book added to collection");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 14 — Added book is listed on the profile page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 14,
            groups           = {"regression"},
            description      = "Profile - Added book is listed on the profile page",
            dependsOnMethods = "verifyAddBookToCollection"
    )
    public void verifyAddedBookAppearsOnProfile() {
        profilePage.navigateToProfile();
        boolean listed = profilePage.waitForBookListed(addedBookTitle);
        int bookCount = profilePage.getBookCount();
        System.out.println("  Books on profile: " + bookCount + " | contains '" + addedBookTitle + "': " + listed);
        Assert.assertTrue(listed, "Added book not found on profile page: " + addedBookTitle);
        Assert.assertEquals(bookCount, 1, "Expected exactly 1 book in collection");
        System.out.println("✓ Test 14 PASS — Added book visible on profile");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 15 — Delete book from profile removes it
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 15,
            groups           = {"smoke", "regression"},
            description      = "Profile - Delete book from collection removes it",
            dependsOnMethods = "verifyAddedBookAppearsOnProfile"
    )
    public void verifyDeleteBookFromProfile() {
        profilePage.navigateToProfile();
        profilePage.deleteBookByTitle(addedBookTitle);
        int bookCount = profilePage.getBookCount();
        System.out.println("  Books on profile after delete: " + bookCount);
        Assert.assertFalse(
                profilePage.isBookListed(addedBookTitle),
                "Book still listed on profile after delete: " + addedBookTitle
        );
        Assert.assertEquals(bookCount, 0, "Expected empty collection after deleting the only book");
        System.out.println("✓ Test 15 PASS — Book deleted, collection empty");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEST 16 — Logout
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority         = 16,
            groups           = {"smoke", "regression"},
            description      = "Book Store - Logout redirects to /login",
            dependsOnMethods = "verifyDeleteBookFromProfile"
    )
    public void verifyLogout() {
        bookStorePage.navigateToProfile();
        bookStorePage.logout();

        Assert.assertTrue(
                getDriver().getCurrentUrl().contains("login"),
                "Expected redirect to /login after logout, got: " + getDriver().getCurrentUrl()
        );
        System.out.println("✓ Test 16 PASS — Logged out, on: " + getDriver().getCurrentUrl());
        System.out.println("=== All 16 Book Store tests completed ===");
    }
}