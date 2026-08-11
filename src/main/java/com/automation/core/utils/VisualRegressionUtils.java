package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import io.qameta.allure.Allure;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import ru.yandex.qatools.ashot.AShot;
import ru.yandex.qatools.ashot.Screenshot;
import ru.yandex.qatools.ashot.comparison.ImageDiff;
import ru.yandex.qatools.ashot.comparison.ImageDiffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pixel-level visual regression checks via AShot. Locator-based assertions
 * (the rest of this framework) verify an element exists/has expected text —
 * they do NOT catch a layout shift, overlapping element, or CSS regression
 * where every locator still resolves fine but the page looks wrong. This
 * fills that gap with a lightweight, no-external-service screenshot diff.
 *
 * First run for a given snapshot name saves the baseline and passes (there
 * is nothing to compare against yet) — commit that baseline image alongside
 * the test. Every subsequent run diffs against it.
 *
 * Config (global.properties or -D overrides):
 *   visual.enabled=true       - master switch (default true)
 *   visual.failOnDiff=true    - fail the test on a diff vs just log+attach (default true)
 *   visual.diffThreshold=0    - allowed diff pixel count before it counts as a mismatch
 *
 * Baselines live under src/test/resources/visual-baselines/<site>/<name>.png
 * (commit these to git). Diff images (only produced on mismatch) land under
 * target/visual-diffs/<name>-diff.png and get attached to Allure.
 *
 * Usage inside a test:
 *   VisualRegressionUtils.compareOrCaptureBaseline(driver, "demoqa", "text-box-page");
 */
public final class VisualRegressionUtils {

    private static final Logger logger = LoggerFactory.getLogger(VisualRegressionUtils.class);
    private static final Path BASELINE_DIR = Paths.get("src", "test", "resources", "visual-baselines");
    private static final Path DIFF_DIR = Paths.get("target", "visual-diffs");

    private VisualRegressionUtils() {
    }

    /**
     * Compares a full-page screenshot against the stored baseline for
     * {@code snapshotName} under {@code site}. If no baseline exists yet,
     * saves the current screenshot as the new baseline and returns without
     * failing (nothing to compare against on the very first run).
     */
    public static void compareOrCaptureBaseline(WebDriver driver, String site, String snapshotName) {
        if (!ConfigReader.getBoolean("visual.enabled", true)) {
            logger.info("[VisualRegressionUtils] visual.enabled=false — skipping '" + snapshotName + "'");
            return;
        }

        try {
            Path baselinePath = BASELINE_DIR.resolve(site).resolve(snapshotName + ".png");
            Screenshot current = new AShot().takeScreenshot(driver);

            if (!Files.exists(baselinePath)) {
                Files.createDirectories(baselinePath.getParent());
                ImageIO.write(current.getImage(), "png", baselinePath.toFile());
                logger.info("[VisualRegressionUtils] No baseline found for '" + snapshotName
                    + "' — saved current screenshot as the new baseline at " + baselinePath
                    + ". Commit this file, then re-run to actually verify future diffs.");
                return;
            }

            BufferedImage baselineImage = ImageIO.read(baselinePath.toFile());
            ImageDiff diff = new ImageDiffer().makeDiff(baselineImage, current.getImage());

            int allowedDiffPixels = ConfigReader.getInt("visual.diffThreshold", 0);
            int actualDiffPixels = diff.getDiffSize();

            if (actualDiffPixels > allowedDiffPixels) {
                // Namespaced by site + thread ID, not just snapshotName: two
                // different sites (or two parallel test classes reusing the
                // same snapshot name) writing a diff at the same moment would
                // otherwise race on one shared file — same bug class as the
                // chromedriver-log and temp-profile-dir fixes elsewhere in
                // this project, just missed here since this class was added
                // after that hardening pass.
                Path diffPath = DIFF_DIR.resolve(site + "-" + snapshotName
                    + "-thread-" + Thread.currentThread().getId() + "-diff.png");
                Files.createDirectories(DIFF_DIR);
                ImageIO.write(diff.getMarkedImage(), "png", diffPath.toFile());
                attachToAllure(snapshotName, diffPath, actualDiffPixels);

                logger.warn("[VisualRegressionUtils] '" + snapshotName + "' — visual diff detected: "
                    + actualDiffPixels + " differing pixel(s) (threshold " + allowedDiffPixels
                    + "). Diff image: " + diffPath);

                if (ConfigReader.getBoolean("visual.failOnDiff", true)) {
                    throw new AssertionError("[VisualRegressionUtils] '" + snapshotName
                        + "' does not match its visual baseline — " + actualDiffPixels
                        + " differing pixel(s) (threshold " + allowedDiffPixels
                        + "). See the attached diff image in Allure, or " + diffPath
                        + ". If this change is intentional, delete " + baselinePath
                        + " and re-run once to re-baseline.");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("[VisualRegressionUtils] Failed to read/write screenshot for '"
                + snapshotName + "'", e);
        }
    }

    private static void attachToAllure(String snapshotName, Path diffPath, int diffPixels) {
        try (var in = Files.newInputStream(diffPath)) {
            Allure.addAttachment(
                "Visual Diff — " + snapshotName + " (" + diffPixels + " px)",
                "image/png",
                in,
                "png"
            );
        } catch (Exception e) {
            logger.warn("[VisualRegressionUtils] Could not attach diff image to Allure: " + e.getMessage());
        }
    }

    /** Convenience overload — captures the diff image as bytes without touching disk, for callers that already have their own attachment pipeline. */
    public static byte[] captureScreenshotBytes(WebDriver driver) {
        try {
            BufferedImage image = new AShot().takeScreenshot(driver).getImage();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return ((TakesScreenshot) driver).getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
        }
    }
}
