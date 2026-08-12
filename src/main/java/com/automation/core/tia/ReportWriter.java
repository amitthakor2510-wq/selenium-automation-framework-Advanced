package com.automation.core.tia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes an {@link ImpactResult} out in every format the rest of the toolchain needs:
 * <ul>
 *   <li>{@code mode.txt} — {@code FULL} or {@code IMPACTED}, read by {@code Scripts/test-impact-analysis.sh}</li>
 *   <li>{@code impacted-tests.txt} — flat FQCN list, one per line, sorted</li>
 *   <li>{@code impacted-tests-<site>.txt} + {@code testng-impacted-<site>.xml} — per site, since this
 *       framework selects its site via a single {@code -Dsite=...} JVM system property and can't mix
 *       sites in one {@code mvn test} run (see {@code ConfigReader}'s own javadoc on that constraint)</li>
 *   <li>{@code impact-report.md} — human-readable summary for a CI job summary / PR comment</li>
 * </ul>
 */
public final class ReportWriter {

    private final Path outputDir;

    public ReportWriter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public void write(ImpactResult result) {
        try {
            Files.createDirectories(outputDir);
            writeMode(result);
            if (result.mode() == ImpactResult.Mode.FULL) {
                writeFullSuiteReport(result);
                return;
            }
            writeFlatList(result);
            Map<String, List<String>> bySite = groupBySite(result);
            for (Map.Entry<String, List<String>> e : bySite.entrySet()) {
                writePerSiteList(e.getKey(), e.getValue());
                writePerSiteSuiteXml(e.getKey(), e.getValue());
            }
            writeImpactedReport(result, bySite);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeMode(ImpactResult result) throws IOException {
        Files.writeString(outputDir.resolve("mode.txt"), result.mode().name() + "\n", StandardCharsets.UTF_8);
    }

    private void writeFlatList(ImpactResult result) throws IOException {
        String content = String.join("\n", new TreeSet<>(result.impactedTests().keySet())) + "\n";
        Files.writeString(outputDir.resolve("impacted-tests.txt"), content, StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> groupBySite(ImpactResult result) {
        Map<String, List<String>> bySite = new TreeMap<>();
        for (String fqcn : result.impactedTests().keySet()) {
            String site = SiteMapper.siteOfTestClass(fqcn).orElse("other");
            bySite.computeIfAbsent(site, k -> new java.util.ArrayList<>()).add(fqcn);
        }
        bySite.values().forEach(java.util.Collections::sort);
        return bySite;
    }

    private void writePerSiteList(String site, List<String> classes) throws IOException {
        Files.writeString(outputDir.resolve("impacted-tests-" + site + ".txt"),
            String.join(",", classes) + "\n", StandardCharsets.UTF_8);
    }

    private void writePerSiteSuiteXml(String site, List<String> classes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE suite SYSTEM \"https://testng.org/testng-1.0.dtd\">\n");
        sb.append("<suite name=\"TIA-Impacted-").append(site).append("\" verbose=\"1\">\n");
        sb.append("  <test name=\"Impacted\">\n");
        sb.append("    <classes>\n");
        for (String fqcn : classes) {
            sb.append("      <class name=\"").append(fqcn).append("\"/>\n");
        }
        sb.append("    </classes>\n");
        sb.append("  </test>\n");
        sb.append("</suite>\n");
        Files.writeString(outputDir.resolve("testng-impacted-" + site + ".xml"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeImpactedReport(ImpactResult result, Map<String, List<String>> bySite) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Test Impact Analysis\n\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");
        int impactedCount = result.impactedTests().size();
        int total = result.totalTestClassesInProject();
        sb.append("**Mode:** IMPACTED\n\n");
        if (total > 0) {
            double pct = 100.0 * impactedCount / total;
            sb.append(String.format(
                "**%d / %d** test classes selected (%.1f%%) — skipping %d.%n%n",
                impactedCount, total, pct, total - impactedCount));
        }
        sb.append("## Changed files (").append(result.changedFiles().size()).append(")\n\n");
        for (ChangedFile f : result.changedFiles()) {
            sb.append("- `").append(f.type()).append("` ").append(f.path());
            if (f.oldPath() != null) {
                sb.append(" (renamed from `").append(f.oldPath()).append("`)");
            }
            sb.append("\n");
        }
        sb.append("\n## Impacted tests by site\n\n");
        for (Map.Entry<String, List<String>> e : bySite.entrySet()) {
            sb.append("### ").append(e.getKey()).append(" (").append(e.getValue().size()).append(")\n\n");
            for (String fqcn : e.getValue()) {
                sb.append("- `").append(fqcn).append("`\n");
                for (ImpactResult.ImpactReasonDetail detail : result.impactedTests().getOrDefault(fqcn, List.of())) {
                    sb.append("  - ").append(detail).append("\n");
                }
            }
            sb.append("\n");
        }
        Files.writeString(outputDir.resolve("impact-report.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeFullSuiteReport(ImpactResult result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Test Impact Analysis\n\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");
        sb.append("**Mode:** FULL — running the entire suite, not a filtered subset.\n\n");
        sb.append("## Why\n\n");
        for (String reason : result.unsafeReasons()) {
            sb.append("- ").append(reason).append("\n");
        }
        sb.append("\n## Changed files (").append(result.changedFiles().size()).append(")\n\n");
        for (ChangedFile f : result.changedFiles()) {
            sb.append("- `").append(f.type()).append("` ").append(f.path()).append("\n");
        }
        Files.writeString(outputDir.resolve("impact-report.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    /** Convenience for the report/CLI: FQCN -&gt; site, when known. */
    static Optional<String> siteOf(String fqcn) {
        return SiteMapper.siteOfTestClass(fqcn);
    }
}
