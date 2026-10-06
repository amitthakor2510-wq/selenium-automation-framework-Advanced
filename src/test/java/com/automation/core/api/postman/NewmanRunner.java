package com.automation.core.api.postman;

import com.automation.core.config.ConfigReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs an exported Postman collection with Newman (Postman's command-line runner) and
 * summarises the outcome, so a Postman collection can be one test in this framework.
 *
 * One-time setup:  npm install -g newman newman-reporter-htmlextra
 * (htmlextra is optional; without it a plain HTML summary is generated instead.)
 *
 * Config keys (all optional):
 *   postman.newman.path         full path to newman if it isn't on PATH
 *   postman.reporter.htmlextra  true (default) - use the htmlextra HTML report when installed
 *   postman.timeoutRequestMs    per-request timeout, default 30000
 *   postman.delayRequestMs      pause between requests, default 0
 *   postman.insecure            true to accept self-signed TLS certificates, default false
 */
public final class NewmanRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NewmanRunner() {
    }

    public record Result(int exitCode, int requestsTotal, int requestsFailed,
                         int assertionsTotal, int assertionsFailed, List<String> failures,
                         Path htmlReport, Path jsonReport, Path consoleLog) {

        public boolean passed() {
            return exitCode == 0 && requestsFailed == 0 && assertionsFailed == 0;
        }

        public String summary() {
            return requestsTotal + " requests (" + requestsFailed + " failed), "
                + assertionsTotal + " assertions (" + assertionsFailed + " failed), exit code " + exitCode;
        }
    }

    private record Proc(int exitCode, String output) {
    }

    /** True if the newman executable can be started. */
    public static boolean isInstalled() {
        return exec(List.of(executable(), "--version")).exitCode() == 0;
    }

    public static Result run(Path collection, Path environment, Map<String, String> envVars, Path reportDir)
            throws IOException {
        Files.createDirectories(reportDir);
        String name = baseName(collection);
        Path json = reportDir.resolve(name + ".json");
        Path html = reportDir.resolve(name + ".html");
        Path console = reportDir.resolve(name + "-console.txt");

        boolean htmlextra = ConfigReader.getBoolean("postman.reporter.htmlextra", true);
        Proc proc = exec(command(collection, environment, envVars, json, html, htmlextra, name));
        if (htmlextra && proc.exitCode() != 0
                && proc.output().toLowerCase(Locale.ROOT).contains("could not find")
                && proc.output().contains("htmlextra")) {
            htmlextra = false; // reporter not installed - rerun with the built-in reporters only
            proc = exec(command(collection, environment, envVars, json, html, false, name));
        }
        Files.writeString(console, proc.output(), StandardCharsets.UTF_8);

        List<String> failures = new ArrayList<>();
        int requestsTotal = 0;
        int requestsFailed = 0;
        int assertionsTotal = 0;
        int assertionsFailed = 0;

        if (Files.exists(json)) {
            JsonNode run = MAPPER.readTree(json.toFile()).path("run");
            JsonNode stats = run.path("stats");
            requestsTotal = stats.path("requests").path("total").asInt();
            requestsFailed = stats.path("requests").path("failed").asInt();
            assertionsTotal = stats.path("assertions").path("total").asInt();
            assertionsFailed = stats.path("assertions").path("failed").asInt();
            for (JsonNode failure : run.path("failures")) {
                JsonNode error = failure.path("error");
                String what = error.path("test").asText(error.path("name").asText("error"));
                failures.add("[" + failure.path("source").path("name").asText("?") + "] "
                    + what + " - " + error.path("message").asText(""));
            }
            if (!htmlextra) {
                Files.writeString(html, plainHtml(name, run), StandardCharsets.UTF_8);
            }
        } else {
            failures.add("Newman produced no JSON report (exit code " + proc.exitCode()
                + "). Last output: " + tail(proc.output(), 600));
        }
        return new Result(proc.exitCode(), requestsTotal, requestsFailed, assertionsTotal, assertionsFailed,
            failures, html, json, console);
    }

    // ------------------------------------------------------------------ internals

    private static List<String> command(Path collection, Path environment, Map<String, String> envVars,
                                        Path json, Path html, boolean htmlextra, String title) {
        List<String> c = new ArrayList<>();
        c.add(executable());
        c.add("run");
        c.add(collection.toAbsolutePath().toString());
        if (environment != null) {
            c.add("-e");
            c.add(environment.toAbsolutePath().toString());
        }
        envVars.forEach((k, v) -> {
            c.add("--env-var");
            c.add(k + "=" + v);
        });
        c.add("-r");
        c.add(htmlextra ? "cli,json,htmlextra" : "cli,json");
        c.add("--reporter-json-export");
        c.add(json.toAbsolutePath().toString());
        if (htmlextra) {
            c.add("--reporter-htmlextra-export");
            c.add(html.toAbsolutePath().toString());
            c.add("--reporter-htmlextra-title");
            c.add(title);
        }
        c.add("--timeout-request");
        c.add(String.valueOf(ConfigReader.getInt("postman.timeoutRequestMs", 30000)));
        int delay = ConfigReader.getInt("postman.delayRequestMs", 0);
        if (delay > 0) {
            c.add("--delay-request");
            c.add(String.valueOf(delay));
        }
        if (ConfigReader.getBoolean("postman.insecure", false)) {
            c.add("--insecure");
        }
        c.add("--color");
        c.add("off");
        return c;
    }

    private static String executable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return ConfigReader.getNonBlank("postman.newman.path", windows ? "newman.cmd" : "newman");
    }

    private static Proc exec(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Proc(process.waitFor(), output);
        } catch (IOException e) {
            return new Proc(-1, "Could not start '" + command.get(0) + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Proc(-1, "Interrupted while running newman");
        }
    }

    private static String baseName(Path collection) {
        String file = collection.getFileName().toString();
        String name = file.replaceFirst("(?i)\\.postman_collection\\.json$", "").replaceFirst("(?i)\\.json$", "");
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String tail(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : "..." + oneLine.substring(oneLine.length() - max);
    }

    /** Minimal self-contained report used when the htmlextra reporter isn't installed. */
    private static String plainHtml(String title, JsonNode run) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title>")
            .append("<style>body{font-family:sans-serif;margin:2rem}table{border-collapse:collapse;width:100%}")
            .append("td,th{border:1px solid #ccc;padding:6px 10px;text-align:left}.pass{color:#0a7a2f}.fail{color:#b00020}</style>")
            .append("</head><body><h1>").append(esc(title)).append("</h1>");
        JsonNode stats = run.path("stats");
        sb.append("<p>Requests: ").append(stats.path("requests").path("total").asInt())
            .append(" (failed ").append(stats.path("requests").path("failed").asInt()).append(") &middot; Assertions: ")
            .append(stats.path("assertions").path("total").asInt())
            .append(" (failed ").append(stats.path("assertions").path("failed").asInt()).append(")</p>");
        sb.append("<table><tr><th>Request</th><th>Status</th><th>Time (ms)</th><th>Assertion</th><th>Result</th></tr>");
        for (JsonNode execution : run.path("executions")) {
            String request = execution.path("item").path("name").asText("?");
            String status = execution.path("response").path("code").asText("-");
            String time = execution.path("response").path("responseTime").asText("-");
            JsonNode assertions = execution.path("assertions");
            if (!assertions.isArray() || assertions.size() == 0) {
                sb.append("<tr><td>").append(esc(request)).append("</td><td>").append(esc(status))
                    .append("</td><td>").append(esc(time)).append("</td><td colspan=\"2\">(no assertions)</td></tr>");
                continue;
            }
            for (JsonNode assertion : assertions) {
                boolean failed = assertion.has("error");
                sb.append("<tr><td>").append(esc(request)).append("</td><td>").append(esc(status))
                    .append("</td><td>").append(esc(time)).append("</td><td>")
                    .append(esc(assertion.path("assertion").asText(""))).append("</td><td class=\"")
                    .append(failed ? "fail\">FAIL: " + esc(assertion.path("error").path("message").asText("")) : "pass\">PASS")
                    .append("</td></tr>");
            }
        }
        return sb.append("</table></body></html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
