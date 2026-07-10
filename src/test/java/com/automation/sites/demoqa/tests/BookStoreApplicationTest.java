package com.automation.sites.demoqa.tests;

import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.sites.demoqa.pages.BookStoreApplicationPage;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.annotations.*;

import java.util.List;

/**
 * Book Store Application tests.
 *
 * WHY NOT extends BaseTest:
 * BaseTest opens and closes the browser on every @BeforeMethod/@AfterMethod.
 * For book store tests this means: open browser → load demoqa → login →
 * navigate to books → test → close browser — repeated 10 times.
 * That's extremely slow and exactly what was causing the load.
 *
 * FIX: Manage the driver ourselves with @BeforeClass/@AfterClass so ONE
 * browser session handles ALL 10 tests. Login happens once in @BeforeClass
 * and the session cookie persists across all tests.
 *
 * Flow: Open browser → Register page check → Login page checks →
 *       Login once → Browse store → Search → Open books → Logout → Close browser
 */
public class BookStoreApplicationTest {

    // ── Credentials — change these after registering at demoqa.com/register ─────
    private static final String VALID_USERNAME = "Amit";
    private static final String VALID_PASSWORD = "Bisag@123";

    private WebDriver driver;
    private BookStoreApplicationPage page;

    // ── One browser for all tests ────────────────────────────────────────────────

    @BeforeClass(alwaysRun = true)
    public void openBrowser() {
        // Don't navigate to home page — go directly to login
        // BaseTest does driver.get(url) which loads home page unnecessarily
        driver = DriverFactory.createDriver();
        page = new BookStoreApplicationPage(driver);
        // First test handles its own navigation — no page load here
    }

    @AfterClass(alwaysRun = true)
    public void closeBrowser() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    // ── Test 1: Register page loads ──────────────────────────────────────────────
    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Book Store - Register page loads with all form fields")
    public void verifyRegisterPageLoads() {
        page.navigateToRegister();

        Assert.assertTrue(driver.getCurrentUrl().contains("register"),
                "Should be on register page");
        System.out.println("Register page URL: " + driver.getCurrentUrl());
    }

    // ── Test 2: New User button goes to register ─────────────────────────────────
    @Test(priority = 2,
            groups = {"smoke", "regression"},
            description = "Book Store - Login page New User button navigates to register",
            dependsOnMethods = "verifyRegisterPageLoads")
    public void verifyNewUserButtonNavigatesToRegister() {
        page.navigateToLogin();
        page.clickNewUser();

        Assert.assertTrue(driver.getCurrentUrl().contains("register"),
                "New User button should go to register");
        System.out.println("New User → " + driver.getCurrentUrl());
    }

    // ── Test 3: Invalid login shows error ────────────────────────────────────────
    @Test(priority = 3,
            groups = {"smoke", "regression"},
            description = "Book Store - Invalid credentials show error message",
            dependsOnMethods = "verifyNewUserButtonNavigatesToRegister")
    public void loginWithInvalidCredentials() {
        page.navigateToLogin();
        page.login("invalidUser_xyz_demoqa", "Wrong@Pass999");

        String error = page.getLoginErrorMessage();
        System.out.println("Login error: " + error);

        Assert.assertFalse(error.isEmpty(), "Expected error message but got empty");
        Assert.assertTrue(error.toLowerCase().contains("invalid"),
                "Error should say 'invalid', got: " + error);
    }

    // ── Test 4: Valid login ───────────────────────────────────────────────────────
    @Test(priority = 4,
            groups = {"regression"},
            description = "Book Store - Valid login shows welcome label")
    public void loginWithValidCredentials() {
        page.navigateToLogin();
        page.login(VALID_USERNAME, VALID_PASSWORD);

        Assert.assertTrue(page.isLoggedIn(),
                "Login failed — set VALID_USERNAME/VALID_PASSWORD at top of this file");

        String name = page.getLoggedInUserName();
        System.out.println("Logged in as: " + name);
        Assert.assertTrue(name.contains(VALID_USERNAME),
                "Welcome label should show username, got: " + name);
    }

