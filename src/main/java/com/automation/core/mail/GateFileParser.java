package com.automation.core.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the per-leg result files written by {@code Scripts/test-gate.sh record}
 * ({@code target/gate/<leg>.txt}, one line: {@code label=X status=ok total=N passed=N failed=N skipped=N}).
 *
 * <p>Why: GitLab and Jenkins merge job artifacts by file name, so of many matrix legs only ONE
 * {@code testng-results.xml} survives - but these gate files have unique names per leg and all survive.
 * A consolidated e-mail built from them reports every leg. Each failing leg appears in the failure table
 * (the individual failed tests are only available where the full results survive).
 */
public final class GateFileParser {

    private GateFileParser() {
    }

    /** Aggregates every {@code *.txt} under {@code dir}; returns {@code null} when there are none. */
    public static RunSummary parseDirectory(Path dir, String site, String browser) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(p -> p.getFileName().toString().endsWith(".txt")).filter(Files::isRegularFile)
                    .sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            return null;
        }
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        List<RunSummary.FailedTest> legs = new ArrayList<>();
        for (Path file : files) {
            Map<String, String> kv = parseLine(Files.readString(file));
            String label = kv.getOrDefault("label", file.getFileName().toString().replaceFirst("\\.txt$", ""));
            if (!"ok".equals(kv.get("status"))) {
                legs.add(new RunSummary.FailedTest(label, "leg", "no readable results (" + kv.get("status") + ")"));
                continue;
            }
            int p = num(kv, "passed");
            int f = num(kv, "failed");
            int s = num(kv, "skipped");
            passed += p;
            failed += f;
            skipped += s;
            if (f > 0) {
                legs.add(new RunSummary.FailedTest(label, "leg", f + " test(s) failed"));
            } else if (p + f + s == 0) {
                legs.add(new RunSummary.FailedTest(label, "leg", "0 tests ran"));
            }
        }
        String suite = files.size() + " test leg" + (files.size() == 1 ? "" : "s");
        return new RunSummary(site, browser, suite, passed + failed + skipped, passed, failed, skipped, 0,
            Instant.now(), legs, "", null);
    }

    static Map<String, String> parseLine(String text) {
        Map<String, String> kv = new HashMap<>();
        for (String token : text.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                kv.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return kv;
    }

    private static int num(Map<String, String> kv, String key) {
        try {
            return Integer.parseInt(kv.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
