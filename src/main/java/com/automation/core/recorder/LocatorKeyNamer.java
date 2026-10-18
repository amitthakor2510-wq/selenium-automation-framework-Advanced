package com.automation.core.recorder;

import com.automation.core.recorder.RecordedEvent.Hints;
import com.automation.core.recorder.RecordedEvent.Target;

import java.net.URI;
import java.util.Locale;

/** Turns what the recorder knows about an element or page into readable object-repository key parts. */
final class LocatorKeyNamer {

    private static final int MAX_NAME_LENGTH = 32;

    private LocatorKeyNamer() {
    }

    /** {@code https://x/y/checkout-step-one.html} -> {@code checkoutStepOne}; the site root -> {@code home}. */
    static String pageSlug(String url) {
        if (url == null || url.isBlank()) {
            return "page";
        }
        try {
            URI uri = URI.create(url);
            String fragment = uri.getFragment();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (fragment != null && fragment.startsWith("/") && fragment.length() > 1) {
                path = fragment;
            }
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
                if (!part.isBlank() && !part.matches("\\d+") && !part.matches("[0-9a-fA-F-]{16,}")) {
                    return camel(part, "page");
                }
            }
            return "home";
        } catch (IllegalArgumentException e) {
            return "page";
        }
    }

    /** A short readable name for an element, from the best naming hint available. */
    static String elementName(Target target) {
        Hints hints = target.hints == null ? new Hints() : target.hints;
        String[] options = {
            hints.testId, target.id, target.name, hints.ariaLabel, hints.label, hints.placeholder,
            hints.value, hints.title, shortText(target.text), target.tag
        };
        for (String option : options) {
            if (option != null && !option.isBlank() && !looksGenerated(option)) {
                return camel(option, "element");
            }
        }
        return camel(target.tag, "element");
    }

    /** Name for an iframe step, always ending in "Frame". */
    static String frameName(RecordedEvent.FrameStep frame, int ordinal) {
        String[] options = {frame.name, frame.id, frame.title};
        for (String option : options) {
            if (option != null && !option.isBlank() && !looksGenerated(option)) {
                String base = camel(option, "");
                if (!base.isEmpty()) {
                    return base.endsWith("Frame") ? base : base + "Frame";
                }
            }
        }
        return "frame" + ordinal;
    }

    /** Upper-snake form for an environment variable name: {@code saucedemo.login.password} -> SAUCEDEMO_LOGIN_PASSWORD. */
    static String envName(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9]+", "_")
            .replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
    }

    private static String shortText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > 40 ? null : trimmed;
    }

    private static boolean looksGenerated(String value) {
        return value.matches(".*\\d{4,}.*") || value.matches("^:r[0-9a-z]+:$");
    }

    /** {@code "Sign in now!"} -> {@code signInNow}; falls back to {@code fallback} if nothing usable is left. */
    static String camel(String raw, String fallback) {
        StringBuilder out = new StringBuilder();
        boolean upperNext = false;
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(out.length() == 0 ? Character.toLowerCase(c)
                    : upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            } else {
                upperNext = out.length() > 0;
            }
            if (out.length() >= MAX_NAME_LENGTH) {
                break;
            }
        }
        if (out.length() == 0 || Character.isDigit(out.charAt(0))) {
            String prefixed = fallback + (out.length() == 0 ? "" : capitalize(out.toString()));
            return prefixed.isEmpty() ? fallback : prefixed;
        }
        return out.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
