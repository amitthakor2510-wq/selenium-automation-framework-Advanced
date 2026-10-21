package com.automation.core.recorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything needed to write one object-repository key: its locators and what the element looked like. */
public final class LocatorSpec {

    public final String key;
    /** Primary locator first, then up to two fallbacks. */
    public final List<Candidate> locators;
    /** The element as observed while recording; null for an iframe that was only passed through. */
    public final RecordedEvent.Target observed;
    /** Top-level page URL the element was recorded on. */
    public final String pageUrl;
    /** True when the key already existed in the repository with the same primary locator. */
    public final boolean alreadyInRepository;

    public LocatorSpec(String key, List<Candidate> locators, RecordedEvent.Target observed, String pageUrl,
                       boolean alreadyInRepository) {
        this.key = key;
        this.locators = new ArrayList<>(locators);
        this.observed = observed;
        this.pageUrl = pageUrl;
        this.alreadyInRepository = alreadyInRepository;
    }

    public Candidate primary() {
        return locators.get(0);
    }

    /** The repository lines for this key: {@code key} and {@code key.fallback.N}, in order. */
    public Map<String, String> repositoryEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < locators.size(); i++) {
            String entryKey = i == 0 ? key : key + ".fallback." + i;
            entries.put(entryKey, locators.get(i).toLocatorString());
        }
        return entries;
    }
}
