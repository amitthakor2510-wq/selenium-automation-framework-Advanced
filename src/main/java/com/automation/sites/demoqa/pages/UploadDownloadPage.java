package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.io.File;

public class UploadDownloadPage extends BasePage {

    // ── Navigation ─────────────────────────────────────────────────────────────
    private final By elementsCard      = By.xpath("//h5[text()='Elements']");
    private final By uploadDownloadMenu = By.xpath("//span[text()='Upload and Download']");

    // ── Download ───────────────────────────────────────────────────────────────
    private final By downloadButton    = By.id("downloadButton");

    // ── Upload ─────────────────────────────────────────────────────────────────
    private final By uploadInput       = By.id("uploadFile");
    private final By uploadedFilePath  = By.id("uploadedFilePath");

    public UploadDownloadPage(WebDriver driver) {
        super(driver);
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    public void navigateToUploadDownload() {
        navigateTo("/upload-download");
        wait.until(ExpectedConditions.visibilityOfElementLocated(downloadButton));
    }

    // ── Download ───────────────────────────────────────────────────────────────

    /**
     * Clicks the Download button.
     * Then waits up to 10 seconds for the file to appear
     * in the system downloads folder.
     * Returns true if file was downloaded successfully.
     */
    public boolean clickDownloadAndVerify(String downloadFolderPath, String expectedFileName) {
        HumanActions.click(driver, downloadButton);

        // Wait up to 10 seconds for file to appear in downloads folder
        File downloadedFile = new File(downloadFolderPath + File.separator + expectedFileName);

        int waitSeconds = 10;
        for (int i = 0; i < waitSeconds; i++) {
            if (downloadedFile.exists() && downloadedFile.length() > 0) {
                return true;
            }
            try {
                Thread.sleep(1000); // check every 1 second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    // ── Upload ─────────────────────────────────────────────────────────────────

    /**
     * Uploads a file by sending the full file path directly
     * to the hidden <input type="file"> element.
     * We do NOT use HumanActions.click() here because clicking
     * the input opens the OS file picker dialog which Selenium
     * cannot control.Instead, we use sendKeys() with the file
     * path &mdash; Selenium handles this specially for file inputs.
     */
    public void uploadFile(String filePath) {
        WebElement input = wait.until(
                ExpectedConditions.presenceOfElementLocated(uploadInput)
        );
        HumanActions.pause();
        input.sendKeys(filePath); // sends full path, no dialog opens
    }

    public String getUploadedFileName() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(uploadedFilePath)
        ).getText();
    }
}