package com.automation.core.components;

import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Reusable Bootstrap-style modal dialog: open-wait, title/body reads, and a
 * hardened 3-attempt close — JS click on the close button, then Escape,
 * then a backdrop click, stopping at whichever works first — that survives
 * DemoQA's occasional overlay/coordinate flakiness. This is the same
 * defensive close sequence {@code PracticeFormPage#closeModal()} used to
 * hand-roll on its own; every page that composes this instance now gets it
 * for free, including {@code ModalDialogsPage}'s small/large modals, which
 * previously only did a single plain JS click.
 *
 * <p>Construct one instance per distinct modal a page can show (e.g.
 * {@code ModalDialogsPage} composes two — one for its small modal, one for
 * its large one) and delegate to it instead of hand-rolling the
 * open/read/close sequence again.
 *
 * <p><b>Not every Bootstrap-shaped modal on this site fits this
 * component.</b> {@code WebTablesPage}'s add/edit modal is a data-entry
 * <em>form</em> — it has no dedicated close button (it dismisses itself on
 * successful submit) and nothing that reads as a single "title"/"body" the
 * way a confirmation dialog does — so it's deliberately left composing its
 * own field locators rather than being forced into this shape. See
 * {@code docs/architecture.md#-component-based-page-objects} for the full
 * writeup of which pages do and don't use this and why.
 */
public class BootstrapModalComponent {

    private static final By MODAL_BACKDROP = By.className("modal-backdrop");

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final JavascriptExecutor js;
    private final By titleLocator;
    private final By bodyLocator;
    private final By closeButtonLocator;

    public BootstrapModalComponent(WebDriver driver, WebDriverWait wait, By titleLocator,
                                   By bodyLocator, By closeButtonLocator) {
        this.driver = driver;
        this.wait = wait;
        this.js = (JavascriptExecutor) driver;
        this.titleLocator = titleLocator;
        this.bodyLocator = bodyLocator;
        this.closeButtonLocator = closeButtonLocator;
    }

    /**
     * Waits for any leftover backdrop from a previously-closed modal to
     * clear (two Bootstrap modals on the same page share one backdrop
     * class — opening the next one before the last one's backdrop is
     * fully gone is a real source of flakiness on pages with more than
     * one modal, like ModalDialogsPage), then waits for this modal's
     * title to actually appear. Call this right after clicking whatever
     * button opens the modal.
     */
    public void waitForOpen() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(MODAL_BACKDROP));
        wait.until(ExpectedConditions.visibilityOfElementLocated(titleLocator));
        HumanActions.pause();
    }

    public boolean isDisplayed() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(titleLocator)).isDisplayed();
    }

    public String getTitle() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(titleLocator)).getText();
    }

    public String getBody() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(titleLocator));
        HumanActions.pause();
        return driver.findElement(bodyLocator).getText().trim();
    }

    /**
     * Closes the modal defensively: JS click the close button, then
     * Escape, then a backdrop click — stopping at whichever attempt
     * actually makes the title disappear — then a final, non-throwing
     * wait to confirm it's gone (and that the backdrop cleared too, so
     * the caller can safely open another modal right after this
     * returns).
     */
    public void closeHardened() {
        js.executeScript("window.scrollTo(0, 0)");
        HumanActions.pause();
        wait.until(ExpectedConditions.visibilityOfElementLocated(titleLocator));

        boolean closed = false;

        // Attempt 1 — JS click on the close button, bypassing coordinates/overlays.
        try {
            WebElement closeBtn = driver.findElement(closeButtonLocator);
            js.executeScript("arguments[0].click();", closeBtn);
            HumanActions.pause();
            closed = isGoneNow();
        } catch (Exception ignored) {
            // Close button click didn't work — fall through to the next attempt.
        }

        // Attempt 2 — Escape key.
        if (!closed) {
            try {
                driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE);
                HumanActions.pause();
                closed = isGoneNow();
            } catch (Exception ignored) {
                // Escape key didn't dismiss it — fall through to the next attempt.
            }
        }

        // Attempt 3 — click the backdrop itself.
        if (!closed) {
            try {
                js.executeScript("var b = document.querySelector('.modal-backdrop'); if (b) b.click();");
                HumanActions.pause();
            } catch (Exception ignored) {
                // Backdrop click didn't work either — the final waits below report the real state.
            }
        }

        // Final waits — just confirm things are gone; don't throw if they
        // already are (one of the attempts above may well have worked).
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(titleLocator));
        } catch (Exception ignored) {
            // Modal may have already closed via one of the attempts above.
        }
        try {
            wait.until(ExpectedConditions.invisibilityOfElementLocated(MODAL_BACKDROP));
        } catch (Exception ignored) {
            // Backdrop may already be gone, or this page never had one to begin with.
        }
        HumanActions.pause();
    }

    private boolean isGoneNow() {
        return driver.findElements(titleLocator).isEmpty()
            || !driver.findElement(titleLocator).isDisplayed();
    }
}
