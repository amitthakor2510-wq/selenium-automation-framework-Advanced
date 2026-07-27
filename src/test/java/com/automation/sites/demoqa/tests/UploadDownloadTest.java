package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.UploadDownloadPage;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class UploadDownloadTest extends BaseTest {

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
        downloadFolderPath = System.getProperty("user.dir")
            + "/target/downloads";
        File downloadDir = new File(downloadFolderPath);

        boolean dirCreated = downloadDir.mkdirs();
        if (!dirCreated && !downloadDir.exists()) {
            throw new RuntimeException(
                "Could not create download directory: " + downloadFolderPath
            );
        }

        System.out.println("[UploadDownloadTest] Upload path : " + uploadFilePath);
        System.out.println("[UploadDownloadTest] Download dir: " + downloadFolderPath);
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

    @Test(priority = 2,
        groups = {"regression"},
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
