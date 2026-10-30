package com.automation.core.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Telegram Bot API ({@code sendMessage}, {@code sendDocument}) with the JDK's own
 * {@link HttpClient} - no extra dependency. The bot token is part of every request URL, so it is
 * scrubbed from every error message and no underlying exception is chained (its text could contain
 * the URL). Redirects are never followed.
 */
public final class TelegramSender {

    private static final Pattern DESCRIPTION = Pattern.compile("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");
    private static final int MAX_RETRY_WAIT_SECONDS = 10;

    private final TelegramSettings settings;
    private final HttpClient client;

    public TelegramSender(TelegramSettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** Sends one HTML-formatted text message. */
    public void sendMessage(String htmlText) throws TelegramException {
        StringBuilder json = new StringBuilder("{");
        json.append("\"chat_id\":").append(quote(settings.chatId()));
        json.append(",\"text\":").append(quote(htmlText));
        json.append(",\"parse_mode\":\"HTML\"");
        json.append(",\"disable_web_page_preview\":true");
        json.append(",\"disable_notification\":").append(settings.silent());
        if (!settings.threadId().isBlank()) {
            json.append(",\"message_thread_id\":").append(settings.threadId());
        }
        json.append('}');
        HttpRequest request = builder("sendMessage")
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
            .build();
        execute(request, "sendMessage");
    }

    /** Uploads one file as a document. */
    public void sendDocument(Path file, String name) throws TelegramException {
        String boundary = "----tg" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder head = new StringBuilder();
        field(head, boundary, "chat_id", settings.chatId());
        field(head, boundary, "disable_notification", String.valueOf(settings.silent()));
        if (!settings.threadId().isBlank()) {
            field(head, boundary, "message_thread_id", settings.threadId());
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"document\"; filename=\"")
            .append(safeFileName(name)).append("\"\r\n")
            .append("Content-Type: application/octet-stream\r\n\r\n");
        String tail = "\r\n--" + boundary + "--\r\n";
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(head.toString(), StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofString(tail, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TelegramException("Cannot read " + file + ": " + redact(e.getMessage()));
        }
        HttpRequest request = builder("sendDocument")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body)
            .build();
        execute(request, "sendDocument " + name);
    }

    private HttpRequest.Builder builder(String method) {
        return HttpRequest.newBuilder(URI.create(settings.apiBase() + "/bot" + settings.token() + "/" + method))
            .timeout(Duration.ofMillis(settings.timeoutMillis()));
    }

    private void execute(HttpRequest request, String what) throws TelegramException {
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TelegramException("Telegram " + what + " was interrupted");
            } catch (IOException e) {
                throw new TelegramException("Telegram " + what + " failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + redact(e.getMessage())));
            }
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() == 200 && body.replaceAll("\\s", "").contains("\"ok\":true")) {
                return;
            }
            if (response.statusCode() == 429 && attempt == 1) {
                Matcher retry = RETRY_AFTER.matcher(body);
                int wait = retry.find() ? Integer.parseInt(retry.group(1)) : 1;
                if (wait <= MAX_RETRY_WAIT_SECONDS) {
                    try {
                        Thread.sleep(Math.max(1, wait) * 1000L);
                        continue;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new TelegramException("Telegram " + what + " was interrupted");
                    }
                }
            }
            throw new TelegramException("Telegram " + what + " was rejected (HTTP " + response.statusCode() + "): "
                + describe(body));
        }
    }

    private String describe(String body) {
        Matcher m = DESCRIPTION.matcher(body);
        String text = m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "no description";
        return redact(text.length() > 200 ? text.substring(0, 200) + "..." : text);
    }

    private String redact(String text) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (!settings.token().isBlank()) {
            out = out.replace(settings.token(), "****");
        }
        return out.replaceAll("bot\\d+:[A-Za-z0-9_-]+", "bot****");
    }

    private static void field(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            .append(value).append("\r\n");
    }

    /** A file name goes into a header, so strip quotes and line breaks. */
    static String safeFileName(String name) {
        String n = name == null || name.isBlank() ? "report" : name;
        return n.replaceAll("[\\r\\n\"\\\\]", "_");
    }

    /** JSON string literal with the escapes JSON requires. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
