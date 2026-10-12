package com.automation.sites.listeners;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The quarantine list read from {@code quarantine.properties} (repo root): which known-flaky
 * tests may fail without failing the build, until when, and why. Pure logic (java.util only) so
 * {@code QuarantineRegistryTest} covers every rule without TestNG or a browser. Class/method
 * matching is shared with {@link TestSelection}.
 *
 * <pre>
 * # target = until | ticket | reason          (until = last day, yyyy-MM-dd, mandatory)
 * LoginTest#verifyValidLogin = 2026-11-15 | QA-412 | CAPTCHA solver misreads 'f'
 * BrokenLinksImagesTest      = 2026-10-31 | QA-377 | external CDN is intermittently down
 * com.automation.sites.demoqa.tests.* = 2026-11-01 | QA-300 | staging data reset
 * </pre>
 *
 * Targets use the same forms as test-config.properties: simple or fully-qualified class name,
 * {@code Class#method}, or {@code some.package.*}. A method rule beats a class/package rule.
 * Lines that cannot be parsed (no date, bad date) are ignored with a warning - the test then
 * simply fails normally, so a typo can never hide a failure.
 *
 * <p>Cucumber scenarios all run through one TestNG method, so they are quarantined by their own
 * identity instead of by class:
 * <pre>
 * scenario: Login with a locked-out user  = 2026-11-15 | QA-412 | intermittent captcha
 * scenario: Checkout *                    = 2026-11-15 | QA-9   | all checkout scenarios
 * feature: checkout.feature               = 2026-11-15 | QA-9   | every scenario in the file
 * tag: @flaky                             = 2026-11-15 | QA-7   | every scenario carrying that tag
 * </pre>
 * ({@code *} is a wildcard; a scenario name cannot contain '='.) A scenario can also carry its own
 * tags: {@code @quarantine_until_2026-11-15} (mandatory date) and an optional {@code @ticket_QA-412}.
 * The file is parsed line by line rather than with {@link java.util.Properties}, because scenario
 * names contain spaces and colons that a properties key cannot hold.
 *
 * <p>Annotation-based quarantines ({@code @Quarantine}) are built with {@link #entryOf}.
 */
public final class QuarantineRegistry {

    /** One quarantined target. {@code origin} says where it came from, for log/report lines. */
    public record Entry(String target, LocalDate until, String ticket, String reason, String origin) {

        /** The quarantine applies up to and including {@code until}. */
        public boolean activeOn(LocalDate day) {
            return !day.isAfter(until);
        }

        /** "QA-412 (until 2026-11-15): CAPTCHA solver misreads 'f'" - any blank part is left out. */
        public String describe() {
            StringBuilder text = new StringBuilder();
            if (ticket != null && !ticket.isBlank()) {
                text.append(ticket).append(' ');
            }
            text.append("(until ").append(until).append(')');
            if (reason != null && !reason.isBlank()) {
                text.append(": ").append(reason);
            }
            return text.toString();
        }
    }

    static final String DEFAULT_FILE = "quarantine.properties";

    private final List<Entry> entries;
    private final List<String> warnings;

    private QuarantineRegistry(List<Entry> entries, List<String> warnings) {
        this.entries = entries;
        this.warnings = warnings;
    }

    private static volatile QuarantineRegistry instance;
    private static volatile List<String> loadMessages = List.of();

    /** Loaded once per JVM from -Dquarantine.file (default: repo-root quarantine.properties). */
    public static QuarantineRegistry get() {
        QuarantineRegistry local = instance;
        if (local == null) {
            synchronized (QuarantineRegistry.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static QuarantineRegistry load() {
        String file = System.getProperty("quarantine.file", DEFAULT_FILE);
        Path path = Path.of(file);
        List<String> messages = new ArrayList<>();
        QuarantineRegistry registry;
        if (Files.exists(path)) {
            try {
                registry = parse(Files.readString(path));
            } catch (IOException e) {
                messages.add("could not read " + file + " (" + e.getMessage() + ") - nothing quarantined");
                registry = parse("");
            }
        } else {
            registry = parse("");
        }
        messages.addAll(registry.warnings);
        loadMessages = messages;
        return registry;
    }

    /** Problems found while loading the file (unparsable lines etc.), for the caller to log once. */
    public static List<String> loadMessages() {
        get();
        return loadMessages;
    }

    /** Parses the file's text; package-private so tests can build a registry without touching disk. */
    static QuarantineRegistry parse(String text) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text; // a BOM would hide the first rule
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                warnings.add("ignored line without 'target = until | ticket | reason': " + line);
                continue;
            }
            String target = line.substring(0, eq).strip();
            String[] parts = line.substring(eq + 1).split("\\|", 3);
            Optional<LocalDate> until = parseDate(parts[0]);
            if (until.isEmpty()) {
                warnings.add("ignored '" + target + "' - needs 'yyyy-MM-dd | ticket | reason', got '"
                    + line.substring(eq + 1).strip() + "'");
                continue;
            }
            entries.add(new Entry(target, until.get(),
                parts.length > 1 ? parts[1].strip() : "",
                parts.length > 2 ? parts[2].strip() : "",
                DEFAULT_FILE));
        }
        return new QuarantineRegistry(entries, warnings);
    }

    /** Builds an entry from {@code @Quarantine} values; empty when {@code until} is missing/invalid. */
    public static Optional<Entry> entryOf(String target, String until, String ticket, String reason) {
        return parseDate(until).map(day -> new Entry(target, day, ticket, reason, "@Quarantine"));
    }

    /**
     * The file rule that applies to this test, if any - a {@code Class#method} rule is preferred
     * over a class or package rule. The caller decides whether it is still active
     * ({@link Entry#activeOn}), so an expired entry can be reported instead of silently ignored.
     */
    public Optional<Entry> find(String className, String methodName) {
        Entry classLevel = null;
        for (Entry entry : entries) {
            if (isScenarioTarget(entry.target())) {
                continue;
            }
            int hash = entry.target().indexOf('#');
            String classPattern = hash < 0 ? entry.target() : entry.target().substring(0, hash);
            if (!TestSelection.matchesClass(classPattern.trim(), className)) {
                continue;
            }
            if (hash >= 0) {
                if (entry.target().substring(hash + 1).trim().equals(methodName)) {
                    return Optional.of(entry);
                }
            } else if (classLevel == null) {
                classLevel = entry;
            }
        }
        return Optional.ofNullable(classLevel);
    }

    /** A Cucumber scenario as the quarantine sees it: its name, feature file path and tags (with the '@'). */
    public record Scenario(String name, String feature, List<String> tags) {
    }

    static final String SCENARIO_PREFIX = "scenario:";
    static final String FEATURE_PREFIX = "feature:";
    static final String TAG_PREFIX = "tag:";
    private static final Pattern UNTIL_TAG = Pattern.compile("^@quarantine_until_(\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern TICKET_TAG = Pattern.compile("^@ticket_(\\S+)$");

    private static boolean isScenarioTarget(String target) {
        return target.startsWith(SCENARIO_PREFIX) || target.startsWith(FEATURE_PREFIX)
            || target.startsWith(TAG_PREFIX);
    }

    /**
     * The quarantine that applies to a Cucumber scenario, if any. Order: a {@code scenario:} rule,
     * the scenario's own {@code @quarantine_until_<date>} tag, a {@code tag:} rule, then a
     * {@code feature:} rule. As with classes, the caller checks {@link Entry#activeOn}.
     */
    public Optional<Entry> findScenario(Scenario scenario) {
        for (Entry entry : entries) {
            if (entry.target().startsWith(SCENARIO_PREFIX)
                && glob(entry.target().substring(SCENARIO_PREFIX.length()).strip())
                    .matcher(scenario.name()).matches()) {
                return Optional.of(entry);
            }
        }
        Optional<Entry> inline = entryFromTags(scenario);
        if (inline.isPresent()) {
            return inline;
        }
        for (Entry entry : entries) {
            if (entry.target().startsWith(TAG_PREFIX)) {
                String wanted = stripAt(entry.target().substring(TAG_PREFIX.length()).strip());
                if (scenario.tags().stream().anyMatch(tag -> stripAt(tag).equals(wanted))) {
                    return Optional.of(entry);
                }
            }
        }
        for (Entry entry : entries) {
            if (entry.target().startsWith(FEATURE_PREFIX)
                && matchesFeature(entry.target().substring(FEATURE_PREFIX.length()).strip(), scenario.feature())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** {@code @quarantine_until_2026-11-15} (+ optional {@code @ticket_QA-412}) written on the scenario itself. */
    static Optional<Entry> entryFromTags(Scenario scenario) {
        String until = null;
        String ticket = "";
        for (String tag : scenario.tags()) {
            Matcher untilMatch = UNTIL_TAG.matcher(tag);
            if (untilMatch.matches()) {
                until = untilMatch.group(1);
            }
            Matcher ticketMatch = TICKET_TAG.matcher(tag);
            if (ticketMatch.matches()) {
                ticket = ticketMatch.group(1);
            }
        }
        if (until == null) {
            return Optional.empty();
        }
        Optional<Entry> entry = entryOf("scenario: " + scenario.name(), until, ticket,
            "tagged @quarantine_until_" + until);
        return entry.map(e -> new Entry(e.target(), e.until(), e.ticket(), e.reason(), "@tag"));
    }

    private static String stripAt(String tag) {
        return tag.startsWith("@") ? tag.substring(1) : tag;
    }

    /** Pattern against the feature's path, or just its file name; also a plain "dir/file.feature" suffix. */
    private static boolean matchesFeature(String pattern, String uri) {
        if (uri == null || pattern.isEmpty()) {
            return false;
        }
        String path = uri.replace('\\', '/');
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        Pattern glob = glob(pattern);
        return glob.matcher(path).matches() || glob.matcher(fileName).matches()
            || path.endsWith("/" + pattern) || path.endsWith(":" + pattern);
    }

    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /** Entries whose date has passed - worth a loud warning, they stop protecting the build. */
    public List<Entry> expiredOn(LocalDate day) {
        List<Entry> expired = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.activeOn(day)) {
                expired.add(entry);
            }
        }
        return expired;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    private static Optional<LocalDate> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
