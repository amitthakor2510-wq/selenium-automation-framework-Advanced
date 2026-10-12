package com.automation.core.budget;

import com.automation.core.budget.PerfSamples.Sample;
import com.automation.core.config.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run-level view of every timing the performance budget measured: which calls were over their
 * limit, which were slowest, and - per endpoint / page - how long it really takes, with a
 * suggested limit. Written once at the end of the run to
 * {@code target/perf-budget/summary-<site>.txt} (uploaded as a CI artifact) and summarised in the
 * log.
 *
 * <p>The point is tuning: picking a limit that is neither so tight that it flakes nor so loose
 * that it never fires is guesswork without data. Run the suite a few times with
 * {@code perf.budget.handling=OPTIONAL}, read the "suggested" column, and put the numbers into
 * {@code perf.budget.*.overrides}. Suggested limit = p95 x 1.5, rounded up to the next 100 ms,
 * and only offered for endpoints measured at least {@value #MIN_CALLS_FOR_SUGGESTION} times.
 *
 * <p>Report building is pure ({@link #build}) so it is unit-tested without a run.
 */
public final class PerfBudgetReport {

    private static final Logger logger = LoggerFactory.getLogger(PerfBudgetReport.class);

    static final int MIN_CALLS_FOR_SUGGESTION = 3;
    private static final int SLOWEST_SHOWN = 10;
    private static final int MAX_ENTRIES = 20_000;

    /** One measurement together with the test it belongs to and the limit it was held to. */
    record Entry(String test, Sample sample, long limitMs) {
        boolean exceeded() {
            return limitMs > 0 && sample.millis() > limitMs;
        }
    }

    private static final Queue<Entry> ENTRIES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();

    private PerfBudgetReport() {
    }

    /** Called by the enforcer for every measurement it evaluates. */
    static void record(String test, Sample sample, long limitMs) {
        if (SIZE.get() < MAX_ENTRIES) {
            ENTRIES.add(new Entry(test == null ? "(scenario)" : test, sample, limitMs));
            SIZE.incrementAndGet();
        }
    }

    /** Test hook: forget everything recorded so far. */
    static void reset() {
        ENTRIES.clear();
        SIZE.set(0);
    }

    /** Logs a one-line verdict and writes the full report; does nothing when nothing was measured. */
    public static void writeSummary() {
        List<Entry> entries = new ArrayList<>(ENTRIES);
        if (entries.isEmpty()) {
            return;
        }
        long over = entries.stream().filter(Entry::exceeded).count();
        try {
            String site = ConfigReader.get("site", ConfigReader.getActiveSite()).replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = Path.of("target", "perf-budget", "summary-" + site + ".txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, build(entries), StandardCharsets.UTF_8);
            logger.info("[PerfBudget] {} measurements, {} over budget - details and suggested limits: {}",
                entries.size(), over, file);
        } catch (IOException | RuntimeException e) {
            logger.warn("[PerfBudget] Could not write the summary file: {}", e.getMessage());
        }
    }

    // ── pure report building ─────────────────────────────────────────────────

    static String build(List<Entry> entries) {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        List<Entry> over = entries.stream().filter(Entry::exceeded)
            .sorted(Comparator.comparingLong((Entry e) -> e.sample().millis()).reversed()).toList();

        text.append("Performance budget summary: ").append(entries.size()).append(" measurements, ")
            .append(over.size()).append(" over budget").append(nl).append(nl);

        text.append("OVER BUDGET (").append(over.size()).append(')').append(nl);
        if (over.isEmpty()) {
            text.append("  none").append(nl);
        }
        for (Entry e : over) {
            text.append("  ").append(describe(e.sample())).append("  ").append(e.sample().millis())
                .append(" ms (budget ").append(e.limitMs()).append(" ms)  in ").append(e.test()).append(nl);
        }

        text.append(nl).append("SLOWEST ").append(SLOWEST_SHOWN).append(nl);
        entries.stream().sorted(Comparator.comparingLong((Entry e) -> e.sample().millis()).reversed())
            .limit(SLOWEST_SHOWN)
            .forEach(e -> text.append("  ").append(describe(e.sample())).append("  ")
                .append(e.sample().millis()).append(" ms  in ").append(e.test()).append(nl));

        text.append(nl).append("PER ENDPOINT / PAGE  (suggested = p95 x 1.5 rounded up to 100 ms, needs >= ")
            .append(MIN_CALLS_FOR_SUGGESTION).append(" calls)").append(nl);
        Map<String, List<Entry>> byTarget = new LinkedHashMap<>();
        for (Entry e : entries) {
            byTarget.computeIfAbsent(describe(e.sample()), key -> new ArrayList<>()).add(e);
        }
        byTarget.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, List<Entry>> t) -> p95(millis(t.getValue()))).reversed())
            .forEach(t -> {
                List<Long> times = millis(t.getValue());
                long limit = t.getValue().get(0).limitMs();
                text.append(String.format(Locale.ROOT, "  %-60s calls=%-4d p50=%-6d p95=%-6d max=%-6d limit=%-6s suggested=%s%n",
                    t.getKey(), times.size(), percentile(times, 50), p95(times), percentile(times, 100),
                    limit == 0 ? "none" : String.valueOf(limit),
                    times.size() >= MIN_CALLS_FOR_SUGGESTION ? String.valueOf(suggestedLimit(times)) : "n/a"));
            });
        return text.toString();
    }

    /** p95 x 1.5, rounded up to the next 100 ms (a limit the endpoint's own normal spread won't trip). */
    static long suggestedLimit(List<Long> millis) {
        long raw = (long) Math.ceil(p95(millis) * 1.5);
        return ((raw + 99) / 100) * 100;
    }

    /** Nearest-rank percentile (1-100) of the values. */
    static long percentile(List<Long> millis, int percent) {
        List<Long> sorted = new ArrayList<>(millis);
        sorted.sort(Long::compare);
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static long p95(List<Long> millis) {
        return percentile(millis, 95);
    }

    private static List<Long> millis(List<Entry> entries) {
        List<Long> list = new ArrayList<>();
        for (Entry e : entries) {
            list.add(e.sample().millis());
        }
        return list;
    }

    private static String describe(Sample sample) {
        switch (sample.kind()) {
            case API:
                return "API " + sample.name();
            case SCREEN:
                return "screen " + sample.name();
            default:
                return "page " + sample.name();
        }
    }
}
