package com.automation.sites.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Decides which test classes / methods / groups are switched off, from
 * {@code test-config.properties} (repo root) — the test-level counterpart of
 * {@code pipeline-config.properties}, which switches whole SITES on and off.
 *
 * <p>Unlike the site switch this one fails OPEN: a missing file, a missing
 * line, or a line with an unrecognised value means "run it". Nothing is ever
 * skipped by accident; only an explicit {@code =false} (or a
 * {@code run.only} list, or the {@code -Dtests.*} overrides below) does.
 *
 * <pre>
 * test.LoginTest.enabled=false                  # a whole class (simple or fully-qualified name)
 * test.BookStoreApplicationTest#verifyLogin.enabled=false   # one method
 * test.com.automation.sites.demoqa.tests.*.enabled=false    # every class in a package
 * group.perf.enabled=false                       # every test carrying this TestNG group
 * run.only=LoginTest,SampleTest                  # ONLY these classes (blank = no restriction)
 * </pre>
 *
 * <p>Command-line overrides (handy for one-off runs, no file edit needed):
 * {@code -Dtests.run.only=LoginTest} replaces the file's {@code run.only};
 * {@code -Dtests.disabled=BrokenLinksImagesTest,LoginTest#verifyX} adds to the
 * disabled list. {@code -Dtest.config.file=path} points at a different file.
 *
 * <p>Precedence: anything disabled stays disabled — {@code run.only} can only
 * narrow the set further, never re-enable something switched off.
 *
 * <p>Known limit: a test method inherited from a base class is matched by the
 * class that DECLARES it (that is what TestNG hands the annotation
 * transformer), so disable it by the declaring class or by group.
 *
 * <p>Pure logic on purpose (java.util only, no TestNG/ConfigReader) so
 * TestSelectionTest can cover every rule without a browser or a suite.
 */
public final class TestSelection {

    private static final Logger logger = LoggerFactory.getLogger(TestSelection.class);

    static final String DEFAULT_FILE = "test-config.properties";
    private static final String ENABLED_SUFFIX = ".enabled";

    private record Rule(String classPattern, String method) {
        String describe() {
            return method == null ? classPattern : classPattern + "#" + method;
        }
    }

    private final List<Rule> disabledRules;
    private final Set<String> disabledGroups;
    private final List<String> runOnly;
    private final List<String> warnings;

    private static volatile TestSelection instance;

    private TestSelection(List<Rule> disabledRules, Set<String> disabledGroups,
                          List<String> runOnly, List<String> warnings) {
        this.disabledRules = disabledRules;
        this.disabledGroups = disabledGroups;
        this.runOnly = runOnly;
        this.warnings = warnings;
    }

    /** Lazily loaded once per JVM from the file named by -Dtest.config.file (default: repo-root test-config.properties). */
    public static TestSelection get() {
        TestSelection local = instance;
        if (local == null) {
            synchronized (TestSelection.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static TestSelection load() {
        String file = System.getProperty("test.config.file", DEFAULT_FILE);
        Properties props = new Properties();
        Path path = Path.of(file);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                logger.warn("[TestSelection] Could not read {} ({}) — running every test.", file, e.getMessage());
            }
        } else {
            logger.info("[TestSelection] {} not found — no test is switched off by file.", file);
        }
        TestSelection selection = from(props,
            System.getProperty("tests.disabled"), System.getProperty("tests.run.only"));
        for (String warning : selection.warnings) {
            logger.warn("[TestSelection] {}: {}", file, warning);
        }
        if (selection.isEmpty()) {
            logger.info("[TestSelection] No tests disabled or restricted — running everything the suite selects.");
        } else {
            logger.info("[TestSelection] Active: {} disabled class/method rule(s), {} disabled group(s), run.only={}",
                selection.disabledRules.size(), selection.disabledGroups.size(),
                selection.runOnly.isEmpty() ? "(none)" : selection.runOnly);
        }
        return selection;
    }

    /** Package-private so tests can build a selection from in-memory properties. */
    static TestSelection from(Properties props, String sysDisabled, String sysRunOnly) {
        List<Rule> rules = new ArrayList<>();
        Set<String> groups = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key).trim();
            if (hasShape(key, "test.")) {
                String target = keyName(key, "test.");
                if (target.isEmpty()) {
                    warnings.add("ignored key with no test name: " + key);
                } else if (isFalse(value)) {
                    rules.add(parseRule(target));
                } else if (!isTrue(value)) {
                    warnings.add("'" + key + "=" + value + "' is not true/false — treated as enabled");
                }
            } else if (hasShape(key, "group.")) {
                String group = keyName(key, "group.");
                if (group.isEmpty()) {
                    warnings.add("ignored key with no group name: " + key);
                } else if (isFalse(value)) {
                    groups.add(group);
                } else if (!isTrue(value)) {
                    warnings.add("'" + key + "=" + value + "' is not true/false — treated as enabled");
                }
            }
        }

        for (String entry : splitList(sysDisabled)) {
            rules.add(parseRule(entry));
        }

        String runOnlyRaw = (sysRunOnly != null && !sysRunOnly.isBlank())
            ? sysRunOnly : props.getProperty("run.only", "");
        return new TestSelection(rules, groups, splitList(runOnlyRaw), warnings);
    }

    /**
     * "prefix" + name + ".enabled". The length check matters: "test.enabled"
     * starts with "test." AND ends with ".enabled" but the two overlap on the
     * shared dot, so slicing it as prefix+name+suffix would go out of bounds.
     */
    private static boolean hasShape(String key, String prefix) {
        return key.startsWith(prefix) && key.endsWith(ENABLED_SUFFIX)
            && key.length() >= prefix.length() + ENABLED_SUFFIX.length();
    }

    private static String keyName(String key, String prefix) {
        return key.substring(prefix.length(), key.length() - ENABLED_SUFFIX.length()).trim();
    }

    boolean isEmpty() {
        return disabledRules.isEmpty() && disabledGroups.isEmpty() && runOnly.isEmpty();
    }

    /**
     * @param className  fully-qualified name of the class declaring the test
     * @param methodName test method name, or null for a class-level annotation
     * @param groups     TestNG groups on the annotation (may be null)
     * @return why this test is switched off, or empty if it should run
     */
    public Optional<String> disabledReason(String className, String methodName, String[] groups) {
        if (groups != null) {
            for (String group : groups) {
                if (disabledGroups.contains(group)) {
                    return Optional.of("group '" + group + "' is disabled");
                }
            }
        }
        for (Rule rule : disabledRules) {
            if (!matchesClass(rule.classPattern(), className)) {
                continue;
            }
            if (rule.method() == null) {
                return Optional.of("class '" + rule.describe() + "' is disabled");
            }
            if (rule.method().equals(methodName)) {
                return Optional.of("method '" + rule.describe() + "' is disabled");
            }
        }
        if (!runOnly.isEmpty()) {
            for (String pattern : runOnly) {
                if (matchesClass(pattern, className)) {
                    return Optional.empty();
                }
            }
            return Optional.of("not in run.only " + runOnly);
        }
        return Optional.empty();
    }

    private static Rule parseRule(String target) {
        int hash = target.indexOf('#');
        if (hash < 0) {
            return new Rule(target.trim(), null);
        }
        String method = target.substring(hash + 1).trim();
        return new Rule(target.substring(0, hash).trim(), method.isEmpty() ? null : method);
    }

    /** pattern = fully-qualified name, simple name, or "some.package.*" (that package and below). */
    static boolean matchesClass(String pattern, String fqcn) {
        if (pattern.endsWith(".*")) {
            return fqcn.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (pattern.equals(fqcn)) {
            return true;
        }
        int dot = fqcn.lastIndexOf('.');
        return pattern.equals(dot < 0 ? fqcn : fqcn.substring(dot + 1));
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean isFalse(String v) {
        return "false".equalsIgnoreCase(v);
    }

    private static boolean isTrue(String v) {
        return "true".equalsIgnoreCase(v);
    }
}
