package com.automation.core.recorder;

import com.automation.core.config.ConfigReader;
import com.automation.core.driver.DriverFactory;
import com.automation.core.keyword.KeywordEngine;
import com.automation.core.keyword.KeywordReader;
import com.automation.core.keyword.ObjectRepository;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Record a browser session into a keyword-driven test.
 *
 * <p>Opens a visible browser at the site, records what you do, and on Stop writes the keyword rows
 * and object-repository entries, seeds self-healing baselines, creates the site's recorded-test runner
 * once, and replays the new test case in a fresh browser to prove it works.
 *
 * <pre>
 * mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.automation.core.recorder.RecorderCli \
 *     -Dsite=saucedemo -Dexec.args="--name TC_Login --url /"
 * </pre>
 *
 * <p>Options: {@code --name} (required), {@code --url} (path or absolute URL, default the site root),
 * {@code --script}, {@code --repo}, {@code --prefix}, {@code --overwrite}, {@code --no-replay},
 * {@code --no-auto-assert}, {@code --max-minutes}. Exit codes: 0 recorded (and replayed if enabled),
 * 1 recorded but the replay failed, 2 nothing was recorded or the arguments were wrong.
 */
public final class RecorderCli {

    private static final long POLL_MILLIS = 200;

    private String name;
    private String site;
    private String url = "";
    private String script;
    private String repo;
    private String prefix;
    private boolean overwrite;
    private boolean replay = true;
    private boolean autoAssert = true;
    private long maxMinutes = 30;

    private RecorderCli() {
    }

    public static void main(String[] args) {
        System.exit(new RecorderCli().run(args));
    }

    private int run(String[] args) {
        try {
            if (!parse(args)) {
                return 2;
            }
            return record();
        } catch (IOException | RuntimeException e) {
            say("Recorder failed: " + e.getMessage());
            return 2;
        }
    }

    private int record() throws IOException {
        if (site != null) {
            System.setProperty("site", site);
        }
        ConfigReader.init();
        String activeSite = ConfigReader.getActiveSite();
        String baseUrl = ConfigReader.get("url");
        if (prefix == null) {
            prefix = activeSite.toLowerCase(Locale.ROOT);
        }
        Path root = Paths.get("").toAbsolutePath();
        Path csv = root.resolve(script != null ? script : "src/test/resources/testdata/keyword/" + activeSite + "_recorded_keywords.csv");
        String repoRelative = repo != null ? repo : "src/test/resources/objectrepository/" + activeSite + ".properties";
        Path repository = root.resolve(repoRelative);

        if (!overwrite && ScriptWriter.existingTestCases(csv).contains(name)) {
            say("Test case '" + name + "' already exists in " + csv + ". Pick another --name or pass --overwrite.");
            return 2;
        }
        Map<String, String> existing = ScriptWriter.readRepository(repository);

        List<RecordedEvent> events = recordInBrowser(startUrl(baseUrl));
        RecordedFlow flow = new StepBuilder(prefix, baseUrl, autoAssert, existing).build(events);
        if (flow.steps.isEmpty()) {
            say("Nothing was recorded.");
            return 2;
        }

        ScriptWriter.writeScript(csv, name, flow.steps, overwrite);
        int added = ScriptWriter.appendRepository(repository, name, flow.locators.values());
        int seeded = FingerprintSeeder.seed(ObjectRepository.fromFile(repository), flow.locators.values());
        List<Path> scaffold = RunnerScaffold.ensure(root, activeSite, objectRepositoryResource(repoRelative), root.relativize(csv).toString().replace('\\', '/'));

        say("");
        say("Recorded " + flow.steps.size() + " step(s) as '" + name + "'.");
        say("  keyword script    : " + csv + " (testCase " + name + ")");
        say("  object repository : " + repository + " (" + added + " line(s) added)");
        say("  self-healing      : " + seeded + " locator baseline(s) stored");
        for (Path created : scaffold) {
            say("  created           : " + created);
        }
        reportFollowUps(flow, activeSite, scaffold.isEmpty());
        return replay ? replayAndReport(flow, repository, csv) : 0;
    }

