package com.automation.core.telegram;

import com.automation.core.mail.RunSummary;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real sender against a throwaway HTTP server on localhost - nothing leaves the machine. */
class TelegramServiceTest {

    private static final String TOKEN = "123456:SECRET-token_value";

    /** method name ("sendMessage"/"sendDocument") + the raw request body, in arrival order. */
    private record Call(String path, String body) {
    }

    private static HttpServer server(List<Call> calls, int status, String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(buf);
            calls.add(new Call(exchange.getRequestURI().getPath(), buf.toString(StandardCharsets.ISO_8859_1)));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Map<String, String> cfg(HttpServer server, Path dir) {
        Map<String, String> m = new HashMap<>();
        m.put("telegram.enabled", "true");
        m.put("telegram.bot.token", TOKEN);
        m.put("telegram.chat.id", "-1001");
        m.put("telegram.apiBase", "http://127.0.0.1:" + server.getAddress().getPort());
        m.put("telegram.timeoutMs", "5000");
        m.put("report.pdf.output.dir", dir.resolve("pdf").toString());
        m.put("report.zip.output", dir.resolve("none.zip").toString());
        m.put("telegram.reports.dir", dir.resolve("none").toString());
        m.put("telegram.dryRun.dir", dir.resolve("dry").toString());
        return m;
    }

    private static RunSummary run(int passed, int failed) {
        return new RunSummary("demoqa", "chrome", "Smoke", passed + failed, passed, failed, 0, 1000,
            Instant.now(), failed == 0 ? List.of()
            : List.of(new RunSummary.FailedTest("login", "LoginTest", "boom")), "", null);
    }

    @Test
    void sendsTheMessageAndUploadsTheFreshPdf(@TempDir Path dir) throws Exception {
        List<Call> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server(calls, 200, "{\"ok\":true,\"result\":{}}");
        try {
            Files.createDirectories(dir.resolve("pdf"));
            Files.writeString(dir.resolve("pdf").resolve("report.pdf"), "%PDF-1.4 fake");
            Map<String, String> m = cfg(server, dir);
            TelegramService.Outcome o = TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 1),
                Instant.now().minusSeconds(60), Map.of());
            assertTrue(o.sent(), o.message());
            assertEquals(2, calls.size());
            assertEquals("/bot" + TOKEN + "/sendMessage", calls.get(0).path());
            assertTrue(calls.get(0).body().contains("\"chat_id\":\"-1001\""));
            assertTrue(calls.get(0).body().contains("\"parse_mode\":\"HTML\""));
            assertTrue(calls.get(0).body().contains("FAILED"));
            assertEquals("/bot" + TOKEN + "/sendDocument", calls.get(1).path());
            assertTrue(calls.get(1).body().contains("filename=\"report.pdf\""));
            assertTrue(calls.get(1).body().contains("%PDF-1.4 fake"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aStaleReportFromAnEarlierRunIsNotUploaded(@TempDir Path dir) throws Exception {
        List<Call> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server(calls, 200, "{\"ok\":true}");
        try {
            Files.createDirectories(dir.resolve("pdf"));
            Path old = dir.resolve("pdf").resolve("old.pdf");
            Files.writeString(old, "old");
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));
            Map<String, String> m = cfg(server, dir);
            TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 0), Instant.now().minusSeconds(60),
                Map.of());
            assertEquals(1, calls.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendOnFailureSkipsGreenRuns(@TempDir Path dir) throws Exception {
        List<Call> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server(calls, 200, "{\"ok\":true}");
        try {
            Map<String, String> m = cfg(server, dir);
            m.put("telegram.sendOn", "failure");
            TelegramSettings s = TelegramSettings.of(m::get, Map.of());
            assertFalse(TelegramService.deliver(s, run(3, 0), null, Map.of()).sent());
            assertEquals(0, calls.size());
            assertTrue(TelegramService.deliver(s, run(2, 1), null, Map.of()).sent());
            assertEquals(1, calls.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRejectedMessageThrowsWithoutLeakingTheToken(@TempDir Path dir) throws Exception {
        List<Call> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server(calls, 401,
            "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized " + TOKEN + "\"}");
        try {
            Map<String, String> m = cfg(server, dir);
            TelegramException e = assertThrows(TelegramException.class,
                () -> TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 0), null, Map.of()));
            assertTrue(e.getMessage().contains("401"));
            assertFalse(e.getMessage().contains("SECRET-token_value"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFailedUploadDoesNotThrowOnceTheSummaryWasDelivered(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            boolean doc = exchange.getRequestURI().getPath().endsWith("sendDocument");
            byte[] out = (doc ? "{\"ok\":false,\"description\":\"file is too big\"}" : "{\"ok\":true}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(doc ? 413 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            Files.createDirectories(dir.resolve("pdf"));
            Files.writeString(dir.resolve("pdf").resolve("r.pdf"), "x");
            Map<String, String> m = cfg(server, dir);
            TelegramService.Outcome o = TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 0),
                Instant.now().minusSeconds(60), Map.of());
            assertTrue(o.sent());
            assertTrue(o.message().contains("NOT uploaded"), o.message());
            assertTrue(o.message().contains("file is too big"), o.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dryRunWritesAPreviewAndSendsNothing(@TempDir Path dir) throws Exception {
        List<Call> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server(calls, 200, "{\"ok\":true}");
        try {
            Map<String, String> m = cfg(server, dir);
            m.put("telegram.dryRun", "true");
            m.remove("telegram.bot.token");
            TelegramService.Outcome o = TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 1), null,
                Map.of());
            assertTrue(o.sent());
            assertEquals(0, calls.size());
            try (Stream<Path> s = Files.list(dir.resolve("dry"))) {
                List<Path> files = s.toList();
                assertEquals(1, files.size());
                assertTrue(Files.readString(files.get(0)).contains("FAILED"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledAndNeverSendNothing(@TempDir Path dir) throws Exception {
        List<Call> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server(calls, 200, "{\"ok\":true}");
        try {
            Map<String, String> m = cfg(server, dir);
            m.put("telegram.enabled", "false");
            assertFalse(TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 1), null, Map.of()).sent());
            m.put("telegram.enabled", "true");
            m.put("telegram.sendOn", "never");
            assertFalse(TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 1), null, Map.of()).sent());
            assertEquals(0, calls.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unusableSettingsAreReportedNotSilentlySkipped() {
        Map<String, String> m = Map.of("telegram.enabled", "true");
        assertThrows(TelegramException.class,
            () -> TelegramService.deliver(TelegramSettings.of(m::get, Map.of()), run(1, 0), null, Map.of()));
    }

    @Test
    void jsonQuotingEscapesControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", TelegramSender.quote("a\"b\\c\nd\u0001"));
    }
}
