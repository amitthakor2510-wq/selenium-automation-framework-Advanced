package com.automation.core.recorder;

import com.automation.core.recorder.RecordedEvent.FrameStep;
import com.automation.core.recorder.RecordedEvent.Target;
import com.automation.core.utils.SensitiveData;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw list of {@link RecordedEvent}s into keyword steps plus the object-repository entries
 * those steps need. Pure logic with no browser access, so it is fully unit-testable.
 *
 * <p>What it decides: which locator becomes the primary and which become fallbacks; readable,
 * collision-free repository keys (reusing an existing key when the same locator is already there);
 * iframe and window switching steps; merging two clicks and a double click into one DOUBLE_CLICK;
 * dropping the focus click that precedes typing into a field; and replacing secrets with
 * {@code ${env:NAME}} placeholders so they never reach the script.
 */
public final class StepBuilder {

    /** A page change within this long of the user's last action is treated as caused by it. */
    private static final long CAUSED_NAVIGATION_MS = 4000;
    private static final long DOUBLE_CLICK_MS = 1500;
    private static final int MAX_FALLBACKS = 2;
    private static final String UPLOAD_DIR = "src/test/resources/testdata/uploads/";

    private final String prefix;
    private final String baseUrl;
    private final boolean autoAssertUrl;
    private final Map<String, String> existingRepository;

    private final RecordedFlow flow = new RecordedFlow();
    private final Map<String, String> identityToKey = new HashMap<>();
    private final Set<String> usedKeys = new HashSet<>();
    private List<String> currentFrames = new ArrayList<>();
    private long lastInteractionRcv;