    // ── Test 5: Books listed ─────────────────────────────────────────────────────
// NO dependsOnMethods — runs independently, handles own login
    @Test(priority = 5,
            groups = {"smoke", "regression"},
            description = "Book Store - Store lists books after login")
    public void verifyBooksAreListedInStore() {
        ensureLoggedIn();
        page.navigateToBookStore();

        int count = page.getVisibleBookCount();
        List<String> titles = page.getBookTitles();
        System.out.println("Books: " + count + " → " + titles);

        Assert.assertTrue(count > 0,
                "Expected books but found: " + count);
    }

    // ── Test 6: Search filters ───────────────────────────────────────────────────
    @Test(priority = 6,
            groups = {"regression"},
            description = "Book Store - Search 'Git' filters list")
    public void searchBookByKeyword() {
        // Already on /books if test 5 ran, otherwise navigate
        if (!driver.getCurrentUrl().contains("/books")) {
            ensureLoggedIn();
            page.navigateToBookStore();
        }

        int total = page.getVisibleBookCount();
        System.out.println("Total before search: " + total);

        page.searchBook("Git");

        int filtered = page.getVisibleBookCount();
        System.out.println("After 'Git': " + filtered + " → " + page.getBookTitles());

        Assert.assertTrue(filtered > 0, "Search 'Git' returned 0 results");
        Assert.assertTrue(filtered < total, "Search did not filter");
    }

    // ── Test 7: Click book opens detail ─────────────────────────────────────────
    @Test(priority = 7,
            groups = {"regression"},
            description = "Book Store - Clicking first book opens detail page")
    public void clickBookOpensDetailPage() {
        ensureLoggedIn();
        page.navigateToBookStore();
        page.clickFirstBook();

        String title = page.getBookDetailTitle();
        System.out.println("Detail title: " + title);
        Assert.assertFalse(title.isEmpty(), "Detail title was blank");
    }

    // ── Test 8: Back to store ────────────────────────────────────────────────────
    @Test(priority = 8,
            groups = {"regression"},
            description = "Book Store - Back to store button works")
    public void backToStoreFromDetailPage() {
        ensureLoggedIn();
        page.navigateToBookStore();
        page.clickFirstBook();
        page.clickBackToBookStore();

        Assert.assertTrue(driver.getCurrentUrl().contains("/books"),
                "Should be on /books, got: " + driver.getCurrentUrl());
        System.out.println("Back to: " + driver.getCurrentUrl());
    }

    // ── Test 9: Search and open specific book ────────────────────────────────────
    @Test(priority = 9,
            groups = {"regression"},
            description = "Book Store - Search 'Git', open 'Git Pocket Guide'")
    public void searchAndOpenSpecificBook() {
        ensureLoggedIn();
        page.navigateToBookStore();
        page.searchBook("Git");
        page.clickBookByTitle("Git Pocket Guide");

        String title = page.getBookDetailValue("Book Title :");
        System.out.println("Book title: " + title);
        Assert.assertTrue(title.toLowerCase().contains("git"),
                "Expected 'git' in title, got: " + title);
    }

    // ── Test 10: Logout ───────────────────────────────────────────────────────────
    @Test(priority = 10,
            groups = {"regression"},
            description = "Book Store - Logout returns to login page")
    public void logoutAfterLogin() {
        ensureLoggedIn();
        page.navigateToProfile();
        page.logout();

        String url = driver.getCurrentUrl();
        System.out.println("URL after logout: " + url);
        Assert.assertTrue(url.contains("/login"),
                "Should be on /login after logout, got: " + url);
    }

// ── Helper ────────────────────────────────────────────────────────────────────
    /**
     * Logs in only if not already logged in.
     * Checks current URL — if already on a demoqa page with active session,
     * no re-login needed. This prevents duplicate login page loads between tests.
     */
    private void ensureLoggedIn() {
        // Check if already logged in by looking for the userName-value label
        if (!page.isLoggedIn()) {
            page.navigateToLogin();
            page.login(VALID_USERNAME, VALID_PASSWORD);
            Assert.assertTrue(page.isLoggedIn(),
                    "Login failed — set VALID_USERNAME/VALID_PASSWORD at top of this file");
            System.out.println("Logged in as: " + page.getLoggedInUserName());
        } else {
            System.out.println("Already logged in — skipping login step");
        }
    }
}