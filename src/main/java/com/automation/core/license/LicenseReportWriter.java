package com.automation.core.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Writes the scan result as {@code license-report.html} (human), {@code license-report.json} (tools),
 * {@code license-report.txt} (console/CI log) and {@code license-summary.properties} (counts only -
 * what the e-mail report reads, so a scan run in one JVM can be mailed from another).
 */
public final class LicenseReportWriter {

    public static final String HTML = "license-report.html";
    public static final String JSON = "license-report.json";
    public static final String TEXT = "license-report.txt";
    public static final String SUMMARY = "license-summary.properties";

    /** Counts-only view of a finished scan. */
    public record Summary(int total, int allowed, int review, int denied, int missing, int failing) {
    }

    private LicenseReportWriter() {
    }

    public static void write(LicenseScanResult result, Path dir) throws IOException {
        Files.createDirectories(dir);
        String text = text(result);
        Files.writeString(dir.resolve(TEXT), text, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(HTML), html(result), StandardCharsets.UTF_8);
        writeJson(result, dir.resolve(JSON));
        writeSummary(result, dir.resolve(SUMMARY));
    }

    /** Reads {@code license-summary.properties} from {@code dir}; empty when absent or unreadable. */
    public static Optional<Summary> readSummary(Path dir) {
        Path file = dir.resolve(SUMMARY);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
            return Optional.of(new Summary(num(p, "total"), num(p, "allowed"), num(p, "review"),
                num(p, "denied"), num(p, "missing"), num(p, "failing")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int num(Properties p, String key) {
        try {
            return Integer.parseInt(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeSummary(LicenseScanResult r, Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("total", String.valueOf(r.dependencies().size()));
        p.setProperty("allowed", String.valueOf(r.count(LicenseStatus.ALLOWED)));
        p.setProperty("review", String.valueOf(r.count(LicenseStatus.REVIEW)));
        p.setProperty("denied", String.valueOf(r.count(LicenseStatus.DENIED)));
        p.setProperty("missing", String.valueOf(r.count(LicenseStatus.MISSING)));
        p.setProperty("failing", String.valueOf(r.violations().size()));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "License scan summary");
        }
    }

    private static void writeJson(LicenseScanResult r, Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("counts", r.counts());
        root.put("ignored", r.ignored());
        root.put("failing", r.violations().size());
        root.put("dependencies", r.dependencies());
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(file.toFile(), root);
    }

    /** Plain-text report, also what the CLI prints. */
    public static String text(LicenseScanResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("License scan: ").append(r.dependencies().size()).append(" dependencies - ")
            .append(r.count(LicenseStatus.ALLOWED)).append(" allowed, ")
            .append(r.count(LicenseStatus.REVIEW)).append(" need review, ")
            .append(r.count(LicenseStatus.DENIED)).append(" denied, ")
            .append(r.count(LicenseStatus.MISSING)).append(" with no license found");
        if (r.ignored() > 0) {
            sb.append(" (").append(r.ignored()).append(" ignored by policy)");
        }
        sb.append('\n');
        for (DependencyLicense d : r.dependencies()) {
            if (d.status() == LicenseStatus.ALLOWED) {
                continue;
            }
            sb.append(String.format("  [%s] %s  ->  %s%s%n", d.status(), d.coordinates(),
                d.licenseIds().isEmpty() ? "(none)" : String.join(" | ", d.licenseIds()),
                d.note().isEmpty() ? "" : "  (" + d.note() + ")"));
        }
        return sb.toString();
    }

    /** Self-contained HTML (inline CSS, every dynamic value escaped). */
    public static String html(LicenseScanResult r) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
            .append("<title>Dependency license report</title><style>")
            .append("body{font-family:Arial,Helvetica,sans-serif;margin:24px;color:#222}")
            .append("table{border-collapse:collapse;width:100%;font-size:13px}")
            .append("th,td{border:1px solid #ccc;padding:6px 8px;text-align:left;vertical-align:top}")
            .append("th{background:#f2f2f2}.DENIED{background:#ffd6d6}.REVIEW{background:#fff0c2}")
            .append(".MISSING{background:#e6e6e6}.ALLOWED{background:#e3f6e3}")
            .append(".sum span{display:inline-block;margin-right:16px;font-weight:bold}")
            .append("</style></head><body><h1>Dependency license report</h1>");
        sb.append("<p class=\"sum\"><span>Total: ").append(r.dependencies().size()).append("</span>")
            .append("<span>Allowed: ").append(r.count(LicenseStatus.ALLOWED)).append("</span>")
            .append("<span>Review: ").append(r.count(LicenseStatus.REVIEW)).append("</span>")
            .append("<span>Denied: ").append(r.count(LicenseStatus.DENIED)).append("</span>")
            .append("<span>No license: ").append(r.count(LicenseStatus.MISSING)).append("</span></p>");
        sb.append("<table><tr><th>Status</th><th>Dependency</th><th>License(s)</th>")
            .append("<th>Declared as</th><th>Source</th><th>Note</th></tr>");
        for (DependencyLicense d : r.dependencies()) {
            sb.append("<tr class=\"").append(d.status()).append("\"><td>").append(d.status()).append("</td><td>")
                .append(escape(d.coordinates())).append("</td><td>").append(escape(join(d.licenseIds())))
                .append("</td><td>").append(escape(join(d.declared()))).append("</td><td>")
                .append(escape(d.source())).append("</td><td>").append(escape(d.note())).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "-" : String.join(" | ", values);
    }

    /** Minimal HTML escaping for text and attribute-free contexts. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