    /**
     * @param prefix             first part of every repository key, normally the site name
     * @param baseUrl            site base URL (no trailing slash) so NAVIGATE steps can be relative; may be empty
     * @param autoAssertUrl      add VERIFY_URL_CONTAINS after a page change caused by a user action
     * @param existingRepository the repository as it is now (key to raw {@code type:value}); may be empty
     */
    public StepBuilder(String prefix, String baseUrl, boolean autoAssertUrl, Map<String, String> existingRepository) {
        this.prefix = prefix;
        this.baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl);
        this.autoAssertUrl = autoAssertUrl;
        this.existingRepository = existingRepository == null ? Map.of() : existingRepository;
    }

    public RecordedFlow build(List<RecordedEvent> events) {
        for (int i = 0; i < events.size(); i++) {
            handle(events, i);
        }
        return flow;
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(List<RecordedEvent> events, int index) {
        RecordedEvent e = events.get(index);
        switch (e.type) {
            case "url" -> onUrl(e);
            case "window" -> onWindow(e);
            case "alert" -> add("ACCEPT_ALERT", "", "", "", "Alert: " + clip(e.text, 60), e);
            case "click" -> {
                if (!isFocusClickBeforeTyping(events, index)) {
                    simpleElementStep("CLICK", e);
                }
            }
            case "dblclick" -> onDoubleClick(e);
            case "rightclick" -> simpleElementStep("RIGHT_CLICK", e);
            case "hover" -> simpleElementStep("HOVER", e);
            case "type" -> onType(e);
            case "select" -> onSelect(e);
            case "check" -> onCheck(e);
            case "upload" -> onUpload(e);
            case "key" -> onKey(e);
            case "assertText" -> onAssertText(e);
            case "assertValue" -> onAssertValue(e);
            case "assertVisible" -> simpleElementStep("VERIFY_DISPLAYED", e);
            default -> flow.notes.add("Ignored an unknown recorded event of type '" + e.type + "'.");
        }
    }

    // ------------------------------------------------------------- element events

    private void simpleElementStep(String keyword, RecordedEvent e) {
        String key = resolve(e);
        if (key != null) {
            add(keyword, key, "", "", describe(keyword, e), e);
        }
    }

    private void onDoubleClick(RecordedEvent e) {
        String key = resolve(e);
        if (key == null) {
            return;
        }
        for (int removed = 0; removed < 2 && !flow.steps.isEmpty(); removed++) {
            RecordedStep last = flow.steps.get(flow.steps.size() - 1);
            if (last.keyword.equals("CLICK") && last.locatorKey.equals(key) && e.ts - last.ts <= DOUBLE_CLICK_MS) {
                flow.steps.remove(flow.steps.size() - 1);
            } else {
                break;
            }
        }
        add("DOUBLE_CLICK", key, "", "", describe("DOUBLE_CLICK", e), e);
    }

    private void onType(RecordedEvent e) {
        String key = resolve(e);
        if (key == null) {
            return;
        }
        String value = e.value == null ? "" : e.value;
        if (value.isEmpty()) {
            add("CLEAR", key, "", "", "Clear " + label(e), e);
            return;
        }
        if (Boolean.TRUE.equals(e.hadValue)) {
            add("CLEAR", key, "", "", "Clear " + label(e) + " before typing", e);
        }
        boolean secret = e.target.secret || SensitiveData.isSensitiveName(key);
        String data = value;
        String description = "Type into " + label(e);
        if (secret) {
            String env = LocatorKeyNamer.envName(key);
            flow.secrets.put(env, value);
            data = "${env:" + env + "}";
            description = "Type secret into " + label(e);
        }
        add("TYPE", key, data, "", description, e);
    }

    private void onSelect(RecordedEvent e) {
        String key = resolve(e);
        if (key != null) {
            add("SELECT_BY_TEXT", key, e.text == null ? "" : e.text, "", "Select '" + clip(e.text, 40) + "' in " + label(e), e);
        }
    }

    private void onCheck(RecordedEvent e) {
        String key = resolve(e);
        if (key != null) {
            String keyword = Boolean.TRUE.equals(e.checked) ? "CHECK" : "UNCHECK";
            add(keyword, key, "", "", describe(keyword, e), e);
        }
    }

    private void onUpload(RecordedEvent e) {
        String key = resolve(e);
        if (key == null) {
            return;
        }
        String names = e.text == null ? "" : e.text;
        String first = names.contains(",") ? names.substring(0, names.indexOf(',')) : names;
        if (first.isEmpty()) {
            return;
        }
        String path = UPLOAD_DIR + first;
        add("UPLOAD_FILE", key, path, "", "Upload " + first, e);
        flow.notes.add("UPLOAD_FILE: put the file at " + path + " (the browser does not reveal where the original was).");
        if (names.contains(",")) {
            flow.notes.add("Several files were chosen at once ('" + names + "'); only the first was recorded.");
        }
    }

    private void onKey(RecordedEvent e) {
        String key = resolve(e);
        add("PRESS_KEY", key == null ? "" : key, e.key, "", "Press " + e.key, e);
    }

    private void onAssertText(RecordedEvent e) {
        String key = resolve(e);
        if (key == null) {
            return;
        }
        String text = e.text == null ? "" : e.text.trim();
        if (text.isEmpty()) {
            add("VERIFY_DISPLAYED", key, "", "", "Verify " + label(e) + " is displayed (it has no text)", e);
        } else {
            add("VERIFY_TEXT", key, "", text, "Verify text of " + label(e), e);
        }
    }

    private void onAssertValue(RecordedEvent e) {
        String key = resolve(e);
        if (key != null) {
            add("VERIFY_ATTRIBUTE", key, "value", e.value == null ? "" : e.value, "Verify value of " + label(e), e);
        }
    }

    // ----------------------------------------------------------- page-level events

    private void onUrl(RecordedEvent e) {
        leaveFrames(e);
        boolean caused = lastInteractionRcv > 0 && !flow.steps.isEmpty() && e.rcv - lastInteractionRcv < CAUSED_NAVIGATION_MS;
        if (!caused) {
            add("NAVIGATE", "", relativeUrl(e.url), "", "Open " + clip(e.url, 80), e);
            return;
        }
        add("WAIT_FOR_PAGE_LOAD", "", "", "", "Wait for the page to load", e);
        String expected = autoAssertUrl ? assertablePath(e.url) : null;
        if (expected != null) {
            add("VERIFY_URL_CONTAINS", "", "", expected, "Landed on " + expected, e);
        }
    }

    private void onWindow(RecordedEvent e) {
        currentFrames = new ArrayList<>();
        if ("new".equals(e.text)) {
            add("SWITCH_TO_WINDOW", "", "NEW", "", "Switch to the window that just opened", e);
        } else {
            add("CLOSE_WINDOW", "", "", "", "Close this window and go back to the previous one", e);
        }
    }

    private void leaveFrames(RecordedEvent e) {
        if (!currentFrames.isEmpty()) {
            add("SWITCH_TO_DEFAULT_CONTENT", "", "", "", "Leave the iframe", e);
            currentFrames = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------ key resolution

    /** Enters any iframes the element is inside, then returns its repository key (null if it had no usable locator). */
    private String resolve(RecordedEvent e) {
        if (e.target == null) {
            flow.notes.add("A '" + e.type + "' event had no target element and was skipped.");
            return null;
        }
        lastInteractionRcv = e.rcv;
        if (!enterFrames(e)) {
            return null;
        }
        boolean secret = e.target.secret;
        String key = keyFor("E" + frameSignature(e), e.target.cands, e.target, e.url,
            LocatorKeyNamer.elementName(e.target), secret);
        if (key == null) {
            flow.notes.add("No unique locator could be built for a <" + e.target.tag + "> (" + clip(e.target.text, 30)
                + "); the '" + e.type + "' step was skipped. Add it by hand.");
        }
        return key;
    }

    private boolean enterFrames(RecordedEvent e) {
        List<String> wanted = new ArrayList<>();
        List<FrameStep> chain = e.frame == null ? List.of() : e.frame;
        StringBuilder outer = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            FrameStep frame = chain.get(i);
            String key = keyFor("F" + outer, frame.cands, frameTarget(frame), e.url, LocatorKeyNamer.frameName(frame, i + 1), false);
            if (key == null) {
                flow.notes.add("An iframe could not be located reliably; actions inside it were skipped.");
                return false;
            }
            wanted.add(key);
            outer.append('>').append(key);
        }
        if (!wanted.equals(currentFrames)) {
            if (!currentFrames.isEmpty()) {
                add("SWITCH_TO_DEFAULT_CONTENT", "", "", "", "Leave the iframe", e);
            }
            for (String frameKey : wanted) {
                add("SWITCH_TO_FRAME", frameKey, "", "", "Enter iframe", e);
            }
            currentFrames = wanted;
        }
        return true;
    }

    private String keyFor(String scope, List<Candidate> cands, Target observed, String url, String name, boolean secret) {
        List<Candidate> ordered = order(cands);
        if (ordered.isEmpty()) {
            return null;
        }
        Candidate primary = ordered.get(0);
        String identity = scope + "|" + primary;
        String known = identityToKey.get(identity);
        if (known != null) {
            return known;
        }
        String reused = findExistingKey(primary);
        if (reused != null) {
            identityToKey.put(identity, reused);
            flow.locators.computeIfAbsent(reused, k -> new LocatorSpec(k, List.of(primary), observed, url, true));
            return reused;
        }
        String elementName = name;
        if (secret && !SensitiveData.isSensitiveName(elementName)) {
            elementName = elementName + "Password";
        }
        String base = prefix + "." + LocatorKeyNamer.pageSlug(url) + "." + elementName;
        String key = base;
        for (int n = 2; usedKeys.contains(key) || existingRepository.containsKey(key); n++) {
            key = base + n;
        }
        usedKeys.add(key);
        List<Candidate> chosen = new ArrayList<>();
        chosen.add(primary);
        // position-based locators are never fallbacks: they can quietly match the wrong element
        for (int i = 1; i < ordered.size() && chosen.size() <= MAX_FALLBACKS; i++) {
            if (ordered.get(i).stable) {
                chosen.add(ordered.get(i));
            }
        }
        flow.locators.put(key, new LocatorSpec(key, chosen, observed, url, false));
        identityToKey.put(identity, key);
        if (!primary.stable) {
            flow.notes.add(key + " only has a position-based locator (" + primary
                + "); it will break when the layout changes. Consider adding an id or data-testid to the page.");
        }
        return key;
    }

    private String findExistingKey(Candidate primary) {
        String wanted = primary.toLocatorString();
        String best = null;
        for (Map.Entry<String, String> entry : existingRepository.entrySet()) {
            if (entry.getKey().contains(".fallback.") || !wanted.equals(entry.getValue().trim())) {
                continue;
            }
            if (entry.getKey().startsWith(prefix + ".")) {
                return entry.getKey();
            }
            if (best == null) {
                best = entry.getKey();
            }
        }
        return best;
    }

    private static List<Candidate> order(List<Candidate> cands) {
        List<Candidate> ordered = new ArrayList<>();
        if (cands == null) {
            return ordered;
        }
        for (Candidate c : cands) {
            if (c.stable) {
                ordered.add(c);
            }
        }
        for (Candidate c : cands) {
            if (!c.stable) {
                ordered.add(c);
            }
        }
        return ordered;
    }

    private static Target frameTarget(FrameStep frame) {
        Target target = new Target();
        target.tag = frame.tag;
        target.id = frame.id;
        target.name = frame.name;
        target.text = "";
        target.parentTag = "";
        return target;
    }

    /** Identifies the iframe chain an event happened in, so equal locators in different frames stay distinct. */
    private static String frameSignature(RecordedEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.frame != null) {
            for (FrameStep f : e.frame) {
                List<Candidate> ordered = order(f.cands);
                sb.append('>').append(ordered.isEmpty() ? "?" : ordered.get(0).toLocatorString());
            }
        }
        return sb.toString();
    }

    private boolean isFocusClickBeforeTyping(List<RecordedEvent> events, int index) {
        RecordedEvent click = events.get(index);
        if (!Boolean.TRUE.equals(click.soft) || index + 1 >= events.size()) {
            return false;
        }
        RecordedEvent next = events.get(index + 1);
        return "type".equals(next.type) && signature(click).equals(signature(next));
    }

    private static String signature(RecordedEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.frame != null) {
            for (FrameStep f : e.frame) {
                List<Candidate> ordered = order(f.cands);
                sb.append(ordered.isEmpty() ? "?" : ordered.get(0)).append('>');
            }
        }
        List<Candidate> ordered = e.target == null ? List.of() : order(e.target.cands);
        return sb.append(ordered.isEmpty() ? "?" : ordered.get(0)).toString();
    }

    // -------------------------------------------------------------------- output

    private void add(String keyword, String locatorKey, String testData, String expected, String description, RecordedEvent e) {
        flow.steps.add(new RecordedStep(keyword, locatorKey, testData, expected, description, e.ts));
    }

    private String describe(String keyword, RecordedEvent e) {
        String verb = switch (keyword) {
            case "CLICK" -> "Click";
            case "DOUBLE_CLICK" -> "Double-click";
            case "RIGHT_CLICK" -> "Right-click";
            case "HOVER" -> "Hover over";
            case "CHECK" -> "Check";
            case "UNCHECK" -> "Uncheck";
            case "VERIFY_DISPLAYED" -> "Verify displayed:";
            default -> keyword;
        };
        return verb + " " + label(e);
    }

    private static String label(RecordedEvent e) {
        Target t = e.target;
        if (t == null) {
            return "";
        }
        boolean hasHints = t.hints != null;
        String shortText = t.text != null && t.text.length() <= 30 && !"select".equals(t.tag) ? t.text : null;
        String[] options = {hasHints ? t.hints.label : null, hasHints ? t.hints.ariaLabel : null,
            hasHints ? t.hints.placeholder : null, hasHints ? t.hints.value : null, shortText, t.id, t.name};
        for (String option : options) {
            if (option != null && !option.isBlank()) {
                return "'" + clip(option.trim(), 40) + "'";
            }
        }
        return "<" + t.tag + ">";
    }

    private String relativeUrl(String url) {
        if (url == null) {
            return "";
        }
        if (!baseUrl.isEmpty() && url.startsWith(baseUrl)) {
            String rest = url.substring(baseUrl.length());
            return "/".equals(rest) ? "" : rest;
        }
        return url;
    }

    private static String assertablePath(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String fragment = uri.getFragment();
            if ((path.isEmpty() || "/".equals(path)) && fragment != null && !fragment.isEmpty()) {
                return "#" + fragment;
            }
            return path.length() > 1 ? path : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
