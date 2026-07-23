package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;
import java.util.NoSuchElementException;

import static java.util.stream.Collectors.toList;

/**
 * ============================================================
 * Profile Page — /profile
 * ============================================================
 * Shows the logged-in user's name and their book collection.
 *
 * IMPORTANT: The /profile page does NOT have an "Add Book" button.
 * Diagnostics confirmed only one clickable element exists on this
 * page (a link back to the homepage). To add a book, you must
 * navigate directly to /books, pick a book, and use the
 * "Add To Your Collection" button on that book's detail page —
 * which then redirects back to /profile.
 */
public class ProfilePage extends BasePage {

    // Same site-wide table redesign confirmed on /books and /webtables:
    // plain semantic <table>/<tr>/<td>, no more react-table ".rt-*" classes.
    // Applying the same confirmed pattern here; noRowsMessage kept as a
    // defensive no-op locator (no confirmed "no data" element seen yet).
    private final By userNameValue   = By.id("userName-value");
    private final By tableRows       = By.cssSelector("table tbody tr");
    private final By noRowsMessage   = By.cssSelector(".rt-noData");
    private final By deleteIconInRow = By.xpath(".//span[contains(@title,'Delete')]");

    public ProfilePage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    public void navigateToProfile() {
        navigateTo("/profile");
        wait.until(ExpectedConditions.visibilityOfElementLocated(userNameValue));
        HumanActions.pause();
    }

    // ── User info ───────────────────────────────────────────────────────────────

    public String getProfileUserName() {
        return waitVisible(userNameValue).getText().trim();
    }

    // ── Book collection ─────────────────────────────────────────────────────────

    /**
     * Number of books currently in the user's collection.
     * Returns 0 if the table shows "No rows found".
     */
    public int getBookCount() {
        try {
            if (!driver.findElements(noRowsMessage).isEmpty()) {
                return 0;
            }
        } catch (Exception ignored) {}

        try {
            return (int) driver.findElements(tableRows).stream()
                    .filter(r -> {
                        try {
                            String text = r.getText().trim();
                            return !text.isEmpty();
                        } catch (StaleElementReferenceException e) {
                            return false;
                        }
                    })
                    .count();
        } catch (Exception e) {
            System.out.println("[ProfilePage] Error getting book count: " + e.getMessage());
            return 0;
        }
    }

    /**
     * List of book titles/rows currently displayed in the collection.
     */
    public List<String> getBookTitles() {
        try {
            if (!driver.findElements(noRowsMessage).isEmpty()) {
                return List.of();
            }
        } catch (Exception ignored) {}

        try {
            return driver.findElements(tableRows).stream()
                    .map(r -> {
                        try {
                            return r.getText().trim();
                        } catch (StaleElementReferenceException e) {
                            return "";
                        }
                    })
                    .filter(t -> !t.isEmpty())
                    .collect(toList());
        } catch (Exception e) {
            System.out.println("[ProfilePage] Error getting book titles: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Check if a book with the given title is listed in the collection.
     */
    public boolean isBookListed(String title) {
        return getBookTitles().stream()
                .anyMatch(rowText -> rowText.toLowerCase().contains(title.toLowerCase()));
    }

    /**
     * Polls until the collection shows the given book (or times out).
     *
     * CONFIRMED from a real run: getBookCount()/isBookListed() are one-shot
     * reads, and navigateToProfile() only waits for the username label —
     * not the collection table. Right after an add, the table can still be
     * re-rendering, so a one-shot read straight after navigation can see
     * the pre-update state (0 books) even though the add succeeded. Callers
     * that just added/removed a book should use this instead of calling
     * isBookListed()/getBookCount() immediately.
     */
    public boolean waitForBookListed(String title) {
        try {
            return wait.until(d -> isBookListed(title));
        } catch (TimeoutException e) {
            dumpPageForDebugging("profile-book-not-listed-after-wait");
            return false;
        }
    }

    /**
     * Deletes a book from the collection by title.
     * Finds the row with matching title and clicks its delete icon.
     *
     * CONFIRMED from a real run: navigateToProfile() only waits for the
     * username label, not for the collection table — it's React, so rows
     * can still be rendering after that. A single findElements() snapshot
     * can race ahead of the table and see zero rows even though the book
     * is really there, so this polls (via wait.until) instead of taking
     * one snapshot.
     */
    public void deleteBookByTitle(String title) {
        System.out.println("[ProfilePage] Deleting book: " + title);

        WebElement row;
        try {
            row = wait.until(d -> d.findElements(tableRows).stream()
                    .filter(r -> {
                        try {
                            return r.getText().toLowerCase().contains(title.toLowerCase());
                        } catch (StaleElementReferenceException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null));
        } catch (TimeoutException e) {
            dumpPageForDebugging("profile-book-row-not-found");
            throw new NoSuchElementException(
                    "No row found matching book title: " + title);
        }

        WebElement deleteIcon = row.findElement(deleteIconInRow);
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", deleteIcon);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", deleteIcon);
        System.out.println("[ProfilePage] Clicked delete icon");

        // CONFIRMED from a screenshot: this is NOT a native browser alert —
        // it's a rendered in-page "Delete Book" modal with OK/Cancel
        // buttons. alertIsPresent() never fires for it, so the old
        // alert-based handling silently skipped past it while the modal
        // stayed open, leaving the delete unconfirmed and every later wait
        // blocked on a removal that could never happen. Click the modal's
        // OK button directly instead.
        try {
            By okButton = By.xpath("//button[normalize-space()='OK']");
            WebElement ok = wait.until(ExpectedConditions.elementToBeClickable(okButton));
            js.executeScript("arguments[0].click();", ok);
            System.out.println("[ProfilePage] Confirmed delete via OK button");
        } catch (TimeoutException e) {
            dumpPageForDebugging("profile-delete-modal-not-found");
            throw e;
        }

        // Wait for the book to be removed from the list
        wait.until(d -> !isBookListed(title));
        System.out.println("[ProfilePage] Book deleted successfully");
    }

    // Writes the full page source to target/debug-dumps so a failing,
    // never-before-verified locator can be fixed from real markup instead
    // of another guess. Same pattern used in CheckBoxPage / BookStoreApplicationPage.
    private void dumpPageForDebugging(String label) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("target", "debug-dumps");
            java.nio.file.Files.createDirectories(dir);
            String fileName = label.replaceAll("[^a-zA-Z0-9]", "")
                    + "-" + System.currentTimeMillis() + ".html";
            java.nio.file.Path file = dir.resolve(fileName);
            java.nio.file.Files.writeString(file, driver.getPageSource());
            System.out.println("  DEBUG full page source written to: " + file.toAbsolutePath());
        } catch (Exception writeEx) {
            System.out.println("  DEBUG could not write page source dump: " + writeEx.getMessage());
        }
        System.out.println("  DEBUG current URL: " + driver.getCurrentUrl());
    }
}