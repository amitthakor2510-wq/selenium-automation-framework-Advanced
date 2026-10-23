package com.automation.core.driver;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.DriverInitializationException;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic chromedriver/geckodriver port assignment plus the retry loop
 * around driver start-up, split out of {@link DriverFactory}. See the
 * comments below for the port-allocation race this exists to defend against.
 * Package-private: only {@link DriverFactory} uses it.
 */
final class DriverStartupRetry {

    private static final Logger logger = LoggerFactory.getLogger(DriverStartupRetry.class);

    private DriverStartupRetry() {
    }

    // ── Local-driver port-race retry (Chrome / Brave / Edge) ───────────────────
    //
    // ROOT CAUSE (confirmed via a real parallel regression run, not a guess):
    // Selenium's PortProber.findFreePort() finds a "free" ephemeral port by
    // opening a ServerSocket on port 0, reading the OS-assigned port number,
    // then immediately closing that socket — the driver service binds to
    // that same port number a moment later, in a separate step. That gap is
    // a classic time-of-check/time-of-use race: under this project's
    // parallel="classes" thread-count="3" TestNG config, three
    // Chrome/Edge driver processes can each ask the OS for a free port
    // within milliseconds of each other, and the OS can legitimately hand
    // out the *same* just-closed port to more than one of them before any
    // of them has actually bound it. Whichever process loses that race
    // fails immediately with RuntimeException("Unable to find a free
    // port..."), from inside Selenium's own DriverService.Builder — there is
    // no hook to intervene earlier, since the ChromeDriver/EdgeDriver
    // constructor does the whole find-port-then-bind sequence internally.
    //
    // UPDATED (Jenkins build failure, 2026-08-05): the "three at once"
    // assumption above only accounted for a single suite's own
    // thread-count="3". It didn't account for the Jenkinsfile's "Run Tests
    // Per Site" stage, which runs multiple sites' suites concurrently as
    // separate `parallel branches` (e.g. demoqa + saucedemo at the same
    // time) — each branch is its own `mvn test` JVM with its own
    // thread-count="3". Two sites racing at once means up to *six*
    // concurrent ChromeDriver/EdgeDriver launches on the same box, not
    // three, which was enough to exhaust the old 4-attempt retry budget
    // outright (LinksTest/ProgressBarTest both failed after 4/4 attempts).
    // Attempts and jitter widened below so the retry budget matches actual
    // cross-site concurrency instead of just one suite's thread-count.
    //
    // This is not fixable by changing driver options or Chrome flags — it's
    // a race in ephemeral port allocation itself, which is why simply
    // asking the OS again (a fresh findFreePort() call gets a *different*
    // ephemeral port almost certainly, since the OS won't immediately
    // re-hand-out one it just gave to someone else) reliably resolves it on
    // retry. A short random jitter before each retry additionally
    // de-synchronizes threads that raced each other on the previous
    // attempt, so they don't just re-race at the same instant again.
    //
    // Serializing all browser launches with a global lock would eliminate
    // the race entirely, but would also serialize away the whole point of
    // parallel="classes" — so retry-with-jitter was the first fix tried,
    // not a lock. Raised from 8 to 12 (2026-08-07): on a CPU-constrained
    // shared Jenkins box, 8 attempts (each with 200-800ms jitter, so ~4s of
    // budget) was still getting exhausted repeatedly under 6 concurrent
    // chromedriver launches — see testng-suites/*-regression.xml, which was
    // also lowered from thread-count=3 to 2 the same day for the same
    // reason.
    //
    // ROOT CAUSE FIX (2026-08-07, second build the same day — the 12-attempt
    // budget above was STILL exhausted, this time by
    // LoginDataDrivenTest specifically): that class launches a brand-new
    // session per data row (CSV/XLSX/JSON/YAML/ZIP — 15+ rows), back-to-back
    // with no pacing, in the exact window demoqa's own "classes" threads are
    // also launching sessions — retry-with-jitter is a PROBABILISTIC
    // mitigation for PortProber's find-then-bind race, and probability
    // alone stopped being good enough once launch frequency got this high.
    // Purely widening the retry budget again would only buy a bit more
    // runway before the same exhaustion recurs at the next busier suite.
    //
    // Instead, each concurrent "launch slot" (site JVM x TestNG thread) now
    // gets its own small, non-overlapping range of EXPLICIT candidate ports
    // (see candidatePort() below), passed to DriverService.Builder.usingPort()
    // instead of leaving port=0. Selenium's PortProber.findFreePort() is
    // only invoked internally when the requested port is 0 — supplying an
    // explicit port bypasses that TOCTOU-prone lookup entirely for the
    // build() step, so two of our OWN threads can no longer legitimately be
    // handed the same "free" port to race over. The per-site offset
    // (candidatePort() hashes ConfigReader.getActiveSite()) guards against
    // the same class of collision already fixed for the per-thread
    // chromedriver log path: demoqa's and saucedemo's separate `mvn test`
    // JVMs each start counting thread slots from 0, so without a
    // site-specific offset their slot-0 threads would still compute the
    // identical port. Retry-with-jitter remains as a defensive fallback
    // (e.g. an unrelated process already happens to hold our computed
    // port), which is why the loop and its attempt budget stay in place —
    // it just isn't the primary defense anymore.
    static final int DRIVER_CREATION_MAX_ATTEMPTS = 12;

