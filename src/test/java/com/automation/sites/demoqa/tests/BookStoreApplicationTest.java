package com.automation.sites.demoqa.tests;

import com.automation.core.driver.DriverFactory;
import com.automation.sites.demoqa.pages.BookStoreApplicationPage;
import com.automation.sites.demoqa.pages.RegistrationPage;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;

/**
 * ============================================================
 * Book Store Application — Complete E2E Flow
 * ============================================================
 *
 * Flow (one browser session, sequential):
 *
 *  Test 1  → Register page loads
 *  Test 2  → Fill & submit registration form (auto-generated unique user)
 *  Test 3  → Back to Login from register page
 *  Test 4  → Invalid login → error message
 *  Test 5  → Valid login with the newly registered user
 *  Test 6  → Book Store loads & shows books
 *  Test 7  → Search "Git" filters list
 *  Test 8  → Click first book → detail page
 *  Test 9  → Back to store
 *  Test 10 → Search + open specific book "Git Pocket Guide"
 *  Test 11 → Logout → redirects to /login
 *
 * HOW CREDENTIALS WORK:
 *   UUID.randomUUID() generates a unique 8-char suffix each run.
 *   So REGISTERED_USERNAME = "AutoTest_a1b2c3d4" — unique every run.
 *   This avoids the "username already taken" error on demoqa.
 *   The SAME username/password are used for both registration (Test 2)
 *   and login (Test 5) — no hardcoded credentials needed.
 *
 * ONE BROWSER SESSION:
 *   @BeforeClass opens Chrome once.
 *   @AfterClass closes it after Test 11.
 *   Tests 6–11 reuse the logged-in cookie from Test 5.
 */
public class BookStoreApplicationTest {

    // ── Auto-generated credentials (unique per run) ──────────────────────────────
    private static final String UNIQUE_ID          = UUID.randomUUID().toString().substring(0, 8);
    private static final String REGISTERED_FNAME   = "Auto";
    private static final String REGISTERED_LNAME   = "Tester";
    private static final String REGISTERED_USERNAME = "AutoTest_" + UNIQUE_ID;
    private static final String REGISTERED_EMAIL   = "autotest_" + UNIQUE_ID + "@mailtest.com";
    private static final String REGISTERED_PASSWORD = "Password123!@";

    private WebDriver driver;
    private BookStoreApplicationPage bookStorePage;
    private RegistrationPage registrationPage;

    @BeforeClass(alwaysRun = true)
    public void openBrowser() {
        driver           = DriverFactory.createDriver();
        bookStorePage    = new BookStoreApplicationPage(driver);
        registrationPage = new RegistrationPage(driver);

        System.out.println("=== Book Store E2E Test Started ===");
        System.out.println("  Generated username : " + REGISTERED_USERNAME);
        System.out.println("  Generated email    : " + REGISTERED_EMAIL);
        System.out.println("  Password           : " + REGISTERED_PASSWORD);
    }

