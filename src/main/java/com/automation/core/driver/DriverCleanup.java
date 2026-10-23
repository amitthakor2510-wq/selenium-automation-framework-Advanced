package com.automation.core.driver;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Browser/driver-process lifecycle helpers split out of {@link DriverFactory}:
 * the {@code quit()} fallback that force-kills orphaned chromedriver/browser
 * process trees, and the JVM-exit cleanup of per-session temp profiles.
 * {@link DriverFactory} keeps the public entry points ({@code quitDriver},
 * {@code forceKillOrphanedDriverProcess}) as thin delegators so callers are
 * unchanged.
 */
final class DriverCleanup {

    private static final Logger logger = LoggerFactory.getLogger(DriverCleanup.class);

    private DriverCleanup() {
    }

    // Every --user-data-dir temp profile created by buildChromeOptions() is
    // tracked here so a single JVM shutdown hook can clean all of them up at
    // once. Without this, each session's temp dir (created fresh per test to
    // guarantee quit() can actually kill the browser — see the comment in
    // buildChromeOptions()) was never deleted, leaking one directory per test
    // run indefinitely. Deleting at JVM-exit time (not right after quit())
    // avoids racing Chrome's own child process, which can still hold a lock
    // on profile files for a moment after the WebDriver session reports closed.
    private static final List<File> TEMP_PROFILE_DIRS =
        Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean cleanupHookRegistered = false;

    // ── Orphaned browser/driver-process cleanup ─────────────────────────────────
    //
    // ROOT CAUSE (confirmed against both the local and Jenkins regression
    // runs): a sizeable, growing fraction of test failures in a long run were
    // NOT genuine test/app bugs at all — they were UnreachableBrowserException
    // / "Error communicating with the remote browser. It may have died." /
    // "HTTP/1.1 header parser received no bytes", hitting completely
    // unrelated test classes later in the same run, in a widening cascade.
    // The trigger every time was a PRECEDING "[BaseTest] driver.quit() failed:
    // Timed out waiting for driver server to stop." warning: Selenium's own
    // DriverService.stop() has an internal timeout waiting for the
    // chromedriver process (and the real browser process it launched) to
    // actually exit, and throws instead of blocking forever once that
    // timeout is hit — but BaseTest.tearDown() only logged that exception,
    // it never did anything about the process the exception says didn't
    // stop. That left a real chromedriver + Chrome process (plus its own
    // renderer/GPU child processes) running and holding memory for the rest
    // of the JVM's lifetime, once per occurrence. On a run with 100+ tests
    // this accumulates: each leaked instance is 100-300MB+, and once enough
    // of them pile up the box runs out of memory/file descriptors, so even
    // a BRAND-NEW, otherwise-healthy Chrome session for the next test can
    // get OOM-killed or fail to complete its own HTTP handshake with
    // chromedriver mid-test — exactly the symptom seen cascading across
    // dozens of unrelated tests in both logs. This tracks enough state per
    // thread (the DriverService that owns the process, plus the browser's
    // own OS PID via the `goog:processID` capability every Chromium-based
    // driver reports) to forcibly kill the whole process tree the moment
    // quit() proves it didn't shut down cleanly, instead of leaving it to
    // linger and slowly starve the rest of the run.
    private static final ThreadLocal<DriverService> CURRENT_DRIVER_SERVICE = new ThreadLocal<>();

    /** Remembers the driver service that owns this thread's browser process, for {@link #quitDriver}. */
    static void track(DriverService service) {
        CURRENT_DRIVER_SERVICE.set(service);
    }

    /**
     * Central replacement for calling {@code driver.quit()} directly.
     * Behaves identically to a normal quit() on the (overwhelmingly common)
     * happy path, but on failure — instead of just letting the exception
     * propagate for the caller to log and move on from, as
     * BaseTest#tearDown() used to do —
     * forcibly kills the underlying chromedriver/browser process tree so it
     * cannot linger and consume memory for the remainder of the run. See the
     * class-level comment on {@link #CURRENT_DRIVER_SERVICE} for the full
     * root-cause history. Safe to call with a null/already-quit driver.
     */
    static void quitDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception e) {
            logger.warn("[DriverFactory] driver.quit() did not shut down cleanly (" + e.getMessage()
                + ") — force-killing the underlying browser/driver process tree so it doesn't linger"
                + " and starve later tests of memory.");
            forceKillOrphanedDriverProcess(driver);
        } finally {
            CURRENT_DRIVER_SERVICE.remove();
        }
    }

    /**
     * Kills the browser process (and every child process it spawned —
     * renderer, GPU, utility processes, etc.) plus the chromedriver/
     * geckodriver/msedgedriver/safaridriver server process itself, using
     * whatever identifying information is still available for a driver
     * whose own quit() has already proven unreliable. Best-effort by
     * design: this runs precisely when the driver is in a broken state, so
     * every step here tolerates the corresponding lookup or kill failing
     * silently rather than throwing a second exception on top of the
     * original quit() failure.
     */
    static void forceKillOrphanedDriverProcess(WebDriver driver) {
        Long browserPid = extractBrowserProcessId(driver);
        if (browserPid != null) {
            ProcessHandle.of(browserPid).ifPresent(ph -> {
                // Kill descendants (renderer/GPU/utility child processes a
                // real browser launches under its own main PID) before the
                // main process itself, so nothing is left holding memory.
                ph.descendants().forEach(ProcessHandle::destroyForcibly);
                ph.destroyForcibly();
            });
        }

        DriverService service = CURRENT_DRIVER_SERVICE.get();
        if (service != null) {
            try {
                service.close();
            } catch (Exception ignored) {
                // The goal here is just making sure the OS process is
                // actually gone — a second failure trying to shut it down
                // "cleanly" isn't itself a new problem worth surfacing.
            }
        }
        CURRENT_DRIVER_SERVICE.remove();
    }

    private static Long extractBrowserProcessId(WebDriver driver) {
        try {
            if (driver instanceof RemoteWebDriver) {
                Object pid = ((RemoteWebDriver) driver).getCapabilities().getCapability("goog:processID");
                if (pid instanceof Number) {
                    return ((Number) pid).longValue();
                }
            }
        } catch (Exception e) {
            // The session is exactly why we're here — capabilities being
            // unreachable on an already-broken driver is expected, not an
            // additional failure to report.
            logger.debug("[DriverFactory] Could not read goog:processID from the broken session: "
                + e.getMessage());
        }
        return null;
    }

    static synchronized void registerTempProfileCleanup(java.nio.file.Path tempProfile) {
        TEMP_PROFILE_DIRS.add(tempProfile.toFile());
        if (!cleanupHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (File dir : TEMP_PROFILE_DIRS) {
                    deleteRecursively(dir);
                }
            }, "selenium-profile-cleanup"));
            cleanupHookRegistered = true;
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            // Best-effort only — a lingering lock file or open handle here
            // just means one leftover temp dir, not a build failure.
            logger.debug("[DriverFactory] Could not delete temp profile file/dir: "
                + file.getAbsolutePath());
        }
    }

}
