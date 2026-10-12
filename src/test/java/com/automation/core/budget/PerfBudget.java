package com.automation.core.budget;

import com.automation.core.budget.PerfSamples.Kind;
import com.automation.core.config.ConfigReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The time limits a test run is held to: a default per kind of measurement plus optional
 * per-endpoint / per-page overrides. Pure logic (no browser, no TestNG) so it is unit-tested
 * in {@code PerfBudgetTest}; {@link #fromConfig()} is the only part that reads configuration.
 *
 * <pre>
 * perf.budget.api.maxMs=5000                       # default for every API call
 * perf.budget.pageLoad.maxMs=15000                 # default for every page navigation
 * perf.budget.api.overrides=GET /BookStore/v1/Books=2000; POST /Account/*=8000
 * perf.budget.pageLoad.overrides=/books=20000; /upload-download=25000
 * perf.budget.screen.maxMs=10000                   # mobile: app launch / element a screen waits for
 * perf.budget.screen.overrides=app launch=30000; element*Network*=20000
 * </pre>
 *
 * <p>Overrides are {@code ;}-separated, <b>first match wins</b> (list specific entries before
 * general ones). {@code *} matches any run of characters, slashes included. An API override
 * starts with an HTTP method, or {@code *} / nothing for "any method". A limit of {@code 0} (or
 * less) means "no limit" for that match, which is how one slow endpoint is exempted.
 */
public final class PerfBudget {

    private record Rule(String method, Pattern path, long maxMs, String text) {
    }

    private final long apiDefaultMs;
    private final long pageLoadDefaultMs;
    private final long screenDefaultMs;
    private final List<Rule> apiRules;
    private final List<Rule> pageRules;
    private final List<Rule> screenRules;
    private final List<String> warnings;

    static final long DEFAULT_SCREEN_MS = 10000;

    private PerfBudget(long apiDefaultMs, long pageLoadDefaultMs, long screenDefaultMs, List<Rule> apiRules,
                       List<Rule> pageRules, List<Rule> screenRules, List<String> warnings) {
        this.apiDefaultMs = apiDefaultMs;
        this.pageLoadDefaultMs = pageLoadDefaultMs;
        this.screenDefaultMs = screenDefaultMs;
        this.apiRules = apiRules;
        this.pageRules = pageRules;
        this.screenRules = screenRules;
        this.warnings = warnings;
    }

    /** Budget from {@code perf.budget.*} (global.properties -> site properties -> -D). */
    public static PerfBudget fromConfig() {
        long apiDefault = ConfigReader.getInt("perf.budget.api.maxMs",
            ConfigReader.getInt("api.responseTime.maxMs", 5000));
        long pageDefault = ConfigReader.getInt("perf.budget.pageLoad.maxMs", 15000);
        long screenDefault = ConfigReader.getInt("perf.budget.screen.maxMs", (int) DEFAULT_SCREEN_MS);
        return of(apiDefault, pageDefault, screenDefault,
            ConfigReader.get("perf.budget.api.overrides", ""),
            ConfigReader.get("perf.budget.pageLoad.overrides", ""),
            ConfigReader.get("perf.budget.screen.overrides", ""));
    }

    public static PerfBudget of(long apiDefaultMs, long pageLoadDefaultMs,
                                String apiOverrides, String pageLoadOverrides) {
        return of(apiDefaultMs, pageLoadDefaultMs, DEFAULT_SCREEN_MS, apiOverrides, pageLoadOverrides, "");
    }

    public static PerfBudget of(long apiDefaultMs, long pageLoadDefaultMs, long screenDefaultMs,
                                String apiOverrides, String pageLoadOverrides, String screenOverrides) {
        List<String> warnings = new ArrayList<>();
        return new PerfBudget(apiDefaultMs, pageLoadDefaultMs, screenDefaultMs,
            parse(apiOverrides, true, warnings), parse(pageLoadOverrides, false, warnings),
            parse(screenOverrides, false, warnings), warnings);
    }

    /** Malformed override entries that were ignored, so the caller can log them once. */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * @param name API: {@code "GET /path"}; page load: {@code "/path"}; screen: its label
     * @return the limit in ms, or {@code 0} when this measurement has no limit
     */
    public long limitFor(Kind kind, String name) {
        if (kind == Kind.API) {
            int space = name.indexOf(' ');
            String method = space < 0 ? "*" : name.substring(0, space).toUpperCase(Locale.ROOT);
            String path = space < 0 ? name : name.substring(space + 1).trim();
            for (Rule rule : apiRules) {
                if ((rule.method().equals("*") || rule.method().equals(method))
                    && rule.path().matcher(path).matches()) {
                    return Math.max(0, rule.maxMs());
                }
            }
            return Math.max(0, apiDefaultMs);
        }
        boolean screen = kind == Kind.SCREEN;
        for (Rule rule : screen ? screenRules : pageRules) {
            if (rule.path().matcher(name).matches()) {
                return Math.max(0, rule.maxMs());
            }
        }
        return Math.max(0, screen ? screenDefaultMs : pageLoadDefaultMs);
    }

    private static List<Rule> parse(String raw, boolean api, List<String> warnings) {
        List<Rule> rules = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return rules;
        }
        for (String entry : raw.split("[;\\n]")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.lastIndexOf('=');
            if (eq <= 0) {
                warnings.add("ignored override with no '=<ms>': " + trimmed);
                continue;
            }
            long maxMs;
            try {
                maxMs = Long.parseLong(trimmed.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                warnings.add("ignored override with a non-numeric limit: " + trimmed);
                continue;
            }
            String target = trimmed.substring(0, eq).trim();
            String method = "*";
            if (api) {
                int space = target.indexOf(' ');
                if (space > 0) {
                    method = target.substring(0, space).toUpperCase(Locale.ROOT);
                    target = target.substring(space + 1).trim();
                }
            }
            if (target.isEmpty()) {
                warnings.add("ignored override with no path: " + trimmed);
                continue;
            }
            rules.add(new Rule(method, glob(target), maxMs, trimmed));
        }
        return rules;
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
