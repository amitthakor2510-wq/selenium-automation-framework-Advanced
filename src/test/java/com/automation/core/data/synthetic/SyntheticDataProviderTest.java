package com.automation.core.data.synthetic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticDataProviderTest {

    @Test
    void blankEdgeValuesAreLeftAloneSoTheyStillTestWhatTheyClaim() {
        assertEquals("", SyntheticDataProvider.uniquify("", 0));
        assertEquals("   ", SyntheticDataProvider.uniquify("   ", 1));
    }

    @Test
    void suffixGoesInsideLeadingAndTrailingPadding() {
        String result = SyntheticDataProvider.uniquify("  padded  ", 4);
        assertEquals("  padded_4  ", result);
        assertTrue(result.startsWith("  "));
        assertTrue(result.endsWith("  "));
    }

    @Test
    void unpaddedValuesGetTheSuffixAppended() {
        assertEquals("abc_7", SyntheticDataProvider.uniquify("abc", 7));
    }
}
