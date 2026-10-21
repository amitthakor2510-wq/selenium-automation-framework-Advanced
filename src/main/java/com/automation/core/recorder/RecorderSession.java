package com.automation.core.recorder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.Alert;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.UnhandledAlertException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drives one recording: injects the in-page recorder script, polls it for what the user did, and
 * adds the things only the driver can see (address changes, new or closed windows, JavaScript
 * alerts). The result is an ordered list of {@link RecordedEvent}s for {@link StepBuilder}.
 *
 * <p>The polling step is exposed as {@link #pollOnce()} so tests can drive a recording
 * deterministically; {@link #run(long, long)} just calls it in a loop.
 *
 * <p>Known limits: events are queued in sessionStorage so they survive same-origin navigations, but
 * a cross-origin navigation can drop whatever the user did in its last fraction of a second; iframes
 * are only seen if they are same-origin; shadow DOM and native (non-web) dialogs are not captured.
 */
public final class RecorderSession {

    private static final Logger logger = LoggerFactory.getLogger(RecorderSession.class);
    private static final String SCRIPT_RESOURCE = "recorder/recorder.js";
    private static final String DRAIN_JS = "return window.__kdtRec ? window.__kdtRec.drain() : null;";
    private static final String FLUSH_JS = "if (window.__kdtRec) { window.__kdtRec.flush(); }";
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final WebDriver driver;
    private final String script;
    private final List<RecordedEvent> events = new ArrayList<>();
    private final List<String> knownHandles = new ArrayList<>();
    private final Map<String, String> lastUrl = new HashMap<>();
    private String currentHandle;
    private volatile boolean stopRequested;
    private boolean finished;

    public RecorderSession(WebDriver driver) {
        this.driver = driver;
        this.script = loadScript();
    }

    /** Opens {@code startUrl}, injects the recorder and records the page as the first event. */
    public void start(String startUrl) {
        driver.get(startUrl);
        currentHandle = driver.getWindowHandle();
        knownHandles.add(currentHandle);
        inject();
        String url = safeUrl();
        lastUrl.put(currentHandle, url);
        events.add(RecordedEvent.synthetic("url", url, null, System.currentTimeMillis()));
    }

    /** Asks {@link #run(long, long)} to stop at the next poll (for example when the user presses ENTER). */
    public void requestStop() {
        stopRequested = true;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public List<RecordedEvent> events() {
        return events;
    }

    /** Polls until the user stops, the browser goes away, or {@code maxMillis} has passed. */
    public void run(long maxMillis, long pollMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (pollOnce() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * One poll: notice window changes, collect queued page events, notice an address change.
     *
     * @return false once recording should stop (stop requested or browser closed)
     */
    public boolean pollOnce() {
        if (finished) {
            return false;
        }
        try {
            syncWindows();
            if (!finished) {
                drainCurrentWindow();
                detectUrlChange();
            }
        } catch (UnhandledAlertException e) {
            handleAlert(e);
        } catch (NoSuchWindowException e) {
            logger.debug("[Recorder] The window went away mid-poll; picking up the next one on the next poll");
        } catch (WebDriverException e) {
            if (browserGone(e)) {
                finished = true;
            } else {
                logger.debug("[Recorder] Poll failed, will retry: {}", firstLine(e.getMessage()));
            }
        }
        return !finished && !stopRequested;
    }

    /** Collects whatever is still queued in the page and returns every event recorded. */
    public List<RecordedEvent> finish() {
        try {
            ((JavascriptExecutor) driver).executeScript(FLUSH_JS);
            drainCurrentWindow();
            detectUrlChange();
        } catch (RuntimeException e) {
            logger.debug("[Recorder] Final drain skipped: {}", firstLine(e.getMessage()));
        }
        finished = true;
        return events;
    }

    // ------------------------------------------------------------------- internals

    private void syncWindows() {
        List<String> now = new ArrayList<>(driver.getWindowHandles());
        if (now.isEmpty()) {
            finished = true;
            return;
        }
        if (!now.contains(currentHandle)) {
            String target = now.get(now.size() - 1);
            driver.switchTo().window(target);
            currentHandle = target;
            events.add(RecordedEvent.synthetic("window", safeUrl(), "closed", System.currentTimeMillis()));
            lastUrl.putIfAbsent(target, safeUrl());
            inject();
        }
        for (String handle : now) {
            if (!knownHandles.contains(handle)) {
                drainQuietly(); // the click that opened this window is still queued in the window we are leaving
                driver.switchTo().window(handle);
                currentHandle = handle;
                knownHandles.add(handle);
                events.add(RecordedEvent.synthetic("window", safeUrl(), "new", System.currentTimeMillis()));
                lastUrl.put(handle, safeUrl());
                inject();
            }
        }
        knownHandles.retainAll(now);
    }

    private void drainQuietly() {
        try {
            drainCurrentWindow();
        } catch (UnhandledAlertException e) {
            throw e;
        } catch (WebDriverException e) {
            logger.debug("[Recorder] Could not drain the window being left: {}", firstLine(e.getMessage()));
        }
    }

    private void drainCurrentWindow() {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object raw = js.executeScript(DRAIN_JS);
        if (raw == null) {
            inject();
            raw = js.executeScript(DRAIN_JS);
        }
        if (raw instanceof String json) {
            parse(json);
        }
    }

    private void parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.path("stop").asBoolean(false)) {
                stopRequested = true;
            }
            long received = System.currentTimeMillis();
            for (JsonNode node : root.path("events")) {
                RecordedEvent event = MAPPER.treeToValue(node, RecordedEvent.class);
                event.rcv = received;
                events.add(event);
            }
        } catch (IOException e) {
            logger.warn("[Recorder] Could not read a batch of events from the page: {}", e.getMessage());
        }
    }

    private void detectUrlChange() {
        String url = safeUrl();
        if (url == null || "about:blank".equals(url) || url.equals(lastUrl.get(currentHandle))) {
            return;
        }
        lastUrl.put(currentHandle, url);
        events.add(RecordedEvent.synthetic("url", url, null, System.currentTimeMillis()));
    }

    private void handleAlert(UnhandledAlertException e) {
        String text = e.getAlertText();
        try {
            Alert alert = driver.switchTo().alert();
            text = alert.getText();
            alert.accept();
        } catch (NoAlertPresentException gone) {
            logger.debug("[Recorder] The driver had already dismissed the alert");
        }
        try {
            drainCurrentWindow(); // the click that opened the alert was queued first and must precede it
        } catch (RuntimeException drainFailure) {
            logger.debug("[Recorder] Could not drain after an alert: {}", firstLine(drainFailure.getMessage()));
        }
        events.add(RecordedEvent.synthetic("alert", safeUrl(), text, System.currentTimeMillis()));
    }

    private void inject() {
        try {
            ((JavascriptExecutor) driver).executeScript(script);
        } catch (UnhandledAlertException e) {
            throw e;
        } catch (WebDriverException e) {
            logger.debug("[Recorder] Could not inject into {}: {}", safeUrl(), firstLine(e.getMessage()));
        }
    }

    private String safeUrl() {
        try {
            return driver.getCurrentUrl();
        } catch (UnhandledAlertException e) {
            throw e;
        } catch (WebDriverException e) {
            return null;
        }
    }

    private static boolean browserGone(WebDriverException e) {
        if (e instanceof NoSuchSessionException) {
            return true;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("disconnected") || message.contains("not reachable") || message.contains("invalid session id")
            || message.contains("session deleted") || message.contains("connection refused")
            || message.contains("target window already closed") || message.contains("no such session");
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String loadScript() {
        try (InputStream in = RecorderSession.class.getClassLoader().getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Recorder script not found on the classpath: " + SCRIPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the recorder script", e);
        }
    }
}