    // Small, dense, per-JVM slot index (0, 1, 2, ...) — deliberately NOT
    // Thread.currentThread().getId(), which is unbounded and not guaranteed
    // small, so it can't be used directly to size a bounded port range.
    private static final java.util.concurrent.atomic.AtomicInteger THREAD_SLOT_COUNTER =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final ThreadLocal<Integer> THREAD_SLOT =
        ThreadLocal.withInitial(THREAD_SLOT_COUNTER::getAndIncrement);

    private static final int PORT_RANGE_BASE = 20000;
    private static final int PORTS_PER_SITE = 200;
    private static final int PORTS_PER_SLOT = 20;
    private static final int SLOTS_PER_SITE = PORTS_PER_SITE / PORTS_PER_SLOT;

    /**
     * Deterministic candidate port for this thread's Nth driver-launch
     * attempt. Distinct (site, thread-slot, attempt) triples never
     * collide with each other; only an unrelated external process already
     * sitting on the same computed port can still cause a bind failure,
     * which {@link #isPortAllocationRace} treats the same as the old
     * find-a-free-port race so the retry loop still recovers from it.
     */
    static int candidatePort(int attempt) {
        int siteOffset = Math.floorMod(safeSiteName().hashCode(), 50) * PORTS_PER_SITE;
        int slotOffset = (THREAD_SLOT.get() % SLOTS_PER_SITE) * PORTS_PER_SLOT;
        int attemptOffset = (attempt - 1) % PORTS_PER_SLOT;
        return PORT_RANGE_BASE + siteOffset + slotOffset + attemptOffset;
    }

    static String safeSiteName() {
        try {
            String site = ConfigReader.getActiveSite();
            return site != null ? site : "default";
        } catch (RuntimeException e) {
            // Config not yet initialized (e.g. a unit test calling
            // DriverFactory directly) — fall back to a fixed bucket rather
            // than letting port computation itself throw.
            return "default";
        }
    }

