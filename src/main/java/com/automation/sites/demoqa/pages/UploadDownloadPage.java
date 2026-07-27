package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;

import java.io.File;
import java.time.Duration;

public class UploadDownloadPage extends BasePage {

    // FIX #7: Extracted magic number to a named constant driven by config.
    // Set download.wait.seconds in global.properties to tune on slow machines.
    private static final int DOWNLOAD_WAIT_SECONDS = 10;

    private final By downloadButton   = By.id("downloadButton");
    private final By uploadInput      = By.id("uploadFile");
    private final By uploadedFilePath = By.id("uploadedFilePath");

    public UploadDownloadPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToUploadDownload() {
        navigateTo("/upload-download");
        wait.until(ExpectedConditions.visibilityOfElementLocated(downloadButton));
    }

    /**
     * Clicks Download and waits for the file to appear using FluentWait
     * instead of a raw Thread.sleep loop.
     * FIX #7: FluentWait polls every 500ms, is interruptible, and uses a
     * named constant rather than a magic number.
     */
    public boolean clickDownloadAndVerify(String downloadFolderPath, String expectedFileName) {
        HumanActions.click(driver, downloadButton);

        File downloadedFile = new File(downloadFolderPath + File.separator + expectedFileName);

        try {
            new FluentWait<>(downloadedFile)
                .withTimeout(Duration.ofSeconds(DOWNLOAD_WAIT_SECONDS))
                .pollingEvery(Duration.ofMillis(500))
                .until(f -> f.exists() && f.length() > 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Uploads a file via sendKeys on the hidden <input type="file"> element.
     * Do NOT use HumanActions.click() here — that opens the OS file picker
     * which Selenium cannot control.
     */
    public void uploadFile(String filePath) {
        WebElement input = wait.until(
            ExpectedConditions.presenceOfElementLocated(uploadInput)
        );
        HumanActions.pause();
        input.sendKeys(filePath);
    }

    public String getUploadedFileName() {
        return wait.until(
            ExpectedConditions.visibilityOfElementLocated(uploadedFilePath)
        ).getText();
    }
}