    private List<RecordedEvent> recordInBrowser(String startUrl) {
        WebDriver driver = DriverFactory.createDriver();
        try {
            RecorderSession session = new RecorderSession(driver);
            session.start(startUrl);
            say("");
            say("Recording " + startUrl);
            say("Use the page as a user would. The REC toolbar (top right) has Verify text / Verify visible /");
            say("Hover and Stop. Press Stop in the browser, or press ENTER here, when you are done.");
            Thread stdin = new Thread(() -> {
                try {
                    if (System.in.read() >= 0) {
                        session.requestStop();
                    }
                } catch (IOException e) {
                    // no console attached: only the Stop button ends the recording
                    Thread.currentThread().interrupt();
                }
            }, "recorder-stdin");
            stdin.setDaemon(true);
            stdin.start();
            session.run(maxMinutes * 60_000L, POLL_MILLIS);
            return new ArrayList<>(session.finish());
        } finally {
            DriverFactory.quitDriver(driver);
        }
    }

    private int replayAndReport(RecordedFlow flow, Path repository, Path csv) {
        say("");
        say("Replaying '" + name + "' in a fresh browser to check it...");
        flow.secrets.forEach(System::setProperty);
        WebDriver driver = DriverFactory.createDriver();
        try {
            new KeywordEngine(driver, ObjectRepository.fromFile(repository))
                .run(KeywordReader.readTestCase(csv.toString(), name));
            say("REPLAY PASSED.");
            return 0;
        } catch (RuntimeException | AssertionError e) {
            say("REPLAY FAILED: " + e.getMessage());
            say("The recording was kept. Fix the step above (or re-record) - see the generated rows and locators.");
            return 1;
        } finally {
            DriverFactory.quitDriver(driver);
            flow.secrets.keySet().forEach(System::clearProperty);
        }
    }

    private void reportFollowUps(RecordedFlow flow, String activeSite, boolean scaffoldExisted) {
        if (!flow.secrets.isEmpty()) {
            say("");
            say("Secrets were NOT written to the script. Set these before running the test (environment variables or -D):");
            flow.secrets.keySet().forEach(env -> say("  " + env));
        }
        for (String note : flow.notes) {
            say("note: " + note);
        }
        say("");
        say("Run it: mvn test -Dsite=" + activeSite + " -DsuiteXmlFile=testng-suites/" + activeSite + "-recorded.xml"
            + (scaffoldExisted ? "" : "   (new suite and runner class were generated)"));
    }

    private static String objectRepositoryResource(String repoRelative) {
        String normalised = repoRelative.replace('\\', '/');
        String marker = "src/test/resources/";
        return normalised.startsWith(marker) ? normalised.substring(marker.length()) : normalised;
    }

    private String startUrl(String baseUrl) {
        if (url.isEmpty()) {
            return baseUrl;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (url.startsWith("/") ? url : "/" + url);
    }

    private boolean parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--name" -> name = value(args, ++i, arg);
                case "--site" -> site = value(args, ++i, arg);
                case "--url" -> url = value(args, ++i, arg);
                case "--script" -> script = value(args, ++i, arg);
                case "--repo" -> repo = value(args, ++i, arg);
                case "--prefix" -> prefix = value(args, ++i, arg);
                case "--max-minutes" -> maxMinutes = Long.parseLong(value(args, ++i, arg));
                case "--overwrite" -> overwrite = true;
                case "--no-replay" -> replay = false;
                case "--no-auto-assert" -> autoAssert = false;
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) {
            say("--name is required and may only contain letters, digits, '_', '.' and '-' (for example --name TC_Login).");
            say("Options: --name N [--url PATH] [--script CSV] [--repo PROPERTIES] [--prefix P] [--overwrite]"
                + " [--no-replay] [--no-auto-assert] [--max-minutes M]; the site comes from -Dsite.");
            return false;
        }
        return true;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void say(String message) {
        System.out.println(message);
    }

    /** Test hook: true if the file looks like a recorded script this tool wrote. */
    static boolean isRecordedScript(Path file) throws IOException {
        return Files.exists(file) && Files.readString(file).startsWith(ScriptWriter.CSV_HEADER);
    }
}
