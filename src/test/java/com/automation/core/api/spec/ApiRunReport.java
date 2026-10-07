package com.automation.core.api.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Collects the result of every spec case and writes ONE self-contained HTML page (plus results.json)
 * you can open offline or email - no server, no Allure install needed. Secrets (Authorization, API
 * keys, tokens, passwords) are masked before anything is written.
 */
public final class ApiRunReport {

    public enum Outcome { PASSED, FAILED, SKIPPED }

    /** One row of the report. */
    public record Entry(String spec, String name, String method, String url, int status, long timeMs,
                        Outcome outcome, List<String> failures, String requestText, String responseText) {
    }

    private static final Pattern SECRET_JSON = Pattern.compile(
        "(\"(?:password|passwd|secret|token|access_token|refresh_token|id_token|api_key|apikey|client_secret)\"\\s*:\\s*)\"[^\"]*\"",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_HEADER_NAME = Pattern.compile(
        "(?i)authorization|api[-_]?key|token|secret|cookie|password");

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
    private final Instant started = Instant.now();

    public void add(Entry entry) {
        entries.add(entry);
    }

    public List<Entry> entries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public long count(Outcome outcome) {
        return entries().stream().filter(e -> e.outcome() == outcome).count();
    }

    /** True when nothing failed. */
    public boolean allGreen() {
        return count(Outcome.FAILED) == 0;
    }

    public static boolean isSecretHeader(String name) {
        return name != null && SECRET_HEADER_NAME.matcher(name).find();
    }

    /** Masks secret-looking JSON fields so a shared report does not leak credentials. */
    public static String maskSecrets(String text) {
        if (text == null) {
            return "";
        }
        return SECRET_JSON.matcher(text).replaceAll("$1\"***\"");
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n... (" + (text.length() - max) + " more characters)";
    }

    /** Writes index.html and results.json into {@code dir}; returns the HTML path. */
    public Path write(Path dir, String title) throws IOException {
        Files.createDirectories(dir);
        List<Entry> rows = entries();
        Path html = dir.resolve("index.html");
        Files.writeString(html, renderHtml(rows, title), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("results.json"), renderJson(rows), StandardCharsets.UTF_8);
        return html;
    }

    String renderHtml(List<Entry> rows, String title) {
        long passed = rows.stream().filter(e -> e.outcome() == Outcome.PASSED).count();
        long failed = rows.stream().filter(e -> e.outcome() == Outcome.FAILED).count();
        long skipped = rows.stream().filter(e -> e.outcome() == Outcome.SKIPPED).count();
        long totalTime = rows.stream().mapToLong(Entry::timeMs).sum();
        long avg = rows.isEmpty() ? 0 : totalTime / rows.size();
        long slowest = rows.stream().mapToLong(Entry::timeMs).max().orElse(0);
        int pct = rows.isEmpty() ? 0 : (int) Math.round(100.0 * passed / Math.max(1, rows.size() - skipped));

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<title>").append(esc(title)).append("</title><style>")
            .append("body{font:14px/1.45 system-ui,Segoe UI,Roboto,sans-serif;margin:0;background:#f4f6f8;color:#1c2430}")
            .append("header{background:#1c2430;color:#fff;padding:20px 28px}header h1{margin:0 0 4px;font-size:20px}")
            .append("header small{color:#9fb0c3}main{max-width:1180px;margin:0 auto;padding:20px 28px}")
            .append(".cards{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:18px}")
            .append(".card{background:#fff;border-radius:8px;padding:12px 18px;min-width:120px;box-shadow:0 1px 2px #0002}")
            .append(".card b{display:block;font-size:24px}.pass{color:#18794e}.fail{color:#c62828}.skip{color:#8a6d00}")
            .append(".bar{height:8px;background:#e1e6eb;border-radius:4px;overflow:hidden;margin-bottom:16px}")
            .append(".bar i{display:block;height:100%;background:#18794e;width:").append(pct).append("%}")
            .append("button{border:1px solid #c5ced8;background:#fff;border-radius:6px;padding:5px 12px;cursor:pointer;margin-right:6px}")
            .append("button.on{background:#1c2430;color:#fff}table{width:100%;border-collapse:collapse;background:#fff;margin-top:12px;border-radius:8px;overflow:hidden}")
            .append("th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef1f4;vertical-align:top}th{background:#eef1f4;font-size:12px;text-transform:uppercase;letter-spacing:.04em}")
            .append(".tag{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;font-weight:600}")
            .append(".tag.PASSED{background:#d9f2e4;color:#18794e}.tag.FAILED{background:#fbdada;color:#c62828}.tag.SKIPPED{background:#fff1c2;color:#8a6d00}")
            .append("pre{background:#0f1720;color:#d7e0ea;padding:10px;border-radius:6px;overflow:auto;max-height:260px;margin:6px 0;white-space:pre-wrap;word-break:break-word}")
            .append("code.m{font-weight:700}details summary{cursor:pointer;color:#2457c5}ul.f{margin:4px 0;padding-left:18px;color:#c62828}")
            .append("</style></head><body><header><h1>").append(esc(title)).append("</h1><small>Generated ")
            .append(esc(Instant.now().toString())).append(" &middot; started ").append(esc(started.toString()))
            .append("</small></header><main><div class=\"cards\">")
            .append(card("Total", String.valueOf(rows.size()), ""))
            .append(card("Passed", String.valueOf(passed), "pass"))
            .append(card("Failed", String.valueOf(failed), "fail"))
            .append(card("Skipped", String.valueOf(skipped), "skip"))
            .append(card("Avg time", avg + " ms", ""))
            .append(card("Slowest", slowest + " ms", ""))
            .append("</div><div class=\"bar\"><i></i></div>")
            .append("<div id=\"f\"><button class=\"on\" data-f=\"all\">All</button><button data-f=\"FAILED\">Failed</button>")
            .append("<button data-f=\"PASSED\">Passed</button><button data-f=\"SKIPPED\">Skipped</button></div>")
            .append("<table><thead><tr><th>Result</th><th>Test</th><th>Request</th><th>HTTP</th><th>Time</th></tr></thead><tbody>");

        for (Entry e : rows) {
            sb.append("<tr data-o=\"").append(e.outcome()).append("\"><td><span class=\"tag ").append(e.outcome()).append("\">")
                .append(e.outcome()).append("</span></td><td><b>").append(esc(e.name())).append("</b><br><small>")
                .append(esc(e.spec())).append("</small>");
            if (!e.failures().isEmpty()) {
                sb.append("<ul class=\"f\">");
                e.failures().forEach(f -> sb.append("<li>").append(esc(f)).append("</li>"));
                sb.append("</ul>");
            }
            if (!e.requestText().isBlank() || !e.responseText().isBlank()) {
                sb.append("<details><summary>request &amp; response</summary><pre>").append(esc(e.requestText()))
                    .append("</pre><pre>").append(esc(e.responseText())).append("</pre></details>");
            }
            sb.append("</td><td><code class=\"m\">").append(esc(e.method())).append("</code> ").append(esc(e.url()))
                .append("</td><td>").append(e.status() > 0 ? String.valueOf(e.status()) : "-").append("</td><td>")
                .append(e.timeMs()).append(" ms</td></tr>");
        }
        sb.append("</tbody></table></main><script>")
            .append("document.querySelectorAll('#f button').forEach(function(b){b.onclick=function(){")
            .append("document.querySelectorAll('#f button').forEach(function(x){x.className=''});b.className='on';")
            .append("var f=b.getAttribute('data-f');document.querySelectorAll('tbody tr').forEach(function(r){")
            .append("r.style.display=(f==='all'||r.getAttribute('data-o')===f)?'':'none'})}});")
            .append("</script></body></html>");
        return sb.toString();
    }

    private static String card(String label, String value, String css) {
        return "<div class=\"card\"><span>" + label + "</span><b class=\"" + css + "\">" + esc(value) + "</b></div>";
    }

    String renderJson(List<Entry> rows) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < rows.size(); i++) {
            Entry e = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"spec\":").append(js(e.spec())).append(",\"name\":").append(js(e.name()))
                .append(",\"method\":").append(js(e.method())).append(",\"url\":").append(js(e.url()))
                .append(",\"status\":").append(e.status()).append(",\"timeMs\":").append(e.timeMs())
                .append(",\"outcome\":").append(js(e.outcome().name().toLowerCase(Locale.ROOT))).append(",\"failures\":[");
            for (int f = 0; f < e.failures().size(); f++) {
                sb.append(f > 0 ? "," : "").append(js(e.failures().get(f)));
            }
            sb.append("]}");
        }
        sb.append("],\"passed\":").append(count(Outcome.PASSED)).append(",\"failed\":").append(count(Outcome.FAILED))
            .append(",\"skipped\":").append(count(Outcome.SKIPPED)).append('}');
        return sb.toString();
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String esc(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
