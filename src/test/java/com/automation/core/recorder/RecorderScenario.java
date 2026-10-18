package com.automation.core.recorder;

import com.automation.core.keyword.KeywordEngine;
import com.automation.core.keyword.KeywordReader;
import com.automation.core.keyword.ObjectRepository;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * End-to-end scenario shared by the TestNG browser test and the offline harness: a scripted "user"
 * operates the fixture site while a {@link RecorderSession} watches, the recording is turned into a
 * keyword script, and that script is replayed in a brand-new browser. If anything the recorder
 * missed or got wrong mattered, the replay (or one of the checks) fails.
 */
public final class RecorderScenario {

    public static final String TEST_CASE = "TC_Recorded_Lab";
    private static final String SECRET = "s3cret-Pass!";

    /** What the scenario produced, for callers that want to inspect it. */
    public static final class Result {
        public RecordedFlow flow;
        public Path script;
        public Path repository;
        public String scriptText;
        public String repositoryText;
    }

    private final WebDriver recordDriver;
    private final String baseUrl;
    private final Path workDir;
    private final Path uploadFile;
    private RecorderSession session;

    public RecorderScenario(WebDriver recordDriver, String baseUrl, Path workDir) throws IOException {
        this.recordDriver = recordDriver;
        this.baseUrl = baseUrl;
        this.workDir = workDir.toAbsolutePath();
        Files.createDirectories(this.workDir);
        this.uploadFile = this.workDir.resolve("note.txt");
        Files.writeString(uploadFile, "recorded upload", StandardCharsets.UTF_8);
    }

    /** Records the scripted session, writes the files, replays them with {@code replayDriver}, and checks the outcome. */
    public Result run(WebDriver replayDriver) throws IOException {
        Result result = record();
        replay(result, replayDriver);
        return result;
    }

    /**
     * First half of {@link #run}: records the session, writes the script and repository, and checks the
     * quality of what was recorded. Separate so a caller that can only hold one driver per thread can
     * quit the recording driver before it opens the replay driver.
     */
    public Result record() throws IOException {
        List<RecordedEvent> events = recordSession();
        Result result = new Result();
        StepBuilder builder = new StepBuilder("lab", baseUrl, true, Map.of());
        result.flow = builder.build(events);
        check(!result.flow.steps.isEmpty(), "the recording produced no steps");

        result.script = workDir.resolve("lab_recorded.csv");
        result.repository = workDir.resolve("lab.properties");
        ScriptWriter.writeScript(result.script, TEST_CASE, result.flow.steps, false);
        ScriptWriter.appendRepository(result.repository, TEST_CASE, result.flow.locators.values());
        result.scriptText = Files.readString(result.script, StandardCharsets.UTF_8);
        result.repositoryText = Files.readString(result.repository, StandardCharsets.ISO_8859_1);

        assertRecordingQuality(result);
        return result;
    }

    // ------------------------------------------------------------------ recording

    private List<RecordedEvent> recordSession() {
        session = new RecorderSession(recordDriver);
        session.start(baseUrl + "/index.html");
        WebDriver d = recordDriver;

        // text, password, select, checkbox and a label-wrapped radio
        click(By.id("username"));
        type(By.id("username"), "amit");
        click(By.id("password"));
        type(By.id("password"), SECRET);
        // Selenium's Select helper fires an untrusted change event; typing on the focused select is what a user does
        d.findElement(By.id("color")).sendKeys("Green");
        poll();
        click(By.id("agree"));
        click(By.cssSelector("label[for='rYes']"));

        // a button whose id changes on every load: the recorder must pick the stable data-testid
        click(By.cssSelector("[data-testid='save-button']"));

        // hover mode, then a menu item that only exists while hovering
        toolbar(3);
        new Actions(d).moveToElement(d.findElement(By.id("menu"))).pause(Duration.ofMillis(1100)).perform();
        poll();
        click(By.id("subItem"));
        toolbar(3);

        // a verification recorded through the toolbar
        toolbar(1);
        click(By.id("result"));

        new Actions(d).doubleClick(d.findElement(By.id("dbl"))).perform();
        poll();
        new Actions(d).contextClick(d.findElement(By.id("ctx"))).perform();
        poll();

        // a JavaScript alert
        click(By.id("alertBtn"));
        poll();

        // file upload and an element inside an iframe
        d.findElement(By.id("file")).sendKeys(uploadFile.toString());
        poll();
        d.switchTo().frame(d.findElement(By.id("frame1")));
        click(By.id("inner"));
        type(By.id("inner"), "inframe");
        click(By.id("innerBtn"));
        d.switchTo().defaultContent();
        poll();

        // a popup window, work inside it, and close it again
        click(By.id("toPopup"));
        sleep(400);
        poll();
        poll();
        click(By.id("popupInput"));
        type(By.id("popupInput"), "from-popup");
        click(By.id("popupBtn"));
        poll();
        d.close();
        poll();
        poll();

        // a navigation caused by a click
        click(By.id("toPage2"));
        sleep(400);
        poll();
        poll();
        return new ArrayList<>(session.finish());
    }

