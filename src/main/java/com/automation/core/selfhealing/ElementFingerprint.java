package com.automation.core.selfhealing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A lightweight snapshot of the DOM attributes that made a located element
 * identifiable, captured at the moment a locator successfully resolved.
 * This is the "memory" self-healing works from: when the original locator
 * later fails (the site's markup shifted), the engine re-scans elements of
 * the same tag and scores each against this fingerprint to find the closest
 * surviving match.
 *
 * Deliberately excludes anything likely to change between page loads for
 * reasons unrelated to markup drift (element position/size, current value
 * of an input, etc.) — only relatively stable identity signals are kept.
 *
 * Public fields (no getters/setters) so Jackson can (de)serialize this
 * with field-visibility enabled in {@link LocatorRepository}, matching the
 * plain-data-holder style already used elsewhere in this framework
 * (e.g. DataRow).
 */
public class ElementFingerprint {

    public String tag;
    public String id;
    public String name;
    public List<String> classes;
    public String text;
    /** type, placeholder, aria-label, role, href, title, data-testid — whichever were present. */
    public Map<String, String> attributes = new LinkedHashMap<>();
    public String parentTag;
    /**
     * Perceptual difference-hash of the element's own screenshot (see
     * {@link VisualHasher}), captured only when self-healing.visual.enabled
     * is true. Null otherwise — visual healing degrades gracefully to
     * DOM-only scoring wherever this is absent, including for fingerprints
     * captured before visual healing was turned on.
     */
    public String visualHash;

    /** No-arg constructor required by Jackson. */
    public ElementFingerprint() {
    }
}
