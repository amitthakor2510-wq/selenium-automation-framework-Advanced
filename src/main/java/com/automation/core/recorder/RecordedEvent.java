package com.automation.core.recorder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing the user did (or the browser did to them), as reported by the in-page script or
 * synthesised by {@link RecorderSession}. Types from the page: click, dblclick, rightclick, type,
 * select, check, upload, key, hover, assertText, assertValue, assertVisible. Types from the
 * session: url (the page address changed), window (text = "new" | "closed"), alert (text = message).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RecordedEvent {

    public String type;
    /** Browser clock (ms). Only compared with other events from the same browser. */
    public long ts;
    /** Address of the top-level page when the event happened. */
    public String url;
    /** Iframes between the top document and the element, outermost first; empty = top document. */
    public List<FrameStep> frame = new ArrayList<>();
    public Target target;
    public String value;
    public String text;
    public String key;
    public Boolean checked;
    public Boolean hadValue;
    public Boolean soft;
    public Integer index;
    /** Local clock (ms) when the session received the event; set by RecorderSession. */
    public long rcv;

    /** An iframe on the way to an element. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FrameStep {
        public List<Candidate> cands = new ArrayList<>();
        public String tag;
        public String name;
        public String id;
        public String title;
    }

    /** What the page told us about the element that was acted on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Target {
        public String tag;
        public String inputType;
        public String id;
        public String name;
        public List<String> classes = new ArrayList<>();
        public String text;
        public String parentTag;
        public Map<String, String> attrs = new LinkedHashMap<>();
        public Hints hints = new Hints();
        public boolean secret;
        public List<Candidate> cands = new ArrayList<>();
    }

    /** Human-readable names for an element, used to name its object-repository key. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Hints {
        public String label;
        public String placeholder;
        public String ariaLabel;
        public String testId;
        public String title;
        public String value;
    }

    public static RecordedEvent synthetic(String type, String url, String text, long rcv) {
        RecordedEvent event = new RecordedEvent();
        event.type = type;
        event.url = url;
        event.text = text;
        event.rcv = rcv;
        event.ts = rcv;
        return event;
    }
}