    @AfterClass(alwaysRun = true)
    public void closeBrowser() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
        }
        System.out.println("=== Book Store E2E Test Finished ===");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 1 — Registration page loads
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 1,
            groups      = {"smoke", "regression"},
            description = "Register page - Navigating to /register loads the form"
    )
    public void verifyRegisterPageLoads() {
        /*
         * What happens:
         * 1. driver.get("https://demoqa.com/register")
         * 2. wait for #firstname field to appear
         * 3. assert URL contains "register"
         */
        registrationPage.navigateToRegistration();

        Assert.assertTrue(driver.getCurrentUrl().contains("register"),
                "Should be on /register page, got: " + driver.getCurrentUrl());

        System.out.println("✓ Test 1 PASS — Register page: " + driver.getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 2 — Fill and submit registration form
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 2,
            groups      = {"smoke", "regression"},
            description = "Register - Fill form with auto-generated unique user and submit",
            dependsOnMethods = "verifyRegisterPageLoads"
    )
    public void registerNewUser() {
        /*
         * What happens:
         * 1. Already on /register from Test 1
         * 2. HumanActions.type → fills firstname (#firstname)
         * 3. HumanActions.type → fills lastname  (#lastname)
         * 4. HumanActions.type → fills userName  (#userName)  ← UNIQUE via UUID
         * 5. HumanActions.type → fills userEmail (#userEmail) ← UNIQUE via UUID
         * 6. HumanActions.type → fills password  (#password)
         * 7. js.click → #register button (JS click avoids sticky ad)
         * 8. demoqa shows browser alert → driver.switchTo().alert().accept()
         * 9. isRegistrationSuccessful() checks: URL redirected to /login
         *    OR a success element appeared
         *
         * WHY UUID?
         *   demoqa rejects duplicate usernames. UUID gives us a fresh user
         *   every run — no manual credential management needed.
         */
        System.out.println("--- STEP 2: Registration ---");

        registrationPage.registerUser(
                REGISTERED_FNAME,
                REGISTERED_LNAME,
                REGISTERED_USERNAME,
                REGISTERED_EMAIL,
                REGISTERED_PASSWORD
        );

        boolean success = registrationPage.isRegistrationSuccessful();
        System.out.println("  Registration successful: " + success);

        Assert.assertTrue(success,
                "Registration failed. Username tried: " + REGISTERED_USERNAME
                        + ". URL: " + driver.getCurrentUrl());

        System.out.println("✓ Test 2 PASS — Registered as: " + REGISTERED_USERNAME);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 3 — Back to Login from register page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 3,
            groups      = {"smoke", "regression"},
            description = "Register - Back to Login link navigates to /login",
            dependsOnMethods = "registerNewUser"
    )
    public void backToLoginFromRegister() {
        /*
         * What happens:
         * After registration, demoqa either:
         *   (a) redirects to /login automatically, OR
         *   (b) stays on /register with a "Back to Login" link
         *
         * clickBackToLogin() handles both cases:
         *   - If on /register: finds #gotologin link, JS-clicks it
         *   - If already on /login: skips click
         *   - Fallback: driver.get("/login") if link isn't found
         *
         * assert URL contains "/login"
         */
        System.out.println("--- STEP 3: Back to Login ---");

        // If already redirected to /login after registration, this is a no-op
        if (!driver.getCurrentUrl().contains("/login")) {
            registrationPage.clickBackToLogin();
        }

        Assert.assertTrue(driver.getCurrentUrl().contains("/login"),
                "Should be on /login, got: " + driver.getCurrentUrl());

        System.out.println("✓ Test 3 PASS — On login page: " + driver.getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 4 — Invalid login shows error
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 4,
            groups      = {"smoke", "regression"},
            description = "Login - Invalid credentials show error in #output",
            dependsOnMethods = "backToLoginFromRegister"
    )
    public void loginWithInvalidCredentials() {
        /*
         * What happens:
         * 1. navigateToLogin() → driver.get + wait for #userName field
         * 2. login("invalid_xyz", "Wrong@999") → type + JS-click #login
         * 3. wait: URL contains /profile (no) OR #output appears (yes)
         * 4. getLoginErrorMessage() → returns #output text
         * 5. assert not empty AND contains "invalid"
         */
        System.out.println("--- STEP 4: Invalid Login ---");

        bookStorePage.navigateToLogin();
        bookStorePage.login("invalid_xyz_9999", "Wrong@Pass999");

        String error = bookStorePage.getLoginErrorMessage();
        System.out.println("  Error message: " + error);

        Assert.assertFalse(error.isEmpty(),
                "Expected an error message for invalid login, got nothing");
        Assert.assertTrue(error.toLowerCase().contains("invalid"),
                "Error should say 'invalid', got: " + error);

        System.out.println("✓ Test 4 PASS — Error shown: " + error);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 5 — Valid login with the newly registered user
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 5,
            groups      = {"regression"},
            description = "Login - Registered user can log in successfully",
            dependsOnMethods = "loginWithInvalidCredentials"
    )
    public void loginWithValidCredentials() {
        /*
         * What happens:
         * 1. navigateToLogin() → fresh /login page
         * 2. login(REGISTERED_USERNAME, REGISTERED_PASSWORD)
         *    → HumanActions.type into #userName and #password
         *    → JS scrollIntoView + JS click on #login (avoids sticky ad)
         *    → waits: URL contains /profile OR #output appears
         * 3. isLoggedIn()
         *    → checks //span[contains(@id,'userName')] is visible
         * 4. getLoggedInUserName() → returns the span text
         * 5. assert name contains REGISTERED_USERNAME
         *
         * WHY the session stays for Tests 6-11:
         *   @BeforeClass opened ONE browser. The login cookie stays alive.
         *   Tests 6-11 navigate to /books and /profile within the same session.
         */
        System.out.println("--- STEP 5: Valid Login ---");

        bookStorePage.navigateToLogin();
        bookStorePage.login(REGISTERED_USERNAME, REGISTERED_PASSWORD);

        Assert.assertTrue(bookStorePage.isLoggedIn(),
                "Login failed for user: " + REGISTERED_USERNAME
                        + ". URL: " + driver.getCurrentUrl()
                        + ". Hint: registration may have failed in Test 2.");

        String displayedName = bookStorePage.getLoggedInUserName();
        System.out.println("  Logged in as: " + displayedName);

        Assert.assertTrue(displayedName.contains(REGISTERED_USERNAME),
                "Welcome label should show '" + REGISTERED_USERNAME
                        + "', got: " + displayedName);

        System.out.println("✓ Test 5 PASS — Logged in: " + displayedName);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 6 — Book Store lists books (uses logged-in session)
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 6,
            groups      = {"smoke", "regression"},
            description = "Book Store - Books are listed after login",
            dependsOnMethods = "loginWithValidCredentials"
    )
    public void verifyBooksAreListed() {
        /*
         * What happens:
         * 1. navigateToBookStore() → driver.get("https://demoqa.com/books")
         *    → waits for #searchBox (page skeleton)
         *    → waits for [role='table'] (React table wrapper)
         *    → Thread.sleep(2000) for React to fill rows
         * 2. getVisibleBookCount()
         *    → finds //div[@role='table']//a[@href]
         *    → filters non-empty getText() → count
         * 3. assert count > 0
         *
         * NOTE: books are only visible when logged in on demoqa.
         *       That is why Tests 6-11 depend on loginWithValidCredentials.
         */
        System.out.println("--- STEP 6: View Books ---");

        bookStorePage.navigateToBookStore();

        int count = bookStorePage.getVisibleBookCount();
        List<String> titles = bookStorePage.getBookTitles();
        System.out.println("  Books found: " + count + " → " + titles);

        Assert.assertTrue(count > 0,
                "Expected books in store but found: " + count
                        + ". Are you logged in? URL: " + driver.getCurrentUrl());

        System.out.println("✓ Test 6 PASS — " + count + " books visible");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 7 — Search filters books
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 7,
            groups      = {"regression"},
            description = "Book Store - Searching 'Git' filters the book list",
            dependsOnMethods = "verifyBooksAreListed"
    )
    public void searchBookByKeyword() {
        /*
         * What happens:
         * 1. Already on /books from Test 6
         * 2. getVisibleBookCount() → baseline (8 books total on demoqa)
         * 3. searchBook("Git")
         *    → box.clear() + box.sendKeys("Git")
         *    → Thread.sleep(800) for React client-side filter
         * 4. getVisibleBookCount() → filtered count
         * 5. assert filtered > 0 (found something)
         * 6. assert filtered < total (actually filtered — not still showing all)
         */
        System.out.println("--- STEP 7: Search Books ---");

        int total = bookStorePage.getVisibleBookCount();
        System.out.println("  Total books: " + total);

        bookStorePage.searchBook("Git");

        int filtered = bookStorePage.getVisibleBookCount();
        List<String> titles = bookStorePage.getBookTitles();
        System.out.println("  After search 'Git': " + filtered + " → " + titles);

        Assert.assertTrue(filtered > 0,
                "Search 'Git' returned 0 results. noData div appeared?");
        Assert.assertTrue(filtered < total,
                "Search did not filter — still showing all " + total);

        System.out.println("✓ Test 7 PASS — Filtered to: " + filtered + " books");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 8 — Click first book → detail page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 8,
            groups      = {"regression"},
            description = "Book Store - Clicking first book opens its detail page",
            dependsOnMethods = "searchBookByKeyword"
    )
    public void clickBookOpensDetailPage() {
        /*
         * What happens:
         * 1. navigateToBookStore() → fresh load (clears the "Git" search filter)
         * 2. clickFirstBook()
         *    → finds all //div[@role='table']//a[@href]
         *    → picks first with non-empty text
         *    → js.scrollIntoView(element)
         *    → js.click(element)
         *    → waits for URL to contain "/books?book=" (detail page URL)
         * 3. getBookDetailValue("Book Title :")
         *    → waits for //label[normalize-space(text())='Book Title :']/following-sibling::label[1]
         *    → returns its text
         * 4. assert title is not empty
         */
        System.out.println("--- STEP 8: Open First Book ---");

        bookStorePage.navigateToBookStore(); // fresh load = no search filter
        bookStorePage.clickFirstBook();

        String title = bookStorePage.getBookDetailValue("Book Title :");
        System.out.println("  Detail page title: " + title);

        Assert.assertFalse(title.isEmpty(),
                "Book detail title was blank — detail page may not have loaded");

        System.out.println("✓ Test 8 PASS — Book detail: " + title);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 9 — Back to store from detail page
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 9,
            groups      = {"regression"},
            description = "Book Detail - Back to store button returns to /books",
            dependsOnMethods = "clickBookOpensDetailPage"
    )
    public void backToStoreFromDetailPage() {
        /*
         * What happens:
         * 1. Still on book detail page (/books?book=...) from Test 8
         * 2. clickBackToBookStore()
         *    → waits for #addNewRecordButton to be clickable
         *    → js.scrollIntoView + js.click
         *    → waits for #searchBox to appear (confirms we're back at /books)
         * 3. assert URL contains "/books"
         */
        System.out.println("--- STEP 9: Back to Store ---");

        bookStorePage.clickBackToBookStore();

        Assert.assertTrue(driver.getCurrentUrl().contains("/books"),
                "Should be back at /books, got: " + driver.getCurrentUrl());

        System.out.println("✓ Test 9 PASS — Back at: " + driver.getCurrentUrl());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 10 — Search + open specific book by title
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 10,
            groups      = {"regression"},
            description = "Book Store - Search 'Git', open 'Git Pocket Guide' by title",
            dependsOnMethods = "backToStoreFromDetailPage"
    )
    public void searchAndOpenSpecificBook() {
        /*
         * What happens:
         * 1. Already on /books from Test 9
         * 2. searchBook("Git") → filters to Git books
         * 3. clickBookByTitle("Git Pocket Guide")
         *    → xpath: //div[@role='table']//a[contains(text(),'Git Pocket Guide')]
         *    → js.scrollIntoView + js.click
         *    → waits for URL to contain "/books?book="
         * 4. getBookDetailValue("Book Title :") → detail page title
         * 5. assert title contains "git" (case-insensitive)
         */
        System.out.println("--- STEP 10: Open Specific Book ---");

        bookStorePage.searchBook("Git");
        bookStorePage.clickBookByTitle("Git Pocket Guide");

        String title = bookStorePage.getBookDetailValue("Book Title :");
        System.out.println("  Specific book detail: " + title);

        Assert.assertTrue(title.toLowerCase().contains("git"),
                "Expected 'git' in title, got: " + title);

        System.out.println("✓ Test 10 PASS — Opened: " + title);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // STEP 11 — Logout
    // ════════════════════════════════════════════════════════════════════════════

    @Test(
            priority    = 11,
            groups      = {"regression"},
            description = "Profile - Logout redirects to /login",
            dependsOnMethods = "searchAndOpenSpecificBook"
    )
    public void logoutAfterLogin() {
        /*
         * What happens:
         * 1. navigateToProfile() → driver.get("https://demoqa.com/profile")
         *    → waits for //span[contains(@id,'userName')] to confirm session alive
         * 2. logout()
         *    → waits for #submit (the Log Out button on profile page)
         *    → js.scrollIntoView + js.click
         *    → waits for URL to contain "/login"
         * 3. assert URL contains "/login"
         *
         * WHY navigate to profile first:
         *   The Log Out button (id="submit") only exists on the /profile page.
         *   navigating there ensures the button is present before clicking.
         */
        System.out.println("--- STEP 11: Logout ---");

        bookStorePage.navigateToProfile();  
        bookStorePage.logout();

        String url = driver.getCurrentUrl();
        System.out.println("  After logout URL: " + url);

        Assert.assertTrue(url.contains("/login"),
                "Should be on /login after logout, got: " + url);

        System.out.println("✓ Test 11 PASS — Logged out, on: " + url);
        System.out.println("=== Full Flow Complete ===");
    }
}