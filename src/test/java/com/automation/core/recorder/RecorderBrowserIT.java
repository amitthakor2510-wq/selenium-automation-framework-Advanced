package com.automation.core.recorder;

import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Browser test for the recorder: a scripted user works the local fixture site, the recording is written
 * out, replayed in a second browser, and finally shown to self-heal on a drifted page. See
 * {@link RecorderScenario} for what is checked.
 *
 * <p>Not part of any suite or of the browser-free unit profile. Run it on demand, headless:
 * <pre>mvn test -Dtest=RecorderBrowserIT -Dsite=saucedemo -Dheadless=true</pre>
 * It needs only a local Chrome/Chromium; it opens no external site and keeps its own self-healing store
 * under target/, so it never touches self-healing-data/.
 */
public class RecorderBrowserIT {

    /**
     * Restored after the test. The two self-healing paths are deliberately NOT in this list: LocatorRepository
     * flushes from a JVM shutdown hook and resolves its file path at that moment, so clearing them here would
     * redirect the flush of this test's fingerprints into the project's real self-healing-data/ store.
     */
    private static final String[] OWN_PROPERTIES = {"url", "human.pause.enabled"};

    private FixtureServer fixture;
    private Path work;

    @BeforeClass
    public void startFixtureAndIsolateState() throws IOException {
        work = Files.createDirectories(Paths.get("target", "recorder-it"));
        fixture = FixtureServer.start();
        System.setProperty("url", fixture.baseUrl());
        System.setProperty("human.pause.enabled", "false");
        System.setProperty("self-healing.repository.path", work.resolve("locator-repository.json").toString());
        System.setProperty("self-healing.report.path", work.resolve("healing-report.json").toString());
        ConfigReader.init();
    }

    @AfterClass(alwaysRun = true)
    public void stopFixtureAndRestoreState() {
        if (fixture != null) {
            fixture.close();
        }
        for (String property : OWN_PROPERTIES) {
            System.clearProperty(property);
        }
        ConfigReader.reset();
    }

    @Test(groups = "recorder-it", description = "Record a scripted session, replay it, and prove its self-healing baselines work")
    public void recordedSessionReplaysAndSelfHeals() throws IOException {
        Path run = Files.createTempDirectory(work, "run-");
        WebDriver recording = DriverFactory.createDriver();
        RecorderScenario scenario;
        RecorderScenario.Result result;
        try {
            scenario = new RecorderScenario(recording, fixture.baseUrl(), run);
            result = scenario.record();
        } finally {
            DriverFactory.quitDriver(recording);
        }
        WebDriver replay = DriverFactory.createDriver();
        try {
            scenario.replay(result, replay);
            scenario.assertDriftNeedsBaseline(result, replay);
            scenario.verifyHealingBaseline(result, replay);
        } finally {
            DriverFactory.quitDriver(replay);
        }
    }
}
