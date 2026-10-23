package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import org.monte.media.Format;
import org.monte.media.FormatKeys.MediaType;
import org.monte.media.math.Rational;
import org.monte.screenrecorder.ScreenRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.monte.media.FormatKeys.EncodingKey;
import static org.monte.media.FormatKeys.FrameRateKey;
import static org.monte.media.FormatKeys.KeyFrameIntervalKey;
import static org.monte.media.FormatKeys.MediaTypeKey;
import static org.monte.media.FormatKeys.MimeTypeKey;
import static org.monte.media.VideoFormatKeys.CompressorNameKey;
import static org.monte.media.VideoFormatKeys.DepthKey;
import static org.monte.media.VideoFormatKeys.ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE;
import static org.monte.media.VideoFormatKeys.QualityKey;

/**
 * Best-effort per-test screen recording (java.awt.Robot under the hood via
 * Monte Screen Recorder — no ffmpeg/native binary needed). Mirrors
 * ScreenshotUtil/FailureDiagnostics' defensive style: any failure here
 * (no display, headless environment, recorder init error) is logged and
 * swallowed, never allowed to fail the actual test.
 *
 * IMPORTANT: this captures whatever is on the JVM's display, not the
 * browser window specifically. That only means something when the browser
 * is actually rendering to a real or virtual (Xvfb) display — a
 * headless=true Chrome/Firefox run has nothing to capture, so
 * video.enabled is a no-op there by design (see global.properties).
 *
 * One instance is created per test attempt (TestListener owns the
 * lifecycle: start in beforeInvocation, stop in afterInvocation), so this
 * class is intentionally NOT a singleton/static-state holder — each test
 * thread gets its own recorder and output file, same reasoning as
 * DriverFactory's per-thread chromedriver log/profile-dir isolation.
 */
public final class VideoRecorder {

    private static final Logger logger = LoggerFactory.getLogger(VideoRecorder.class);

    private ScreenRecorder recorder;
    private File videoFile;
    private boolean active;

    /** Starts recording, or silently no-ops (active stays false) if video.enabled
     *  is off, the environment is headless, or the recorder fails to start. */
    public void start(String testName) {
        if (!ConfigReader.getBoolean("video.enabled", false)) {
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            logger.info("video.enabled is true but this JVM has no display (GraphicsEnvironment.isHeadless()) "
                + "— skipping recording for [" + testName + "]. Run with -Dheadless=false under a real "
                + "or Xvfb display to get real recordings.");
            return;
        }

        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle captureArea = gc.getBounds();

            String outputDir = ConfigReader.get("video.output.dir", "target/videos");
            Files.createDirectories(Path.of(outputDir));

            int fps = ConfigReader.getInt("video.fps", 10);
            Format fileFormat = new Format(MediaTypeKey, MediaType.FILE, MimeTypeKey, "avi");
            Format screenFormat = new Format(
                MediaTypeKey, MediaType.VIDEO,
                EncodingKey, ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE,
                CompressorNameKey, ENCODING_AVI_TECHSMITH_SCREEN_CAPTURE,
                DepthKey, 24,
                FrameRateKey, Rational.valueOf(fps),
                QualityKey, 1.0f,
                KeyFrameIntervalKey, fps * 60
            );

            recorder = new ScreenRecorder(gc, captureArea, fileFormat, screenFormat, null, null,
                new File(outputDir)) {
                @Override
                protected File createMovieFile(Format fileFormat) {
                    // Own naming instead of Monte's default timestamp-only name, so the
                    // file is identifiable without opening it — mirrors
                    // ScreenshotUtil.captureScreenshot()'s millisecond+random-suffix
                    // pattern for the same collision-avoidance reason under parallel
                    // threads.
                    String timestamp = LocalDateTime.now(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS"));
                    String uniqueSuffix = Long.toHexString(
                        java.util.concurrent.ThreadLocalRandom.current().nextLong());
                    File dir = new File(outputDir);
                    dir.mkdirs();
                    videoFile = new File(dir, sanitize(testName) + "_" + timestamp + "_" + uniqueSuffix + ".avi");
                    return videoFile;
                }
            };
            recorder.start();
            active = true;
        } catch (Exception e) {
            logger.warn("Could not start video recording for [" + testName + "]: " + e.getMessage());
            recorder = null;
            active = false;
        }
    }

    /** Stops recording. Returns the recorded file, or null if recording was never
     *  active/failed to start. Caller decides whether to keep, attach, or delete it. */
    public File stop() {
        if (!active || recorder == null) {
            return null;
        }
        try {
            recorder.stop();
        } catch (Exception e) {
            logger.warn("Could not stop video recording cleanly: " + e.getMessage());
        } finally {
            active = false;
        }
        // Monte's ScreenRecorder.stop() finalizes the AVI it created via
        // createMovieFile() above (tracked in videoFile); getCreatedMovieFiles()
        // is a secondary fallback in case that field is ever empty (e.g. stop()
        // threw before finalizing but a file still exists on disk).
        if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
            return videoFile;
        }
        try {
            List<File> created = recorder.getCreatedMovieFiles();
            if (created != null && !created.isEmpty()) {
                return created.get(created.size() - 1);
            }
        } catch (Exception ignored) {
            // recorder may already be in a torn-down state; nothing more we can do
        }
        return null;
    }

    /** Deletes the given recording (used when a passing test's video isn't being
     *  kept — see video.keep.on.pass). No-ops quietly if the file is null/missing. */
    public static void discard(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            logger.warn("Could not delete discarded video recording " + file + ": " + e.getMessage());
        }
    }

    private static String sanitize(String testName) {
        if (testName == null || testName.isBlank()) {
            return "test";
        }
        return testName.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}
