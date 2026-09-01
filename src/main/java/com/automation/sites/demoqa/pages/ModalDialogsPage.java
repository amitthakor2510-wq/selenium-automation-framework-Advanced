package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.components.BootstrapModalComponent;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Migrated to compose {@link BootstrapModalComponent} instead of
 * hand-rolling open/title/body/close logic twice (once per modal size) —
 * see docs/architecture.md#-component-based-page-objects. Behavior is a
 * strict superset of the old implementation: both modals now also get the
 * hardened 3-attempt close (JS click, then Escape, then backdrop click)
 * that only {@code PracticeFormPage} used to have, instead of a single
 * plain JS click that had no fallback if DemoQA's overlay/coordinate
 * flakiness got in the way.
 */
public class ModalDialogsPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By alertsFrameCard  = By.xpath("//h5[text()='Alerts, Frame & Windows']");
    private final By modalDialogsMenu = By.xpath("//span[text()='Modal Dialogs']");

    // ── Buttons ────────────────────────────────────────────────────────────────
    private final By smallModalButton = By.id("showSmallModal");
    private final By largeModalButton = By.id("showLargeModal");

    // ── Modal components ──────────────────────────────────────────────────────
    private final BootstrapModalComponent smallModal;
    private final BootstrapModalComponent largeModal;

    public ModalDialogsPage(WebDriver driver) {
        super(driver);
        this.smallModal = new BootstrapModalComponent(driver, wait,
            By.id("example-modal-sizes-title-sm"), By.cssSelector(".modal-body"), By.id("closeSmallModal"));
        this.largeModal = new BootstrapModalComponent(driver, wait,
            By.id("example-modal-sizes-title-lg"), By.cssSelector(".modal-body"), By.id("closeLargeModal"));
    }

    public void navigateToModalDialogs() {
        navigateTo("/modal-dialogs");
        wait.until(ExpectedConditions.visibilityOfElementLocated(smallModalButton));
    }

    // ── Small modal ────────────────────────────────────────────────────────────

    public void openSmallModal() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("modal-backdrop")));
        HumanActions.click(driver, smallModalButton);
        smallModal.waitForOpen();
    }

    public String getSmallModalTitle() {
        return smallModal.getTitle();
    }

    public String getSmallModalBody() {
        return smallModal.getBody();
    }

    public void closeSmallModal() {
        smallModal.closeHardened();
    }

    // ── Large modal ────────────────────────────────────────────────────────────

    public void openLargeModal() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("modal-backdrop")));
        HumanActions.click(driver, largeModalButton);
        largeModal.waitForOpen();
    }

    public String getLargeModalTitle() {
        return largeModal.getTitle();
    }

    public String getLargeModalBody() {
        return largeModal.getBody();
    }

    public void closeLargeModal() {
        largeModal.closeHardened();
    }
}
