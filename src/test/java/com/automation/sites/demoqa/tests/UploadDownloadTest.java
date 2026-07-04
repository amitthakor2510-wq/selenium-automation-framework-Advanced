package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.UploadDownloadPage;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class UploadDownloadTest extends BaseTest {

    private String uploadFilePath;
    private String downloadFolderPath;

    @BeforeClass
    public void prepareFiles() throws IOException {
        // ── Upload file ────────────────────────────────────────────────────────
        // Create a small dummy text file to upload
        // so the test is self-contained and needs no manual file preparation
        File uploadFile = new File("target/test-upload.txt");
        boolean dirCreated = uploadFile.getParentFile().mkdirs();
        if (!dirCreated && !uploadFile.getParentFile().exists()) {
            throw new RuntimeException("Could not create directory: "
                    + uploadFile.getParentFile().getAbsolutePath());
        }

        try (FileWriter writer = new FileWriter(uploadFile)) {
            writer.write("This is a test file for upload.");
        }

        uploadFilePath = uploadFile.getAbsolutePath();

        // ── Download folder ────────────────────────────────────────────────────
    }

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Upload and Download - Verify File Upload")
    public void verifyFileUpload(){
        UploadDownloadPage page = new UploadDownloadPage(getDriver());

        page.navigateToUploadDownload();
        page.uploadFile(uploadFilePath);

        String displayedPath = page.getUploadedFileName();
        Assert.assertTrue(displayedPath.contains("test-upload.txt"),
                "Uploaded filename should appear on page");
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Upload and Download - Verify File Download")
    public void verifyFileDownload() {
        UploadDownloadPage page = new UploadDownloadPage(getDriver());

        page.navigateToUploadDownload();

        boolean downloaded = page.clickDownloadAndVerify(
                downloadFolderPath,
                "sampleFile.jpeg"  // this is the filename demoqa downloads
        );

        Assert.assertTrue(downloaded,
                "File should be downloaded to: " + downloadFolderPath);
    }
}