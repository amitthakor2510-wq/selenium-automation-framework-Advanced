package com.automation.core.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.FrameworkException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One shared, in-process WireMock server per JVM — lets API tests run offline, and lets a test
 * force error states (500s, slow responses, flaky-then-recovering endpoints) that a real backend
 * will never produce on demand.
 *
 * <p>Everything is opt-in and off by default, so a plain {@code mvn test} behaves exactly as it
 * did before this class existed:
 * <pre>
 *   -Dmock.enabled=true        ApiConfig.baseUri() returns this server's URL instead of the real
 *                              site's, so every existing API test class is mocked with zero edits
 *   -Dmock.record=true         (with mock.enabled=true) proxy to the real site and save what comes
 *                              back into src/test/resources/wiremock/{site}/ instead of replaying
 *   -Dmock.record.target=URL   real site to record from (default: the site's own "url")
 *   -Dmock.record.method=GET   which HTTP method to record (default GET, or ANY)
 *   -Dmock.record.urlPattern=  regex on the URL PATH to record (default ".*")
 * </pre>
 *
 * <p><b>Where stubs come from.</b> In playback, WireMock loads {@code mappings/} and
 * {@code __files/} from {@code src/test/resources/wiremock/{site}/} (or the same folder on the
 * classpath when the sources aren't on disk, e.g. inside a Docker image). On top of that, a site
 * may register a stateful <i>fake</i> as a low-priority fallback — see {@link DemoQaBookStoreFake}
 * and {@link #installFallbacks}. Recorded stubs always win over a fake (priority 5 vs 10), so
 * recording the real catalogue silently replaces the fake's hand-written one.
 *
 * <p><b>Why a fake and not just recordings.</b> A recording replays one fixed answer per request.
 * That is right for reads (a book catalogue) but wrong for a flow like "create account, add book,
 * see book on the account, delete", where each answer depends on the calls before it and the IDs
 * are new on every run. Record the stateless reads; let the fake own the stateful writes.
 *
 * <p><b>Limits worth knowing.</b>
 * <ul>
 *   <li>This mocks the HTTP calls made by {@code ApiClient}/RestAssured only. A browser under
 *       Selenium still talks to the real site — WireMock is not in that path.</li>
 *   <li>The server is shared by every test class in the JVM (parallel="classes" included). A test
 *       that adds its own stubs should use a URL no other class touches, and must not call
 *       {@link #reset()} while other classes are running.</li>
 * </ul>
 */
public final class WireMockManager {

    private static final Logger logger = LoggerFactory.getLogger(WireMockManager.class);
    private static final Object LOCK = new Object();

    private static WireMockServer server;
    private static DemoQaBookStoreFake fake;
    private static boolean recording;
    private static boolean shutdownHookRegistered;
    private static String activeSite;

    private WireMockManager() {
    }

    /** True when {@code -Dmock.enabled=true}. */
    public static boolean isEnabled() {
        return ConfigReader.getBoolean("mock.enabled", false);
    }

    /** True while this JVM's server is proxying to the real site and recording what it sees. */
    public static boolean isRecording() {
        synchronized (LOCK) {
            return recording;
        }
    }

    /** Starts the shared server if needed (idempotent) and returns its base URL. */
    public static String start() {
        return baseUrl(ensureStarted());
    }

    /**
     * The running server, started on first use — for tests that want to add their own stubs
     * ({@code WireMockManager.server().stubFor(...)}).
     */
    public static WireMockServer server() {
        return ensureStarted();
    }

    /**
     * Drops every stub added at run time and returns the server to its as-loaded state: the
     * file-based stubs plus the site's fallback fake with all of its data wiped.
     * Not safe to call while other test classes are mid-run against the same server.
     */
    public static void reset() {
        synchronized (LOCK) {
            if (server == null || !server.isRunning()) {
                return;
            }
            server.resetToDefaultMappings();
            if (fake != null) {
                fake.reset();
            }
            installFallbacks(server, activeSite, recording);
        }
    }

    /** Stops the server; when recording, this is what writes the captured stubs to disk. */
    public static void stop() {
        synchronized (LOCK) {
            if (server == null) {
                return;
            }
            try {
                if (recording && server.isRunning()) {
                    int saved = server.stopRecording().getStubMappings().size();
                    logger.info("[WireMock] Recording stopped — {} stub(s) saved under {}",
                        saved, stubsDirectory(activeSite));
                }
                if (server.isRunning()) {
                    server.stop();
                }
            } finally {
                server = null;
                fake = null;
                recording = false;
            }
        }
    }

    /** {@code src/test/resources/wiremock/{site}} — where recorded stubs are written and read. */
    public static Path stubsDirectory(String site) {
        return Path.of(System.getProperty("user.dir"), "src", "test", "resources", "wiremock", site);
    }

    // ------------------------------------------------------------------

    private static WireMockServer ensureStarted() {
        synchronized (LOCK) {
            if (server != null && server.isRunning()) {
                return server;
            }

            String site = System.getProperty("site", "demoqa");
            boolean wantRecord = ConfigReader.getBoolean("mock.record", false);

            DemoQaBookStoreFake newFake = new DemoQaBookStoreFake();
            WireMockConfiguration config = WireMockConfiguration.options()
                .dynamicPort()
                .bindAddress("127.0.0.1")
                .maxRequestJournalEntries(2000)
                .extensions(newFake);
            config = withStubSource(config, site, wantRecord);

            WireMockServer newServer = new WireMockServer(config);
            newServer.start();

            server = newServer;
            fake = newFake;
            activeSite = site;
            recording = false;

            if (wantRecord) {
                startRecording(newServer, site);
            } else {
                installFallbacks(newServer, site, false);
            }

            if (!shutdownHookRegistered) {
                // stop() is what flushes a recording to disk, so make sure it runs even if the
                // suite never calls it explicitly.
                Runtime.getRuntime().addShutdownHook(new Thread(WireMockManager::stop, "wiremock-shutdown"));
                shutdownHookRegistered = true;
            }
            logger.info("[WireMock] Listening on {} (site={}, mode={})",
                baseUrl(newServer), site, wantRecord ? "RECORD" : "PLAYBACK");
            return newServer;
        }
    }

    private static WireMockConfiguration withStubSource(WireMockConfiguration config, String site,
                                                        boolean record) {
        Path dir = stubsDirectory(site);
        if (record) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new FrameworkException("Cannot create WireMock recording folder " + dir, e);
            }
            return config.usingFilesUnderDirectory(dir.toString());
        }
        if (Files.isDirectory(dir)) {
            return config.usingFilesUnderDirectory(dir.toString());
        }
        String classpathDir = "wiremock/" + site;
        if (WireMockManager.class.getClassLoader().getResource(classpathDir) != null) {
            return config.usingFilesUnderClasspath(classpathDir);
        }
        try {
            // No stubs for this site anywhere — an empty scratch folder keeps WireMock happy, and
            // any fake registered for the site below is still available.
            return config.usingFilesUnderDirectory(Files.createTempDirectory("wiremock-empty-").toString());
        } catch (IOException e) {
            throw new FrameworkException("Cannot create scratch folder for WireMock", e);
        }
    }

    private static void startRecording(WireMockServer target, String site) {
        String realUrl = ConfigReader.getNonBlank("mock.record.target", ConfigReader.get("url"));
        String method = ConfigReader.get("mock.record.method", "GET").trim();
        String pathRegex = ConfigReader.getNonBlank("mock.record.urlPattern", ".*");

        RequestMethod requestMethod = "ANY".equalsIgnoreCase(method)
            ? RequestMethod.ANY : RequestMethod.fromString(method.toUpperCase());

        target.startRecording(recordSpec()
            .forTarget(realUrl)
            .onlyRequestsMatching(RequestPatternBuilder.newRequestPattern(requestMethod, urlPathMatching(pathRegex)))
            .ignoreRepeatRequests()
            .makeStubsPersistent(true));
        recording = true;
        logger.info("[WireMock] Recording {} {} from {} into {}", method, pathRegex, realUrl, stubsDirectory(site));
    }

    /**
     * Site-specific low-priority fallbacks. Add a new site's fake here: register it in
     * {@link #ensureStarted()}'s {@code extensions(...)} and route unmatched requests to it below.
     */
    private static void installFallbacks(WireMockServer target, String site, boolean isRecording) {
        if (isRecording) {
            return; // the recording proxy must see every request; a fake would swallow them
        }
        if ("demoqa".equals(site)) {
            target.stubFor(any(anyUrl())
                .atPriority(10)
                .willReturn(aResponse().withTransformers(DemoQaBookStoreFake.NAME)));
        }
    }

    private static String baseUrl(WireMockServer running) {
        return "http://127.0.0.1:" + running.port();
    }
}
