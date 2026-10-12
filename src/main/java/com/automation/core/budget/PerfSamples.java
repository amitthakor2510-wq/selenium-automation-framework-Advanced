package com.automation.core.budget;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-thread collection of timings measured while a test runs (page loads from
 * {@code BasePage.navigateTo}/the keyword engine, API calls from the RestAssured budget filter).
 *
 * <p>This class only COLLECTS. It lives in {@code src/main} because page objects and the keyword
 * engine are in {@code src/main} and cannot reach the assertion layer ({@code Verify}, TestNG),
 * which lives in {@code src/test}. After each test method, {@code TestListener} drains the
 * samples through {@code PerfBudgetEnforcer}, which compares them with the configured budgets and
 * fails the test when one is exceeded. Page objects therefore never assert anything, in line with
 * CONVENTIONS.md.
 *
 * <p>State is per thread, so parallel classes never see each other's samples.
 */
public final class PerfSamples {

    /**
     * What was timed: an API call, a web page load, or a mobile screen becoming ready (an app
     * launch, or an element a screen waits for after a tap).
     */
    public enum Kind { API, PAGE_LOAD, SCREEN }

    /**
     * One timing. For {@link Kind#API} {@code name} is {@code "GET /path"} (no query string);
     * for {@link Kind#PAGE_LOAD} it is the navigated path, e.g. {@code "/books"}; for
     * {@link Kind#SCREEN} it is a short label such as {@code "app launch"}.
     */
    public record Sample(Kind kind, String name, long millis) {
    }

    /** Safety cap per thread: if no listener ever drains (a suite without TestListener) memory still stays bounded. */
    static final int MAX_SAMPLES_PER_THREAD = 5000;

    private static final ThreadLocal<List<Sample>> SAMPLES = ThreadLocal.withInitial(ArrayList::new);

    private PerfSamples() {
    }

    public static void record(Kind kind, String name, long millis) {
        List<Sample> list = SAMPLES.get();
        if (list.size() < MAX_SAMPLES_PER_THREAD) {
            list.add(new Sample(kind, name, millis));
        }
    }

    /** Records how long a navigation took, normalising a full URL down to its path. */
    public static void recordPageLoad(String urlOrPath, long millis) {
        record(Kind.PAGE_LOAD, toPath(urlOrPath), millis);
    }

    /** Records how long a mobile screen / app took to become ready. */
    public static void recordScreen(String name, long millis) {
        record(Kind.SCREEN, name == null || name.isBlank() ? "screen" : name, millis);
    }

    /** Takes (and clears) this thread's samples. */
    public static List<Sample> drain() {
        List<Sample> list = SAMPLES.get();
        List<Sample> copy = List.copyOf(list);
        list.clear();
        return copy;
    }

    /** Drops this thread's samples without evaluating them (e.g. calls made during teardown). */
    public static void clear() {
        SAMPLES.get().clear();
    }

    /** "https://host/books?x=1" -> "/books"; "" or just a host -> "/". */
    static String toPath(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isBlank()) {
            return "/";
        }
        String value = urlOrPath.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                String path = URI.create(value).getPath();
                return path == null || path.isEmpty() ? "/" : path;
            } catch (IllegalArgumentException e) {
                return value;
            }
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return value.startsWith("/") ? value : "/" + value;
    }
}
