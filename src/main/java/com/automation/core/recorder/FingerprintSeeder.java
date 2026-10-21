package com.automation.core.recorder;

import com.automation.core.keyword.ObjectRepository;
import com.automation.core.recorder.RecordedEvent.Target;
import com.automation.core.selfhealing.ElementFingerprint;
import com.automation.core.selfhealing.SelfHealingEngine;
import org.openqa.selenium.By;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Gives every freshly recorded locator a self-healing baseline at record time, using what the page
 * reported about the element, so the very first replay can already heal a drifted locator rather
 * than only after a first successful run.
 */
public final class FingerprintSeeder {

    private static final int MAX_TEXT_LENGTH = 100;

    private FingerprintSeeder() {
    }

    /** Seeds one fingerprint per spec that has an observed element; returns how many were stored. */
    public static int seed(ObjectRepository repository, Collection<LocatorSpec> specs) {
        int seeded = 0;
        for (LocatorSpec spec : specs) {
            if (spec.observed == null) {
                continue;
            }
            By by = repository.get(spec.key);
            SelfHealingEngine.seedBaseline(spec.pageUrl, by, toFingerprint(spec.observed));
            seeded++;
        }
        return seeded;
    }

    static ElementFingerprint toFingerprint(Target target) {
        ElementFingerprint fp = new ElementFingerprint();
        fp.tag = target.tag;
        fp.id = emptyToNull(target.id);
        fp.name = emptyToNull(target.name);
        fp.classes = target.classes == null ? new ArrayList<>() : new ArrayList<>(target.classes);
        fp.text = truncate(target.text);
        List<String> tracked = SelfHealingEngine.trackedAttributes();
        if (target.attrs != null) {
            for (Map.Entry<String, String> attr : target.attrs.entrySet()) {
                if (tracked.contains(attr.getKey()) && attr.getValue() != null && !attr.getValue().isEmpty()) {
                    fp.attributes.put(attr.getKey(), attr.getValue());
                }
            }
        }
        fp.parentTag = emptyToNull(target.parentTag);
        return fp;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= MAX_TEXT_LENGTH ? trimmed : trimmed.substring(0, MAX_TEXT_LENGTH);
    }
}