    static WebDriver createWithPortConflictRetry(String browserLabel,
                                                         java.util.function.IntFunction<WebDriver> creator) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= DRIVER_CREATION_MAX_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1) {
                    try {
                        Thread.sleep(200L + (long) (Math.random() * 600));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    logger.warn("[DriverFactory] " + browserLabel + " driver: retrying after a free-port"
                        + " allocation race (attempt " + attempt + "/" + DRIVER_CREATION_MAX_ATTEMPTS + ").");
                }
                return creator.apply(attempt);
            } catch (RuntimeException e) {
                if (!isPortAllocationRace(e)) {
                    throw e;
                }
                lastFailure = e;
            }
        }
        throw new DriverInitializationException(
            "[DriverFactory] " + browserLabel + " driver failed to start after " + DRIVER_CREATION_MAX_ATTEMPTS
                + " attempts, each hitting a port allocation race despite explicit deterministic port"
                + " assignment (see candidatePort()). If this becomes frequent (not just occasional under"
                + " heavy load), consider lowering the parallel thread-count in the TestNG suite XML being run.",
            lastFailure);
    }

    private static boolean isPortAllocationRace(RuntimeException e) {
        // Walk the cause chain too — SessionNotCreatedException's own
        // getMessage() is just "Could not start a new session...";  the
        // actually diagnostic text ("Driver server process died
        // prematurely") lives on its cause, not concatenated into the
        // outer message. Checking only e.getMessage() (as this method used
        // to) meant that specific, very-much-related-to-the-same-race
        // failure was never recognized as retryable at all.
        Throwable current = e;
        int guard = 0;
        while (current != null && guard++ < 10) {
            if (matchesRetryableText(current.getMessage())) {
                return true;
            }
            Throwable next = current.getCause();
            current = (next == current) ? null : next;
        }
        return false;
    }

    private static boolean matchesRetryableText(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unable to find a free port")
            || lower.contains("address already in use")
            || lower.contains("port is already allocated")
            || lower.contains("eaddrinuse")
            || lower.contains("cannot bind")) {
            return true;
        }
        // BUG FIX (2026-08-07 build — ProgressBarTest.setUp): under exactly
        // the same heavy concurrent-launch conditions that cause the port
        // race above, chromedriver's OWN process can also die before it
        // even gets to respond to the session-creation POST — Selenium
        // then throws SessionNotCreatedException wrapping "Driver server
        // process died prematurely" (see WebDriverException/DriverService),
        // a message that never mentions "port" at all. Because that string
        // didn't match any check above, this exact failure was falling
        // straight through createWithPortConflictRetry's catch and
        // propagating on the FIRST attempt — burning zero retries — while
        // every sibling failure in the same run correctly consumed a
        // 12-attempt budget first. It's the identical root cause (too many
        // concurrent chromedriver launches contending for process/CPU
        // resources on a constrained box) manifesting as a process crash
        // instead of a bind failure, so it gets the same retry-with-jitter
        // treatment rather than being treated as a distinct, non-retryable
        // failure mode.
        //
        // ROOT CAUSE FIX (found reviewing a "Chrome instance exited" build
        // log): this method used to ALSO match plain
        // "could not start a new session" — but that string is not a
        // symptom of anything in particular, it's Selenium's own generic
        // SessionNotCreatedException wrapper text
        // ("Could not start a new session. Response code %s. Message:
        // %s"), present verbatim on the OUTER exception of every single
        // Chrome/Edge/Brave launch failure regardless of cause. Since
        // isPortAllocationRace() checks e.getMessage() at the top of the
        // cause chain first, that one clause alone made this method return
        // true for 100% of SessionNotCreatedExceptions — a bad
        // --start-maximized launch on a display-less runner, a corrupted
        // profile dir, a chromedriver/Chrome version mismatch, an OOM
        // kill — every one of them got misdiagnosed as a "port allocation
        // race", burned the full 12-attempt/jitter budget for nothing
        // (~10s+ wasted per failing test, no less flaky for it), and then
        // surfaced createWithPortConflictRetry's port-specific error
        // message and "lower the parallel thread-count" advice, which is
        // actively misleading for a failure that was never about ports or
        // concurrency at all. Removed; "chrome not reachable" and "driver
        // server process died prematurely" are the only patterns that were
        // ever actually confirmed (via isolated single-cause testing) to
        // correlate with the free-port TOCTOU race, so those are the only
        // ones left here. A genuinely non-retryable launch failure now
        // propagates on the FIRST attempt with its real message intact,
        // instead of being disguised as a port race for 12 attempts.
        return lower.contains("driver server process died prematurely")
            || lower.contains("chrome not reachable");
    }
}
