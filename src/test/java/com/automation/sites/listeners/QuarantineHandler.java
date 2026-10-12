package com.automation.sites.listeners;

import com.automation.core.config.ConfigReader;
import com.automation.core.quarantine.Quarantine;
import com.automation.sites.listeners.QuarantineRegistry.Entry;
import com.automation.sites.listeners.QuarantineRegistry.Scenario;
import io.qameta.allure.Allure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestResult;
import org.testng.SkipException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies the flaky-test quarantine (see {@link Quarantine}, {@link QuarantineRegistry}) to each
 * finished test method. Called from {@code TestListener.afterInvocation}, after soft assertions
 * and performance budgets have been folded into the result:
 *
 * <ul>
 *   <li>quarantined test FAILED - the result is turned into SKIPPED with a "Quarantined ..."
 *       message (original failure kept as the cause), so the build stays green but the report
 *       still shows exactly what happened, with screenshot/page source/video as usual;</li>
 *   <li>quarantined test PASSED - left as passed, tagged so the report shows it is healthy and
 *       can probably leave quarantine;</li>
 *   <li>quarantine past its {@code until} date - ignored, the test fails the build normally.</li>
 * </ul>
 *
 * Quarantined tests are not retried (RetryAnalyzer asks {@link #isQuarantined}); a failure is
 * tolerated anyway, so a retry would only burn time. Everything is switched off by
 * {@code -Dquarantine.enabled=false}.
 */
public final class QuarantineHandler {

    private static final Logger logger = LoggerFactory.getLogger(QuarantineHandler.class);

    private static final List<String> TOLERATED = new CopyOnWriteArrayList<>();
    private static final List<String> HEALTHY = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean STARTUP_LOGGED = new AtomicBoolean();

    private QuarantineHandler() {
    }

    /** Quarantines older than this many days from now are not honoured; 0 = no cap. */
    static final int DEFAULT_MAX_DAYS = 90;
    /** Tests in these TestNG groups can never be quarantined: a flaky SECURITY check must be fixed, not silenced. */
    static final String DEFAULT_NEVER_GROUPS = "security";

    private static final java.util.Set<String> POLICY_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isEnabled() {
        try {
            return ConfigReader.getBoolean("quarantine.enabled", true);
        } catch (RuntimeException configUnavailable) {
            return true;
        }
    }

    private static int maxDays() {
        try {
            return ConfigReader.getInt("quarantine.maxDays", DEFAULT_MAX_DAYS);
        } catch (RuntimeException configUnavailable) {
            return DEFAULT_MAX_DAYS;
        }
    }

    private static String neverGroups() {
        try {
            return ConfigReader.get("quarantine.neverGroups", DEFAULT_NEVER_GROUPS);
        } catch (RuntimeException configUnavailable) {
            return DEFAULT_NEVER_GROUPS;
        }
    }

    /**
     * A quarantine must be temporary: one whose end date is further away than {@code maxDays} from
     * today (say 2099-01-01) is a permanent exemption in disguise and is not honoured.
     */
    static boolean withinPolicy(Entry entry, LocalDate today, int maxDays) {
        return maxDays <= 0 || !entry.until().isAfter(today.plusDays(maxDays));
    }

    /** True when any of the test's groups is on the comma-separated never-quarantine list (case-insensitive). */
    static boolean excludedByGroup(String[] testGroups, String neverGroupsCsv) {
        if (testGroups == null || neverGroupsCsv == null || neverGroupsCsv.isBlank()) {
            return false;
        }
        java.util.Set<String> never = new java.util.HashSet<>();
        for (String group : neverGroupsCsv.split(",")) {
            never.add(group.trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (String group : testGroups) {
            if (group != null && never.contains(group.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void warnOnce(String message) {
        if (POLICY_WARNED.add(message)) {
            logger.warn("[Quarantine] {}", message);
        }
    }

    /** True when this test is covered by a quarantine that has not expired. */
    public static boolean isQuarantined(ITestResult result) {
        return isEnabled() && activeEntry(result).isPresent();
    }

    /**
     * Applies the quarantine to a finished result.
     *
     * @return the quarantine entry when the test is quarantined (whatever its outcome), else empty
     */
    public static Optional<Entry> apply(ITestResult result) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return applyAt(result, LocalDate.now(), maxDays(), neverGroups());
    }

    /** {@link #apply} with the date and policy passed in, so every rule can be tested without configuration. */
    static Optional<Entry> applyAt(ITestResult result, LocalDate today, int maxDays, String neverGroups) {
        Optional<Entry> found = activeEntry(result, today, maxDays, neverGroups);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = found.get();
        String name = displayName(result);
        label(entry);

        if (result.getStatus() == ITestResult.FAILURE) {
            Throwable original = result.getThrowable();
            String why = original == null ? "unknown failure"
                : original.getClass().getSimpleName() + ": " + firstLine(original.getMessage());
            result.setStatus(ITestResult.SKIP);
            result.setThrowable(new SkipException(
                "Quarantined - " + entry.describe() + ". Failure ignored for the build: " + why, original));
            TOLERATED.add(name + " - " + entry.describe() + " - " + why);
            logger.warn("[Quarantine] {} FAILED but is quarantined, build not affected: {} | {}",
                name, entry.describe(), why);
        } else if (result.getStatus() == ITestResult.SUCCESS) {
            HEALTHY.add(name + " - " + entry.describe());
            logger.info("[Quarantine] {} passed while quarantined - a candidate to release: {}",
                name, entry.describe());
        }
        return found;
    }

    /** Logs expired entries / file problems once per JVM; safe to call from every onStart. */
    public static void logStartupOnce() {
        if (!isEnabled() || !STARTUP_LOGGED.compareAndSet(false, true)) {
            return;
        }
        for (String message : QuarantineRegistry.loadMessages()) {
            logger.warn("[Quarantine] {}", message);
        }
        for (Entry expired : QuarantineRegistry.get().expiredOn(LocalDate.now())) {
            logger.warn("[Quarantine] '{}' expired on {} ({}) - it now fails the build normally. "
                    + "Fix the test, or renew the date if it is still being worked on.",
                expired.target(), expired.until(), expired.ticket());
        }
    }

    /** End-of-run summary: what failed under quarantine (the thing that must not be forgotten). */
    public static void writeSummary() {
        if (TOLERATED.isEmpty() && HEALTHY.isEmpty()) {
            return;
        }
        logger.warn("[Quarantine] {} quarantined test(s) failed and were tolerated; {} passed.",
            TOLERATED.size(), HEALTHY.size());
        TOLERATED.forEach(line -> logger.warn("[Quarantine]   tolerated failure: {}", line));
        try {
            // One file per site: CI often runs several sites as separate JVMs sharing target/.
            String site = ConfigReader.get("site", ConfigReader.getActiveSite()).replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = Path.of("target", "quarantine", "summary-" + site + ".txt");
            Files.createDirectories(file.getParent());
            StringBuilder text = new StringBuilder("Quarantined tests that FAILED (build not affected):")
                .append(System.lineSeparator());
            TOLERATED.forEach(line -> text.append("  - ").append(line).append(System.lineSeparator()));
            text.append(System.lineSeparator()).append("Quarantined tests that PASSED (candidates to release):")
                .append(System.lineSeparator());
            HEALTHY.forEach(line -> text.append("  - ").append(line).append(System.lineSeparator()));
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            logger.warn("[Quarantine] Could not write the summary file: {}", e.getMessage());
        }
    }

    /**
     * Labels a Cucumber scenario as quarantined in the Allure result the Cucumber plugin writes.
     * That result is produced from Cucumber's own events, so the TestNG-level FAILED-to-SKIPPED
     * flip cannot change it: the scenario stays "failed" there but carries the {@code quarantined}
     * label, so it can be filtered out of the failures view. The TestNG result, the CI gate and
     * the Extent report all see it as skipped.
     */
    public static void markScenario(String name, String featureUri, Collection<String> tags) {
        if (!isEnabled()) {
            return;
        }
        try {
            Scenario scenario = new Scenario(name == null ? "" : name, featureUri == null ? "" : featureUri,
                tags == null ? List.of() : new ArrayList<>(tags));
            QuarantineRegistry.get().findScenario(scenario)
                .filter(e -> e.activeOn(LocalDate.now()) && withinPolicy(e, LocalDate.now(), maxDays()))
                .ifPresent(QuarantineHandler::label);
        } catch (RuntimeException e) {
            logger.debug("[Quarantine] could not label scenario '{}': {}", name, e.getMessage());
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Annotation on the method, then on the test's runtime class, then on the class that declares
     * the method, then a quarantine.properties rule (runtime class first, then declaring class,
     * so a rule naming a subclass also covers a method it inherits). Expired entries are skipped.
     */
    private static Optional<Entry> activeEntry(ITestResult result) {
        return activeEntry(result, LocalDate.now(), maxDays(), neverGroups());
    }

    static Optional<Entry> activeEntry(ITestResult result, LocalDate today, int maxDays, String neverGroups) {
        Optional<Entry> found = lookup(result, today);
        if (found.isEmpty()) {
            return found;
        }
        Entry entry = found.get();
        if (!withinPolicy(entry, today, maxDays)) {
            warnOnce("'" + entry.target() + "' is quarantined until " + entry.until() + ", more than " + maxDays
                + " days away - ignored, the test fails normally. Use a nearer date (quarantine.maxDays).");
            return Optional.empty();
        }
        if (excludedByGroup(result.getMethod().getGroups(), neverGroups)) {
            warnOnce("'" + entry.target() + "' is in a group that can never be quarantined ("
                + neverGroups + ") - ignored, the test fails normally.");
            return Optional.empty();
        }
        return found;
    }

    private static Optional<Entry> lookup(ITestResult result, LocalDate today) {
        // Cucumber: every scenario runs through one TestNG method, so the scenario's own name /
        // feature / tags decide first; a rule on the runner class below still covers them all.
        Optional<Scenario> scenario = ScenarioInfo.from(result.getParameters());
        if (scenario.isPresent()) {
            Optional<Entry> scenarioEntry = QuarantineRegistry.get().findScenario(scenario.get())
                .filter(e -> e.activeOn(today));
            if (scenarioEntry.isPresent()) {
                return scenarioEntry;
            }
        }
        Method method = result.getMethod().getConstructorOrMethod().getMethod();
        Class<?> runtime = result.getTestClass().getRealClass();
        Class<?> declaring = method != null ? method.getDeclaringClass() : runtime;
        String methodName = result.getMethod().getMethodName();

        Optional<Entry> entry = Optional.empty();
        if (method != null && method.isAnnotationPresent(Quarantine.class)) {
            entry = fromAnnotation(declaring.getSimpleName() + "#" + methodName,
                method.getAnnotation(Quarantine.class));
        }
        for (Class<?> owner : new Class<?>[] {runtime, declaring}) {
            if (entry.isEmpty() && owner.isAnnotationPresent(Quarantine.class)) {
                entry = fromAnnotation(owner.getSimpleName(), owner.getAnnotation(Quarantine.class));
            }
        }
        for (Class<?> owner : new Class<?>[] {runtime, declaring}) {
            if (entry.isEmpty()) {
                entry = QuarantineRegistry.get().find(owner.getName(), methodName);
            }
        }
        return entry.filter(e -> e.activeOn(today));
    }

    private static Optional<Entry> fromAnnotation(String target, Quarantine annotation) {
        Optional<Entry> entry = QuarantineRegistry.entryOf(target, annotation.until(),
            annotation.ticket(), annotation.reason());
        if (entry.isEmpty()) {
            logger.warn("[Quarantine] @Quarantine on {} ignored: until='{}' is not a yyyy-MM-dd date",
                target, annotation.until());
        }
        return entry;
    }

    private static void label(Entry entry) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCaseOrStep().isPresent()) {
                return;
            }
            Allure.label("quarantined", "true");
            Allure.label("tag", "quarantined");
            if (entry.ticket() != null && !entry.ticket().isBlank()) {
                Allure.parameter("Quarantine ticket", entry.ticket());
            }
            Allure.parameter("Quarantined until", entry.until().toString());
        } catch (RuntimeException e) {
            logger.debug("[Quarantine] could not label Allure result: {}", e.getMessage());
        }
    }

    private static String displayName(ITestResult result) {
        Optional<Scenario> scenario = ScenarioInfo.from(result.getParameters());
        if (scenario.isPresent()) {
            return "Scenario '" + scenario.get().name() + "'";
        }
        return result.getTestClass().getRealClass().getSimpleName() + "#" + result.getMethod().getMethodName();
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "(no message)";
        }
        String line = message.strip().split("\\R", 2)[0];
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }
}
