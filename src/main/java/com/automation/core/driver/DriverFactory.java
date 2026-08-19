package com.automation.core.driver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.DriverInitializationException;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariDriverService;
import org.openqa.selenium.safari.SafariOptions;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Single place responsible for creating a WebDriver instance.
 * Supports chrome / firefox / edge / brave / safari, and a headless=true/false
 * config flag so Jenkins can run headless while local dev
 * runs with a visible browser.
 *
 * SAFARI — two hard platform constraints that don't apply to any other
 * browser this factory supports, both enforced/documented at the call
 * sites below rather than silently worked around:
 * <ol>
 *   <li><b>macOS only.</b> SafariDriver ships as part of the OS
 *   (/usr/bin/safaridriver) — there is no Linux/Windows build, and
 *   WebDriverManager cannot download one. {@code safaridriver --enable}
 *   (or Safari &gt; Develop &gt; Allow Remote Automation) must already have
 *   been run on the host once; if it hasn't, session creation fails with
 *   a clear "Could not create a session" error from Selenium itself.</li>
 *   <li><b>Headless is not supported at all</b> — there is no Safari
 *   equivalent of {@code --headless}. Requesting headless=true with
 *   browser=safari is logged as a warning and a normal windowed session
 *   is launched anyway, rather than either silently pretending to be
 *   headless or failing the whole run over a flag that every other
 *   browser accepts.</li>
 *   <li><b>Only one SafariDriver session may be open on a machine at a
 *   time</b> (a WebKit/Apple limitation, not a Selenium one) — a second
 *   concurrent session fails outright. This factory does not serialize
 *   Safari session creation itself, because the session's whole lifetime
 *   (not just creation) needs to be exclusive and that lifetime is owned
 *   by BaseTest/the TestNG suite, not this class. Any suite that runs
 *   Safari MUST use {@code parallel="none"} (see
 *   testng-suites/*-safari-*.xml) — do not point a thread-count&gt;1 suite
 *   at browser=safari.</li>
 * </ol>
 *
 * DOCKER / SELENIUM GRID:
 * When grid.enabled=true (or -Dgrid.enabled=true), the browser is not
 * launched locally — instead a RemoteWebDriver session is opened against
 * the Selenium Grid hub at grid.url (default: http://localhost:4444/wd/hub).
 * This is how the framework runs inside docker-compose, where the browsers
 * live in separate selenium/node-* containers. See docker-compose.yml.
 */
public final class DriverFactory {

    private static final Logger logger = LoggerFactory.getLogger(DriverFactory.class);

    private DriverFactory() {
    }

    // ── Selenium Grid / Docker (RemoteWebDriver) ────────────────────────────────

    /**
     * Grid-specific FirefoxOptions — deliberately NOT the same object
     * createFirefoxDriver() builds for local runs: that one probes for a
     * host-specific Firefox binary path, which is meaningless for a Grid
     * node running in its own container. Extracted verbatim from the
     * former switch/case in createRemoteDriver — no behavior change.
     */
    private static FirefoxOptions buildFirefoxRemoteOptions(boolean headless) {
        FirefoxOptions ffOptions = new FirefoxOptions();
        ffOptions.setPageLoadStrategy(PageLoadStrategy.EAGER);
        // ROOT CAUSE FIX (noVNC :7901 showing nothing): same reasoning as
        // buildChromeOptions()'s forRemote branch — selenium/node-firefox
        // already runs its own Xvfb + noVNC, and "-headless" here would
        // render nothing into it. Ignore the flag for Grid sessions.
        if (headless) {
            logger.warn("[DriverFactory] headless=true was requested for a Grid (remote) firefox "
                + "session — ignoring it and launching a normal windowed session instead, so the "
                + "node's noVNC viewer (:7901) actually shows the browser.");
        }
        return ffOptions;
    }

    /**
     * Grid-specific EdgeOptions — same reasoning as buildFirefoxRemoteOptions:
     * the local createEdgeDriver() options (download prefs, notifications,
     * etc.) are host-specific and not what a Grid node needs. Extracted
     * verbatim from the former switch/case in createRemoteDriver.
     */
    private static EdgeOptions buildEdgeRemoteOptions(boolean headless) {
        EdgeOptions edgeOptions = new EdgeOptions();
        edgeOptions.setPageLoadStrategy(PageLoadStrategy.EAGER);
        // ROOT CAUSE FIX (noVNC :7902 showing nothing): same reasoning as
        // buildChromeOptions()'s forRemote branch — selenium/node-edge
        // already runs its own Xvfb + noVNC, and --headless=new here would
        // render nothing into it. Ignore the flag for Grid sessions.
        if (headless) {
            logger.warn("[DriverFactory] headless=true was requested for a Grid (remote) edge "
                + "session — ignoring it and launching a normal windowed session instead, so the "
                + "node's noVNC viewer (:7902) actually shows the browser.");
        }
        return edgeOptions;
    }

    /**
     * Grid-specific SafariOptions. Unlike Firefox/Edge's remote-options
     * builders above, this ignores {@code headless} entirely rather than
     * silently accepting-and-dropping it — Safari has no headless
     * capability to set in the first place, on Grid or otherwise, so
     * there's no flag here to conditionally add. A Selenium Grid Safari
     * node is itself a real Mac (Grid does not — cannot — offer a
     * containerized Safari node the way it does chrome/firefox/edge), so
     * the same one-session-at-a-time constraint documented on the class
     * javadoc applies to that node too.
     */
    private static SafariOptions buildSafariRemoteOptions(boolean headless) {
        if (headless) {
            logger.warn("[DriverFactory] headless=true was requested for safari, but Safari has no"
                + " headless mode — launching a normal windowed remote session instead.");
        }
        return new SafariOptions();
    }

    /**
     * Registry mapping browser name to its BrowserProvider. This is the
     * one place a new browser gets added — createDriver() and
     * createRemoteDriver() below never branch on browser name themselves,
     * they just look it up here. Each provider delegates straight to the
     * existing per-browser methods below (createChromeDriver,
     * buildChromeOptions, etc.) — none of that logic changed, only how
     * it's dispatched to.
     */
    private static final Map<String, BrowserProvider> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put("chrome", new BrowserProvider() {
            @Override
            public WebDriver createLocalDriver(boolean headless) {
                return createChromeDriver(headless);
            }

            @Override
            public Capabilities buildRemoteOptions(boolean headless) {
                // Matches the original: chrome and brave shared the same
                // grid options (buildChromeOptions), with no Brave-binary
                // path set for the remote case — the Grid node's own
                // browser image is what actually runs, not this host's
                // Brave install. tempProfile is null here deliberately: a
                // --user-data-dir pointing at a path on THIS host would be
                // meaningless (and simply wouldn't exist) inside the Grid
                // node's own container filesystem.
                //
                // ROOT CAUSE FIX (noVNC on :7900 showing nothing even
                // though the session is live): the selenium/node-chrome
                // image already runs its own internal Xvfb + x11vnc +
                // noVNC — that virtual display IS the "headless" story for
                // a Grid node; it's how a real, on-screen Chrome window
                // ends up watchable from the host despite the container
                // having no physical display. Forwarding this JVM's own
                // --headless=new flag into the REMOTE session (like the
                // local one) makes Chrome render nothing at all inside
                // that Xvfb display — noVNC then has a genuinely blank
                // screen to stream, not a broken connection. So
                // buildChromeOptions() is told forRemote=true here and
                // silently drops the --headless arg for Grid sessions —
                // same "headless doesn't make sense for this transport"
                // reasoning buildSafariRemoteOptions() already applies,
                // just logged instead of silent since Chrome (unlike
                // Safari) genuinely supports headless, so a passed-but-
                // ignored true is worth surfacing.
                return buildChromeOptions(headless, null, true);
            }
        });

        PROVIDERS.put("brave", new BrowserProvider() {
            @Override
            public WebDriver createLocalDriver(boolean headless) {
                return createBraveDriver(headless);
            }

            @Override
            public Capabilities buildRemoteOptions(boolean headless) {
                return buildChromeOptions(headless, null, true);
            }
        });

        PROVIDERS.put("firefox", new BrowserProvider() {
            @Override
            public WebDriver createLocalDriver(boolean headless) {
                return createFirefoxDriver(headless);
            }

            @Override
            public Capabilities buildRemoteOptions(boolean headless) {
                return buildFirefoxRemoteOptions(headless);
            }
        });

        PROVIDERS.put("edge", new BrowserProvider() {
            @Override
            public WebDriver createLocalDriver(boolean headless) {
                return createEdgeDriver(headless);
            }

            @Override
            public Capabilities buildRemoteOptions(boolean headless) {
                return buildEdgeRemoteOptions(headless);
            }
        });

        PROVIDERS.put("safari", new BrowserProvider() {
            @Override
            public WebDriver createLocalDriver(boolean headless) {
                return createSafariDriver(headless);
            }

            @Override
            public Capabilities buildRemoteOptions(boolean headless) {
                return buildSafariRemoteOptions(headless);
            }
        });
    }

    public static WebDriver createDriver() {
        String browser = ConfigReader.get("browser", "chrome").toLowerCase();
        boolean headless = ConfigReader.getBoolean("headless", false);

        BrowserProvider provider = PROVIDERS.get(browser);
        if (provider == null) {
            throw new DriverInitializationException("Browser not supported: " + browser
                + ". Supported: " + PROVIDERS.keySet());
        }

        WebDriver driver = ConfigReader.getBoolean("grid.enabled", false)
            ? createRemoteDriver(browser, provider, headless)
            : provider.createLocalDriver(headless);

        applyPageLoadTimeout(driver);
        return driver;
    }

    // BUG FIX: no driver-level pageLoadTimeout was ever set anywhere in this
    // class. pageLoadStrategy is EAGER (see buildChromeOptions()'s comment),
    // so driver.get() normally returns quickly once the DOM is parsed — but
    // EAGER only changes *when* Selenium considers the navigation "done", it
    // doesn't remove the underlying wait entirely, and a genuinely pathological
    // page (a script that blocks parsing, a hung request the browser is still
    // waiting on) can still leave driver.get() blocked on Selenium's own
    // internal default (300s) with nothing in this framework able to
    // interrupt it — surfacing as a whole test run looking "stuck" on the
    // second/third site of a suite with no useful error, since the hang
    // happens inside the driver.get() call itself, before any of our own
    // WebDriverWait-based element waits (which DO have a bounded, catchable
    // timeout) ever get a chance to run. Deliberately a separate config key
    // from KeywordEngine.WAIT_FOR_PAGE_LOAD's pageLoad.timeout (that one
    // polls document.readyState in JS *after* driver.get() already returned
    // control under EAGER — a different, later check) — this one is the
    // driver-level hard ceiling on driver.get() itself, so it defaults wider
    // (2x timeout.long) rather than sharing the same value. Once this fires,
    // driver.get() throws org.openqa.selenium.TimeoutException, which
    // NAVIGATE's normal step-failure handling in KeywordEngine.run() already
    // converts into an ordinary (non-hanging) test failure instead of an
    // indefinite hang.
    private static void applyPageLoadTimeout(WebDriver driver) {
        int seconds = ConfigReader.getInt("driver.pageLoad.timeout", ConfigReader.getInt("timeout.long", 15) * 2);
        try {
            driver.manage().timeouts().pageLoadTimeout(java.time.Duration.ofSeconds(seconds));
        } catch (Exception e) {
            logger.warn("[DriverFactory] Could not set pageLoadTimeout (non-fatal): {}", e.getMessage());
        }
    }

    private static WebDriver createRemoteDriver(String browser, BrowserProvider provider, boolean headless) {
        String gridUrl = ConfigReader.get("grid.url", "http://localhost:4444/wd/hub");
        Capabilities options = provider.buildRemoteOptions(headless);

        try {
            URL hubUrl = URI.create(gridUrl).toURL();
            logger.info("[DriverFactory] Connecting to Selenium Grid at " + gridUrl
                + " (browser=" + browser + ", headless=" + headless + ")");

            // Capabilities-typed constructor works identically for
            // ChromeOptions/FirefoxOptions/EdgeOptions (all implement
            // Capabilities) — replaces the former instanceof chain that
            // picked a browser-specific RemoteWebDriver constructor
            // overload; same resulting session either way.
            RemoteWebDriver remoteDriver = new RemoteWebDriver(hubUrl, options);

            // Without this, sendKeys() on a file input (upload.file.path, the
            // Practice Form "Select Picture" field, etc.) fails with "File not
            // found" — the path is only valid on the machine running this JVM
            // (the "tests" container), not on the Grid node actually running the
            // browser (the "chrome"/"firefox"/"edge" container). LocalFileDetector
            // makes RemoteWebDriver recognize when a sendKeys() value is a real
            // local file and transparently upload its bytes to the node first,
            // which is exactly what local (non-Grid) WebDriver sessions get for
            // free by sharing a filesystem with the browser.
            remoteDriver.setFileDetector(new org.openqa.selenium.remote.LocalFileDetector());
            return remoteDriver;
        } catch (MalformedURLException e) {
            throw new DriverInitializationException("[DriverFactory] Invalid grid.url: " + gridUrl, e);
        }
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
    private static final int DRIVER_CREATION_MAX_ATTEMPTS = 12;

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
    private static int candidatePort(int attempt) {
        int siteOffset = Math.floorMod(safeSiteName().hashCode(), 50) * PORTS_PER_SITE;
        int slotOffset = (THREAD_SLOT.get() % SLOTS_PER_SITE) * PORTS_PER_SLOT;
        int attemptOffset = (attempt - 1) % PORTS_PER_SLOT;
        return PORT_RANGE_BASE + siteOffset + slotOffset + attemptOffset;
    }

    private static String safeSiteName() {
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

    private static WebDriver createWithPortConflictRetry(String browserLabel,
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

    // ── Chrome ────────────────────────────────────────────────────────────────

    // NOTE: pinning chromedriver to an older build (149.x) was tried and did
    // NOT fix the Brave "Chrome instance exited" failure — it crashed
    // identically to the auto-matched 150.x build. That rules out a
    // driver/browser version mismatch as the cause. Since a real version
    // mismatch normally produces an explicit "This version of ChromeDriver
    // only supports Chrome version X" message rather than a silent crash,
    // the real cause is the Brave process itself dying on launch for a
    // reason ChromeDriver isn't surfacing (sandbox restriction, missing
    // shared library, snap confinement, GPU init failure, etc). Reverted to
    // normal auto-matched versioning; verboseLogging below is what actually
    // gets us the real reason.
    private static WebDriver createChromeDriver(boolean headless) {
        WebDriverManager.chromedriver().setup();
        // BUG FIX: the temp --user-data-dir profile used to be created fresh
        // by buildChromeOptions() on EVERY retry attempt (including ones
        // that never got far enough to actually launch Chrome, since the
        // port failure happens after options are built) — one throwaway
        // directory per attempt, never cleaned up until JVM exit (see
        // TEMP_PROFILE_DIRS below), which meant a saucedemo test class
        // burning through 12 failed attempts x 15+ data rows could leave
        // 150+ empty directories on disk mid-run. That extra filesystem
        // churn adds I/O contention on an already CPU-constrained box,
        // widening the very race window the retries exist to survive, and
        // is a plausible contributor to the Jenkins log's separate
        // "Unable to create temporary directory" surefire warning. A single
        // attempt never actually starts Chrome with a profile from an
        // EARLIER failed attempt (no attempt that lost the port race ever
        // touched it), so it's safe — and correct — to create it once
        // per createChromeDriver() call and reuse it across retries.
        java.nio.file.Path tempProfile = createIsolatedTempProfile();

        // ChromeOptions are still rebuilt fresh per attempt (cheap, and some
        // options — e.g. the logging service's port — legitimately differ
        // per attempt), but the port is now assigned deterministically via
        // candidatePort() instead of left at 0 for Selenium to race on —
        // see createWithPortConflictRetry() / candidatePort().
        return createWithPortConflictRetry("chrome", attempt -> {
            ChromeOptions options = buildChromeOptions(headless, tempProfile, false);
            ChromeDriverService service = buildDriverService(candidatePort(attempt));
            // Tracked so quitDriver()/forceKillOrphanedDriverProcess() can
            // force this specific service's process down if quit() later
            // proves unreliable — see CURRENT_DRIVER_SERVICE's class-level
            // comment for why this matters.
            CURRENT_DRIVER_SERVICE.set(service);
            return new ChromeDriver(service, options);
        });
    }

    // ── Brave ─────────────────────────────────────────────────────────────────
    // Brave is Chromium-based: same chromedriver, just point binary at Brave.

    private static WebDriver createBraveDriver(boolean headless) {
        // Locate Brave binary — check common install paths across OSes
        String braveBinary = findBraveBinary();
        if (braveBinary == null) {
            throw new DriverInitializationException(
                "Brave browser binary not found. Install Brave or set -Dbrave.binary=/path/to/brave");
        }

        // .browserBinary() points chromedriver's version-matching at Brave,
        // so WebDriverManager downloads a chromedriver matched to Brave's
        // actual Chromium engine.
        WebDriverManager.chromedriver().browserBinary(braveBinary).setup();
        logger.info("[DriverFactory] Using Brave binary: " + braveBinary);

        // Verbose ChromeDriver logging: the generic SessionNotCreatedException
        // we've been getting ("Chrome instance exited") hides the actual
        // reason the Brave process died. This routes chromedriver's verbose
        // log (which DOES include Brave's own stderr/crash output) to a file
        // instead of losing it. Check this file after any failed run:
        //   target/logs/chromedriver-brave.log
        //
        // Options/service are (re)built fresh per attempt inside the retry
        // lambda, but the temp profile dir and port are handled the same
        // way as createChromeDriver() — see the comments there.
        java.nio.file.Path tempProfile = createIsolatedTempProfile();

        return createWithPortConflictRetry("brave", attempt -> {
            ChromeOptions options = buildChromeOptions(headless, tempProfile, false);
            options.setBinary(braveBinary);
            ChromeDriverService service = buildDriverService(candidatePort(attempt));
            CURRENT_DRIVER_SERVICE.set(service);
            return new ChromeDriver(service, options);
        });
    }

    /**
     * Builds a ChromeDriverService bound to an explicit, deterministic port
     * (see {@link #candidatePort}), with best-effort verbose logging to
     * target/logs/&lt;site&gt;/chromedriver-&lt;browser&gt;-thread-&lt;id&gt;.log. A
     * failure to set up the log file never blocks a run or affects the
     * port used — see the note inside this method. History below.
     * <p>
     * BUG FIX: the log file used to be named purely by browser
     * ("chromedriver-chrome.log"), with no thread isolation — the same gap
     * already fixed for download directories (see getDownloadPath()) and
     * temp Chrome/Brave profile dirs above, just missed here. Under this
     * project's parallel="classes" thread-count="3" TestNG config, all 3
     * concurrent same-browser sessions were opening and writing to that one
     * shared file at once: on Linux the writes interleave into garbage,
     * and on Windows a second/third ChromeDriverService can fail outright
     * trying to open a file another process already has open for writing.
     * Either way the exact diagnostic log this method exists to produce —
     * "check this file after any failed run" — was unreliable precisely
     * when parallel execution made it most needed. Thread ID in the
     * filename gives each concurrent session its own log, same as the
     * download-path and temp-profile-dir fixes already do.
     * <p>
     * BUG FIX (Jenkins build failure, 2026-08-06): {@code .build()} below
     * performs its own {@code PortProber.findFreePort()} call to pick a
     * port for this diagnostic service — the exact same free-port race
     * documented on {@link #createWithPortConflictRetry}. This method used
     * to wrap the ENTIRE body (directory setup AND {@code .build()}) in one
     * broad {@code catch (Exception e)}, so under heavy parallel load that
     * race got silently swallowed right here and logged as a mere "logging
     * setup failed" warning — which is exactly what the recurring
     * "Could not set up verbose chromedriver logging: Unable to find a
     * free port" warnings in the Jenkins log were. Returning null then
     * sent every attempt down the {@code new ChromeDriver(options)}
     * fallback path, which does its OWN independent
     * {@code createDefaultService()} free-port lookup — i.e. TWO
     * find-a-free-port attempts per retry instead of one, roughly doubling
     * contention and burning through {@link #DRIVER_CREATION_MAX_ATTEMPTS}
     * twice as fast. That's why saucedemo's login tests (whose classes all
     * launch a driver, sequentially, many times over via their data
     * providers, right in the busiest opening seconds of the parallel
     * demoqa+saucedemo run) exhausted all 8 attempts outright while
     * demoqa's classes — creating far fewer drivers overall — happened to
     * ride out the same window. Fixed by narrowing the try/catch to just
     * the directory/config setup (genuine, non-racy failure modes) and
     * letting {@code .build()}'s RuntimeException propagate straight out
     * of this method and up through the {@code createChromeDriver}/
     * {@code createBraveDriver} lambdas, so {@link #createWithPortConflictRetry}
     * sees and retries it exactly once per attempt — the same single
     * lookup every other failure mode already goes through.
     */
    private static ChromeDriverService buildDriverService(int port) {
        // BUG FIX: this used to return null (falling all the way back to
        // `new ChromeDriver(options)`, i.e. Selenium's own default service
        // with NO explicit port) whenever log-directory setup failed. That
        // meant a log-setup problem silently also lost the deterministic
        // port assignment that createChromeDriver()/createBraveDriver() rely
        // on to avoid the free-port race — a logging concern and a
        // concurrency-safety concern were incorrectly tied to the same
        // fallback. They're independent now: the explicit port is always
        // used; only the verbose log FILE is best-effort.
        File logFile = tryBuildLogFile();

        ChromeDriverService.Builder builder = new ChromeDriverService.Builder()
            .usingPort(port)
            .withVerbose(true);
        if (logFile != null) {
            builder.withLogFile(logFile);
        }
        // .build() no longer performs Selenium's own PortProber.findFreePort()
        // lookup at all, since usingPort(port) above means the requested
        // port is non-zero — see the note above candidatePort(). Any
        // RuntimeException here (e.g. this exact port is already bound by
        // an unrelated process) propagates straight out to
        // createWithPortConflictRetry, which retries on the next attempt's
        // different candidate port.
        return builder.build();
    }

    /**
     * Best-effort verbose chromedriver log file path, scoped per active
     * site and per thread-slot so concurrent site JVMs/threads never share
     * one file (see the historical note this replaced, kept in git blame).
     * Returns null (no log file, but the driver service itself is
     * unaffected — see {@link #buildDriverService}) if the directory can't
     * be created.
     */
    private static File tryBuildLogFile() {
        try {
            String browser = ConfigReader.get("browser", "chrome").toLowerCase();
            File logDir = new File(System.getProperty("user.dir") + File.separator
                + "target" + File.separator + "logs" + File.separator + safeSiteName());
            if (!logDir.exists() && !logDir.mkdirs()) {
                logger.warn("[DriverFactory] Could not create log directory: " + logDir);
                return null;
            }
            File logFile = new File(logDir,
                "chromedriver-" + browser + "-thread-" + Thread.currentThread().getId() + ".log");
            logger.info("[DriverFactory] Verbose chromedriver log: " + logFile.getAbsolutePath());
            return logFile;
        } catch (Exception e) {
            logger.warn("[DriverFactory] Could not set up verbose chromedriver logging: "
                + e.getMessage());
            return null;
        }
    }

    private static String findBraveBinary() {
        // Allow explicit override via system property
        String override = System.getProperty("brave.binary");
        if (override != null && new File(override).exists()) {
            return override;
        }

        String[] candidates = {
            // Linux
            "/usr/bin/brave-browser",
            "/usr/bin/brave",
            "/opt/brave.com/brave/brave",
            // macOS
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            // Windows
            System.getenv("LOCALAPPDATA") != null
                ? System.getenv("LOCALAPPDATA") + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"
                : null,
            "C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "C:\\Program Files (x86)\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"
        };

        for (String path : candidates) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    /**
     * Creates (and registers for JVM-exit cleanup) a fresh, isolated
     * --user-data-dir for one driver-creation call. Called ONCE per
     * createChromeDriver()/createBraveDriver()/createEdgeDriver() invocation
     * — i.e. once per successful-or-exhausted retry loop, not once per
     * attempt — since a failed attempt (lost the port race) never actually
     * launches a browser against this directory, so there's nothing to
     * isolate it from on the next attempt. See the comment in
     * createChromeDriver() for why creating a fresh one per attempt was a
     * bug, not just wasteful.
     */
    private static java.nio.file.Path createIsolatedTempProfile() {
        try {
            java.nio.file.Path tempProfile =
                java.nio.file.Files.createTempDirectory("selenium-profile-");
            registerTempProfileCleanup(tempProfile);
            return tempProfile;
        } catch (java.io.IOException e) {
            logger.warn("[DriverFactory] Could not create temp profile dir: "
                + e.getMessage());
            return null;
        }
    }

    /** Shared ChromeOptions used by both Chrome and Brave */
    private static ChromeOptions buildChromeOptions(boolean headless, java.nio.file.Path tempProfile,
                                                    boolean forRemote) {
        ChromeOptions options = new ChromeOptions();

        // ROOT CAUSE FIX (browser window staying open after quit()):
        // No --user-data-dir was ever set, so Chrome/Brave launched against
        // the OS default profile. Chromium is single-instance-per-profile —
        // if a Brave window is already running on that profile, the process
        // ChromeDriver just launched hands off to the already-running
        // instance and exits immediately. ChromeDriver's session then has no
        // real process left to signal, so quit() can't kill the actual
        // browser and the window (and any others in that instance) stays
        // open. Giving every session an isolated temp profile guarantees a
        // genuinely new, independently-owned process that quit() can
        // actually terminate. The directory itself is created once by the
        // caller (createIsolatedTempProfile()) and reused across retry
        // attempts — see createChromeDriver().
        if (tempProfile != null) {
            options.addArguments("--user-data-dir=" + tempProfile.toAbsolutePath());
        }

        // Default (NORMAL) blocks driver.get() until the browser's full 'load'
        // event fires — on demoqa that means waiting for every ad/tracker script
        // too, not just the page's own content, which is what was making every
        // navigation take ~30s. EAGER returns once the DOM is parsed; our own
        // explicit waits (visibilityOfElementLocated etc.) already gate on the
        // specific elements each page actually needs before touching them.
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        // ROOT CAUSE (confirmed by isolating each ChromeDriver default switch
        // individually against the raw Brave binary): Brave's crashpad
        // startup self-check crashes (SIGTRAP, "elf_dynamic_array_reader.h:
        // tag not found") specifically when launched with the legacy
        // --test-type=webdriver switch. ChromeDriver injects this switch by
        // default on every session — it's not something we pass ourselves —
        // so it can only be removed via the excludeSwitches capability.
        // --enable-automation and --remote-debugging-port=0 were each tested
        // alone and do NOT trigger the crash, so only "test-type" is excluded.
        options.setExperimentalOption("excludeSwitches", java.util.List.of("test-type"));

        options.addArguments("--disable-notifications");
        options.addArguments("--disable-extensions");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-crash-reporter");
        options.addArguments("--disable-breakpad");
        options.addArguments("--noerrdialogs");

        String downloadPath = getDownloadPath();
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadPath);
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("safebrowsing.enabled", true);
        options.setExperimentalOption("prefs", prefs);

        if (forRemote) {
            // See the ROOT CAUSE FIX comment on buildRemoteOptions() in the
            // "chrome" provider above: the Grid node container already
            // provides its own virtual display + noVNC (:7900) for this
            // exact purpose. Forcing --headless=new here would render
            // nothing into that display, leaving noVNC streaming a blank
            // screen even though the session is genuinely live. A
            // maximized window inside the node's Xvfb is what noVNC is
            // actually built to show.
            options.addArguments("--start-maximized");
            if (headless) {
                logger.warn("[DriverFactory] headless=true was requested for a Grid (remote) session — "
                    + "ignoring it and launching a normal windowed session instead, so the node's "
                    + "noVNC viewer (:7900) actually shows the browser. Headless mode has no effect "
                    + "on Grid nodes anyway: they run their own Xvfb regardless, so this flag is only "
                    + "meaningful for local (non-Grid) runs.");
            }
        } else if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
        } else {
            options.addArguments("--start-maximized");
        }

        // Capture browser (JS console) logs so TestListener can attach them to
        // the Allure report on failure — invaluable for diagnosing JS errors
        // that caused a UI interaction to fail. Chromium-based only (Chrome,
        // Edge, Brave); Firefox/geckodriver has no equivalent W3C capability.
        org.openqa.selenium.logging.LoggingPreferences logPrefs =
            new org.openqa.selenium.logging.LoggingPreferences();
        logPrefs.enable(org.openqa.selenium.logging.LogType.BROWSER, java.util.logging.Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        return options;
    }

    // ── Firefox ───────────────────────────────────────────────────────────────

    private static WebDriver createFirefoxDriver(boolean headless) {
        // Apply WDM cache settings explicitly so drivers are not re-downloaded
        // on every run (pom.xml passes these as system properties).
        String wdmCache = System.getProperty("wdm.cachePath",
            System.getProperty("user.home") + "/.wdm");
        System.setProperty("wdm.cachePath", wdmCache);
        System.setProperty("wdm.forceDownload",
            System.getProperty("wdm.forceDownload", "false"));

        WebDriverManager.firefoxdriver().setup();

        String firefoxBinary = findFirefoxBinary();
        if (firefoxBinary != null) {
            logger.info("[DriverFactory] Using Firefox binary: " + firefoxBinary);
        } else {
            logger.info("[DriverFactory] Firefox binary not found on known paths — " +
                "geckodriver will search PATH. If tests hang, install Firefox or pass " +
                "-Dfirefox.binary=/path/to/firefox");
        }

        FirefoxOptions options = new FirefoxOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        if (firefoxBinary != null) {
            options.setBinary(firefoxBinary);
        }

        // Disable sandbox ONLY on CI — containers (Jenkins/GitLab agents)
        // often lack the Linux user-namespace permissions Firefox's sandbox
        // needs to initialize, which can hang or crash the browser without
        // this. Locally this is unnecessary and shows a security warning
        // banner, so it's gated behind common CI env vars rather than
        // applied unconditionally.
        boolean isCi = System.getenv("CI") != null
            || System.getenv("JENKINS_HOME") != null
            || System.getenv("GITLAB_CI") != null;
        if (isCi) {
            options.addPreference("security.sandbox.content.level", 0);
            options.addPreference("security.sandbox.gpu.level", 0);
            options.addPreference("security.sandbox.media.level", 0);
        }

        // Download preferences
        options.addPreference("browser.download.folderList", 2);
        options.addPreference("browser.download.dir", getDownloadPath());
        options.addPreference("browser.helperApps.neverAsk.saveToDisk",
            "application/octet-stream,application/zip,text/csv");

        if (headless) {
            options.addArguments("-headless");
            options.addArguments("--width=1920");
            options.addArguments("--height=1080");
        }

        // BUG FIX: unlike Chrome/Brave/Edge, Firefox was never routed
        // through createWithPortConflictRetry() at all — `new
        // FirefoxDriver(options)` builds its own default GeckoDriverService
        // internally, which hits the exact same PortProber TOCTOU race (see
        // the notes above createWithPortConflictRetry()), just with no
        // retry and no deterministic port to avoid it. Under
        // -DALL_BROWSERS, where chrome/firefox/edge all launch concurrently
        // per site, Firefox was the one browser that could still fail
        // outright on this race with zero recovery. Given an explicit
        // GeckoDriverService + the same retry loop the other browsers use.
        FirefoxDriver driver = (FirefoxDriver) createWithPortConflictRetry("firefox", attempt -> {
            org.openqa.selenium.firefox.GeckoDriverService service =
                new org.openqa.selenium.firefox.GeckoDriverService.Builder()
                    .usingPort(candidatePort(attempt))
                    .build();
            return new FirefoxDriver(service, options);
        });

        // Firefox has no launch-time equivalent to Chrome/Edge's
        // --start-maximized argument, so the window opens at Firefox's
        // default size unless maximized after the fact.
        if (!headless) {
            driver.manage().window().maximize();
        }

        return driver;
    }

    private static String findFirefoxBinary() {
        // 1. Explicit -Dfirefox.binary=... CLI/pom override
        String override = System.getProperty("firefox.binary");
        if (override != null && !override.trim().isEmpty() && new File(override).exists()) {
            return override;
        }

        // 2. Probe common install paths (order matters — most common first per OS)
        String[] candidates = {
            // Ubuntu 22+/24 Snap install (most common on modern Ubuntu)
            "/snap/bin/firefox",
            // Ubuntu/Debian apt install
            "/usr/bin/firefox",
            // Older Ubuntu/Fedora
            "/usr/lib/firefox/firefox",
            "/usr/lib64/firefox/firefox",
            // Fedora/RHEL flatpak
            "/var/lib/flatpak/exports/bin/org.mozilla.firefox",
            // macOS
            "/Applications/Firefox.app/Contents/MacOS/firefox",
            "/Applications/Firefox Developer Edition.app/Contents/MacOS/firefox",
            "/Applications/Firefox Nightly.app/Contents/MacOS/firefox",
            // Windows
            System.getenv("PROGRAMFILES") != null
                ? System.getenv("PROGRAMFILES") + "\\Mozilla Firefox\\firefox.exe" : null,
            System.getenv("PROGRAMFILES(X86)") != null
                ? System.getenv("PROGRAMFILES(X86)") + "\\Mozilla Firefox\\firefox.exe" : null,
        };

        for (String path : candidates) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }

        // 3. Try finding firefox on PATH via which/where command
        //
        // Process streams/handles are explicitly closed and the process is
        // waited on and destroyed — leaving them open leaks file descriptors
        // over a long test run. "where" on Windows can also print more than
        // one match (one per line), so only the first line is taken instead
        // of treating the whole (possibly multi-line) output as a single path.
        try {
            String whichCmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "where firefox" : "which firefox";
            Process p = Runtime.getRuntime().exec(whichCmd);
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            } finally {
                p.waitFor();
                p.destroy();
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            if (!firstLine.isEmpty() && new File(firstLine).exists()) {
                return firstLine;
            }
        } catch (Exception ignored) {
            // fall through — geckodriver will search PATH itself
        }

        return null;  // geckodriver will attempt to find Firefox itself
    }

    // ── Edge ──────────────────────────────────────────────────────────────────

    private static WebDriver createEdgeDriver(boolean headless) {
        WebDriverManager.edgedriver().setup();

        // BUG FIX: unlike createChromeDriver()/createBraveDriver(), this
        // method never set --user-data-dir, so Edge sessions launched
        // against the shared default profile — the exact
        // "browser window staying open after quit()" bug already
        // documented and fixed for Chrome/Brave in buildChromeOptions(),
        // just missed here. Same fix: an isolated temp profile per call,
        // created once and reused across retry attempts.
        java.nio.file.Path tempProfile = createIsolatedTempProfile();

        // Options are (re)built fresh per attempt inside the retry lambda —
        // EdgeDriverService hits the exact same PortProber race as
        // ChromeDriverService (both are Selenium DriverService subclasses),
        // so it gets the same explicit-deterministic-port fix — see
        // candidatePort() / createWithPortConflictRetry() above
        // createChromeDriver().
        return createWithPortConflictRetry("edge", attempt -> {
            EdgeOptions options = new EdgeOptions();
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);
            options.addArguments("--disable-notifications");
            options.addArguments("--disable-extensions");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--remote-allow-origins=*");
            if (tempProfile != null) {
                options.addArguments("--user-data-dir=" + tempProfile.toAbsolutePath());
            }

            String downloadPath = getDownloadPath();
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("download.default_directory", downloadPath);
            prefs.put("download.prompt_for_download", false);
            prefs.put("download.directory_upgrade", true);
            options.setExperimentalOption("prefs", prefs);

            if (headless) {
                options.addArguments("--headless=new");
                options.addArguments("--window-size=1920,1080");
            } else {
                options.addArguments("--start-maximized");
            }

            EdgeDriverService service = new EdgeDriverService.Builder()
                .usingPort(candidatePort(attempt))
                .build();
            CURRENT_DRIVER_SERVICE.set(service);
            return new EdgeDriver(service, options);
        });
    }

    // ── Safari ────────────────────────────────────────────────────────────────

    /**
     * Launches a local Safari session via the OS-provided safaridriver.
     * <p>
     * Deliberately much smaller than createChromeDriver()/createEdgeDriver():
     * SafariOptions has no equivalent of --headless, --disable-notifications,
     * --user-data-dir, --no-sandbox, or a download.default_directory prefs
     * map — WebKit's automation surface just doesn't expose those knobs, so
     * there is nothing to port over from the Chromium-based browsers here,
     * not an oversight. Downloaded files land in the signed-in user's real
     * ~/Downloads (there is no per-session isolation the way
     * {@link #getDownloadPath()} gives Chrome/Brave/Edge); a suite that
     * asserts on a specific download path is not portable to Safari as-is.
     * <p>
     * No WebDriverManager.safaridriver().setup() call — safaridriver ships
     * inside macOS itself (there's no separate binary to download/version-
     * match), and WebDriverManager has no such method. It must already be
     * enabled once on the host via {@code safaridriver --enable} — this
     * method deliberately does not try to run that itself (it needs sudo).
     */
    private static WebDriver createSafariDriver(boolean headless) {
        if (headless) {
            // See the class javadoc — Safari has no headless mode at all.
            // Failing the whole run over a flag every other supported
            // browser silently accepts would be surprising for a suite
            // that runs the same -Dheadless=true across a browser matrix;
            // warn and launch a normal windowed session instead.
            logger.warn("[DriverFactory] headless=true was requested for safari, but Safari has no"
                + " headless mode — launching a normal windowed session instead.");
        }

        SafariOptions options = new SafariOptions();

        // Reuses the exact same explicit-deterministic-port retry
        // machinery as Chrome/Edge above (candidatePort() /
        // createWithPortConflictRetry()) purely for consistency and to
        // absorb an unrelated process already sitting on the computed
        // port. It is NOT what makes concurrent Safari sessions safe —
        // see the class javadoc: only one SafariDriver session may exist
        // on the machine at all, a constraint this retry loop cannot fix
        // and does not attempt to. Callers must run Safari suites with
        // parallel="none".
        return createWithPortConflictRetry("safari", attempt -> {
            SafariDriverService service = new SafariDriverService.Builder()
                .usingPort(candidatePort(attempt))
                .build();
            CURRENT_DRIVER_SERVICE.set(service);
            return new SafariDriver(service, options);
        });
    }

    // ── Shared ────────────────────────────────────────────────────────────────

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

    /**
     * Central replacement for calling {@code driver.quit()} directly.
     * Behaves identically to a normal quit() on the (overwhelmingly common)
     * happy path, but on failure — instead of just letting the exception
     * propagate for the caller to log and move on from, as
     * {@link com.automation.sites.core.BaseTest#tearDown()} used to do —
     * forcibly kills the underlying chromedriver/browser process tree so it
     * cannot linger and consume memory for the remainder of the run. See the
     * class-level comment on {@link #CURRENT_DRIVER_SERVICE} for the full
     * root-cause history. Safe to call with a null/already-quit driver.
     */
    public static void quitDriver(WebDriver driver) {
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
    public static void forceKillOrphanedDriverProcess(WebDriver driver) {
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

    private static synchronized void registerTempProfileCleanup(java.nio.file.Path tempProfile) {
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

    private static void deleteRecursively(File file) {
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

    /**
     * SCALABILITY: isolated per-thread download directory.
     * <p>
     * Previously this returned the single shared path {@code target/downloads}
     * for every session — harmless back when suites only ever ran
     * parallel="none", but now that testng-suites/*.xml run
     * parallel="classes" thread-count="3" (multiple concurrent browser
     * sessions, one per thread), every one of those sessions was still being
     * told to save downloads into the exact same folder. Two classes that
     * happen to download a file with the same name at the same time (today:
     * only {@code UploadDownloadTest} downloads anything, but that stops
     * being true the moment a second download test is added, or this class
     * is copied for another site) would race on that shared file, and a
     * stale leftover from one thread's earlier run could make another
     * thread's freshly-started download check pass before its own file
     * actually landed. Same class of problem — and same fix — as the
     * per-session {@code --user-data-dir} temp profile isolation above:
     * give each thread (i.e. each concurrent WebDriver session) its own
     * subdirectory instead of sharing one.
     * <p>
     * Public (not private) so callers that need to know where THIS
     * thread's browser is actually configured to download to — e.g.
     * {@code UploadDownloadTest} asserting a download landed — can call the
     * exact same method DriverFactory itself uses when building browser
     * options, instead of recomputing the path a second time and risking
     * the two copies drifting apart.
     * <p>
     * BUG FIX: this path was scoped by thread ID only, not by site — the
     * exact same collision class already identified and fixed for the
     * verbose chromedriver log path (see {@link #tryBuildLogFile}):
     * Jenkins' "Run Tests Per Site" stage runs demoqa and saucedemo as two
     * separate {@code mvn test} JVMs sharing the same workspace, so each
     * JVM's own thread IDs restart independently from 1 — both branches'
     * thread-1 would resolve to the identical
     * {@code target/downloads/thread-1} directory. No test happens to hit
     * this today (only one site's suite currently downloads a file), but
     * that's incidental, not structural, so it's scoped by site here too
     * rather than left as a landmine for the next download test added to a
     * second site.
     */
    public static String getDownloadPath() {
        String path = System.getProperty("user.dir")
            + File.separator + "target"
            + File.separator + "downloads"
            + File.separator + safeSiteName()
            + File.separator + "thread-" + Thread.currentThread().getId();
        new File(path).mkdirs();
        return path;
    }
}
