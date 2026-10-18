package com.automation.core.recorder;

import com.automation.core.recorder.RecordedEvent.Target;
import com.automation.core.selfhealing.ElementFingerprint;
import com.automation.core.selfhealing.SelfHealingEngine;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fingerprints built from what the recorder observed must look like the ones the healing engine captures. */
class FingerprintSeederTest {

    @Test
    void onlyTrackedNonEmptyAttributesAreKeptAndTextIsCapped() {
        Target t = new Target();
        t.tag = "input";
        t.id = "";
        t.name = "user";
        t.classes = List.of("form-control");
        t.text = "x".repeat(300);
        t.parentTag = "div";
        t.attrs.put("placeholder", "Username");
        t.attrs.put("type", "");
        t.attrs.put("onclick", "evil()");

        ElementFingerprint fp = FingerprintSeeder.toFingerprint(t);
        assertEquals("input", fp.tag);
        assertNull(fp.id, "an empty id is stored as absent, like a missing attribute");
        assertEquals("user", fp.name);
        assertEquals(List.of("form-control"), fp.classes);
        assertEquals(100, fp.text.length());
        assertEquals("div", fp.parentTag);
        assertEquals("Username", fp.attributes.get("placeholder"));
        assertFalse(fp.attributes.containsKey("type"));
        assertFalse(fp.attributes.containsKey("onclick"));
        assertTrue(SelfHealingEngine.trackedAttributes().contains("placeholder"));
    }

    @Test
    void theElementKeyIgnoresQueryAndPortAndSurvivesABadUrl() {
        By by = By.id("x");
        assertEquals("http://host/a/b.html :: By.id: x", SelfHealingEngine.elementKey("http://host:8080/a/b.html?q=1#frag", by));
        assertEquals("unknown-page :: By.id: x", SelfHealingEngine.elementKey((String) null, by));
    }
}