    private void click(By by) {
        recordDriver.findElement(by).click();
        poll();
    }

    private void type(By by, String text) {
        recordDriver.findElement(by).sendKeys(text);
        poll();
    }

    private void toolbar(int buttonIndex) {
        recordDriver.findElement(By.cssSelector("#__kdt_toolbar button:nth-of-type(" + buttonIndex + ")")).click();
        poll();
    }

    private void poll() {
        session.pollOnce();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------------------- assertions

    private static void assertRecordingQuality(Result r) {
        List<String> keywords = r.flow.steps.stream().map(s -> s.keyword).collect(Collectors.toList());
        for (String expected : List.of("NAVIGATE", "TYPE", "SELECT_BY_TEXT", "CHECK", "CLICK", "HOVER", "VERIFY_TEXT",
            "DOUBLE_CLICK", "RIGHT_CLICK", "ACCEPT_ALERT", "UPLOAD_FILE", "SWITCH_TO_FRAME", "SWITCH_TO_DEFAULT_CONTENT",
            "SWITCH_TO_WINDOW", "CLOSE_WINDOW", "WAIT_FOR_PAGE_LOAD", "VERIFY_URL_CONTAINS")) {
            check(keywords.contains(expected), "recording is missing a " + expected + " step. Steps: " + r.flow.steps);
        }
        check(!r.scriptText.contains(SECRET), "the typed password leaked into the keyword script");
        check(!r.repositoryText.contains(SECRET), "the typed password leaked into the object repository");
        check(r.scriptText.contains("${env:"), "the password was not replaced by an ${env:...} placeholder");
        check(!r.flow.secrets.isEmpty() && r.flow.secrets.containsValue(SECRET), "the secret was not kept for the replay");

        long doubleClicks = keywords.stream().filter("DOUBLE_CLICK"::equals).count();
        check(doubleClicks == 1, "expected exactly one DOUBLE_CLICK, got " + doubleClicks);
        RecordedStep dbl = r.flow.steps.stream().filter(s -> s.keyword.equals("DOUBLE_CLICK")).findFirst().orElseThrow();
        check(r.flow.steps.stream().noneMatch(s -> s.keyword.equals("CLICK") && s.locatorKey.equals(dbl.locatorKey)),
            "the two clicks of the double click were not merged away");

        // the dynamic id must not be the primary locator of the Save button
        String save = r.flow.locators.values().stream()
            .filter(s -> s.primary().value.contains("save-button")).map(s -> s.primary().toLocatorString())
            .findFirst().orElse(null);
        check(save != null, "the Save button was not located through its data-testid. Locators: " + r.flow.locators.keySet());
        check(r.flow.locators.values().stream().noneMatch(s -> s.primary().type.equals("id") && s.primary().value.startsWith("btn-")),
            "a generated id (btn-NNNNNN-x) was used as a primary locator");

        // the radio is hidden, so it must have been recorded as a click on its visible label
        check(r.flow.steps.stream().anyMatch(s -> s.keyword.equals("CLICK") && s.description.contains("Yes please")),
            "the label-wrapped radio was not recorded as a CLICK on its label");
        check(r.flow.steps.stream().noneMatch(s -> s.keyword.equals("CHECK") && s.locatorKey.contains("rYes")),
            "the hidden radio input was recorded directly");
    }

    // -------------------------------------------------------------------- replay

    /** Second half of {@link #run}: plays the recorded script back in {@code replayDriver} and checks where it ends. */
    public void replay(Result r, WebDriver replayDriver) throws IOException {
        // the upload path in the script is relative to the project root; point it at the file this scenario made
        String rewritten = r.scriptText.replace("src/test/resources/testdata/uploads/", workDir.toString() + "/");
        Path runnable = workDir.resolve("lab_replay.csv");
        Files.writeString(runnable, rewritten, StandardCharsets.UTF_8);

        Collection<String> names = r.flow.secrets.keySet();
        for (Map.Entry<String, String> secret : r.flow.secrets.entrySet()) {
            System.setProperty(secret.getKey(), secret.getValue());
        }
        try {
            ObjectRepository repository = ObjectRepository.fromFile(r.repository);
            new KeywordEngine(replayDriver, repository).run(KeywordReader.readTestCase(runnable.toString(), TEST_CASE));
        } finally {
            names.forEach(System::clearProperty);
        }
        WebElement heading = replayDriver.findElement(By.id("heading"));
        check("Page Two".equals(heading.getText()), "the replay did not end on page two");
    }

    /**
     * Negative control for {@link #verifyHealingBaseline}: the same drifted page with primary-only locators but
     * NO seeded baseline must fail. Run it before seeding, otherwise it would find the baseline and prove nothing.
     */
    public void assertDriftNeedsBaseline(Result r, WebDriver driver) throws IOException {
        ObjectRepository repository = primaryOnlyRepository(r);
        try {
            runDrift(repository, driver);
        } catch (RuntimeException e) {
            return;
        }
        throw new AssertionError("[RecorderScenario] the drifted page was found without any baseline, so the "
            + "healing-baseline check would prove nothing");
    }

    /**
     * Proves the recorded baselines work: seeds them the way RecorderCli does, drifts the page (the Alert
     * button's id changes) and replays with primary-only locators, so only self-healing can find the button.
     */
    public void verifyHealingBaseline(Result r, WebDriver driver) throws IOException {
        ObjectRepository repository = primaryOnlyRepository(r);
        int seeded = FingerprintSeeder.seed(repository, r.flow.locators.values());
        check(seeded > 0, "no self-healing baselines were seeded");
        runDrift(repository, driver);
        check(!driver.findElements(By.id("alert-trigger")).isEmpty(), "the drifted page was not the one tested");
    }

    private ObjectRepository primaryOnlyRepository(Result r) throws IOException {
        Path primaryOnly = workDir.resolve("lab_primary_only.properties");
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(r.repository, StandardCharsets.ISO_8859_1)) {
            if (!line.contains(".fallback.")) {
                kept.add(line);
            }
        }
        Files.write(primaryOnly, kept, StandardCharsets.ISO_8859_1);
        return ObjectRepository.fromFile(primaryOnly);
    }

    private void runDrift(ObjectRepository repository, WebDriver driver) throws IOException {
        Path drift = workDir.resolve("lab_drift.csv");
        Files.writeString(drift, ScriptWriter.CSV_HEADER + "\n"
            + "Drift,1,NAVIGATE,,/index.html?drift=1,,\n"
            + "Drift,2,CLICK,lab.index.alertBtn,,,\n"
            + "Drift,3,ACCEPT_ALERT,,,,\n", StandardCharsets.UTF_8);
        new KeywordEngine(driver, repository).run(KeywordReader.readTestCase(drift.toString(), "Drift"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("[RecorderScenario] " + message);
        }
    }

    /** Convenience for the harness: prints the generated script and repository. */
    public static String describe(Result r) {
        return "---- keyword script ----\n" + r.scriptText + "\n---- object repository ----\n" + r.repositoryText;
    }
}
