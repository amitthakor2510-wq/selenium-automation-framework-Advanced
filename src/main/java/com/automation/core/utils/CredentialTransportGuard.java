package com.automation.core.utils;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Refuses to send a credential (API key, bearer token) over plain {@code http://} to anything
 * other than the local machine.
 *
 * <p>The AI / CAPTCHA-vision endpoints are configurable ({@code ai.endpoint},
 * {@code captcha.ai.endpoint}, {@code ai.vision.endpoint}). A typo or a copied-over
 * {@code http://} URL would otherwise put the {@code x-api-key} / {@code Authorization} header
 * on the wire in clear text. Loopback hosts are exempt because that traffic never leaves the
 * machine (the usual local Ollama setup). Host checks are literal - no DNS lookup is made.
 */
public final class CredentialTransportGuard {

    private CredentialTransportGuard() {
    }

    /** Longest slice of a remote response body that is copied into an exception message. */
    public static final int MAX_ERROR_BODY_CHARS = 500;

    /**
     * @param uri              the request URI
     * @param carriesCredential true if the request has an API key / Authorization header
     * @param allowInsecure    explicit opt-out (e.g. {@code ai.allowInsecureTransport=true})
     * @param label            short name used in the error message
     * @throws IOException if a credential would be sent in clear text to a non-local host
     */
    public static void requireSecure(URI uri, boolean carriesCredential, boolean allowInsecure, String label)
            throws IOException {
        if (!carriesCredential || allowInsecure || uri == null) {
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme) || isLocal(uri.getHost())) {
            return;
        }
        throw new IOException(label + ": refusing to send an API key / Authorization header over "
            + scheme + "://" + uri.getHost() + " (not HTTPS and not localhost). Use an https:// endpoint, "
            + "or set ai.allowInsecureTransport=true if you really trust this network.");
    }

    /** True for localhost, 127.0.0.0/8, ::1 and host.docker.internal. */
    static boolean isLocal(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.equals("localhost") || h.equals("::1") || h.equals("host.docker.internal")
            || h.matches("127(\\.\\d{1,3}){3}");
    }

    /** Caps a remote response body before it goes into an exception message or log line. */
    public static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_ERROR_BODY_CHARS
            ? body
            : body.substring(0, MAX_ERROR_BODY_CHARS) + "... [truncated " + (body.length() - MAX_ERROR_BODY_CHARS) + " chars]";
    }
}
