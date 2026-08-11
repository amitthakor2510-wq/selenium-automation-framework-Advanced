package com.automation.sites.demoqa.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.driver.DriverFactory;
import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.UploadDownloadPage;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class UploadDownloadTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(UploadDownloadTest.class);

    // FIX #1: Was 'private static' — changed to instance fields so each
    // thread gets its own copy. Static + parallel execution = race condition.
    private String uploadFilePath;
    private String downloadFolderPath;

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        prepareFiles();
    }

    private void prepareFiles() {

        // ── Upload file ────────────────────────────────────────────────────
        try {
            File uploadFile = new File(
                System.getProperty("user.dir") + "/target/test-upload.txt"
            );

            boolean dirCreated = uploadFile.getParentFile().mkdirs();
            if (!dirCreated && !uploadFile.getParentFile().exists()) {
                throw new RuntimeException(
                    "Could not create directory: "
                        + uploadFile.getParentFile().getAbsolutePath()
                );
            }

            try (FileWriter writer = new FileWriter(uploadFile)) {
                writer.write("Test file created by Selenium automation");
            }

            uploadFilePath = uploadFile.getAbsolutePath();

        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to create upload file: " + e.getMessage(), e
            );
        }

        // ── Download folder ────────────────────────────────────────────────
        // SCALABILITY: must be the exact same per-thread-isolated path
        // DriverFactory configured THIS thread's browser to actually save
        // downloads into (see DriverFactory.getDownloadPath()'s javadoc) —
        // previously this recomputed "target/downloads" independently,
        // which happened to match by coincidence when nothing ran in
        // parallel, but would silently point at the wrong (shared, and now
        // no longer used by the browser) directory now that sessions each
        // get their own thread-<id> subfolder under parallel="classes".
        // Calling the same method DriverFactory itself uses guarantees this
        // can't drift out of sync with what the browser was actually told.
        downloadFolderPath = DriverFactory.getDownloadPath();

        logger.info("[UploadDownloadTest] Upload path : " + uploadFilePath);
        logger.info("[UploadDownloadTest] Download dir: " + downloadFolderPath);
    }

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Upload and Download - Verify File Upload")
    public void verifyFileUpload() {
        Assert.assertNotNull(uploadFilePath,
            "uploadFilePath is null - @BeforeMethod did not run");

        UploadDownloadPage page = new UploadDownloadPage(getDriver());
        page.navigateToUploadDownload();
        page.uploadFile(uploadFilePath);

        String displayedPath = page.getUploadedFileName();
        Assert.assertTrue(
            displayedPath.contains("test-upload.txt"),
            "Uploaded filename should appear on page. Got: " + displayedPath
        );
    }

    // "safari-unsupported": Safari has no download.default_directory-style
    // capability at all (see DriverFactory.createSafariDriver()'s javadoc) —
    // downloaded files always land in the signed-in user's real ~/Downloads,
    // not the per-thread isolated path DriverFactory.getDownloadPath()
    // configures for Chrome/Brave/Edge, so this specific assertion can never
    // pass under Safari regardless of app behavior. testng-suites/*-safari-*.xml
    // excludes this group; verifyFileUpload above has no such dependency and
    // still runs under Safari.
    @Test(priority = 2,
        groups = {"regression", "safari-unsupported"},
        description = "Upload and Download - Verify File Download")
    public void verifyFileDownload() {
        Assert.assertNotNull(downloadFolderPath,
            "downloadFolderPath is null - @BeforeMethod did not run");

        UploadDownloadPage page = new UploadDownloadPage(getDriver());
        page.navigateToUploadDownload();

        boolean downloaded = page.clickDownloadAndVerify(
            downloadFolderPath,
            "sampleFile.jpeg"
        );

        Assert.assertTrue(
            downloaded,
            "File should be downloaded to: " + downloadFolderPath
        );
    }
}
