package com.automation.core.recorder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Tiny local web server that serves the HTML pages under {@code src/test/resources/recorder-fixtures}.
 * It exists so recorder/keyword browser tests exercise a real HTTP origin (iframes are same-origin
 * only over http, not file://) without depending on any external site.
 */
public final class FixtureServer implements AutoCloseable {

    private static final String RESOURCE_DIR = "recorder-fixtures/";

    private final HttpServer server;

    private FixtureServer(HttpServer server) {
        this.server = server;
    }

    /** Starts the server on a free loopback port. */
    public static FixtureServer start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", FixtureServer::handle);
        http.start();
        return new FixtureServer(http);
    }

    /** Base URL without a trailing slash, e.g. {@code http://127.0.0.1:41233}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String name = "/".equals(path) ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("/")) {
            respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = FixtureServer.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
