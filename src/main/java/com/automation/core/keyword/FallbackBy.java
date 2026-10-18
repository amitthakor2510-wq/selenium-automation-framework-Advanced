package com.automation.core.keyword;

import com.automation.core.selfhealing.HealingEvent;
import com.automation.core.selfhealing.LocatorRepository;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link By} that tries an ordered list of locators and answers with the first one that
 * matches. It is what {@link ObjectRepository#get(String)} returns for a key that has
 * {@code <key>.fallback.N} entries, so every consumer of a plain {@code By} (HumanActions,
 * ExpectedConditions, SelfHealingEngine) gets multi-locator resilience without any change.
 *
 * <p>Resolution order per lookup: the first candidate that matches at least one
 * <em>displayed</em> element wins; if nothing displayed was found, the first candidate that
 * matched anything is returned so presence-style waits keep working; otherwise an empty list.
 * Using a fallback instead of the primary locator is logged once per instance and recorded
 * as a {@code fallback} stage in the self-healing report, so a drifting primary locator shows
 * up as something to fix instead of a silently passing test.
 */
public final class FallbackBy extends By {

    private static final Logger logger = LoggerFactory.getLogger(FallbackBy.class);

    private final String key;
    private final List<By> candidates;
    private final AtomicBoolean reported = new AtomicBoolean(false);

    public FallbackBy(String key, List<By> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("FallbackBy needs at least one locator for '" + key + "'");
        }
        this.key = key;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    /** The primary (first) locator. */
    public By primary() {
        return candidates.get(0);
    }

    /** All locators in priority order, primary first. */
    public List<By> candidates() {
        return candidates;
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        List<WebElement> firstNonEmpty = null;
        int firstNonEmptyIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            List<WebElement> found = candidates.get(i).findElements(context);
            if (found.isEmpty()) {
                continue;
            }
            if (anyDisplayed(found)) {
                report(i);
                return found;
            }
            if (firstNonEmpty == null) {
                firstNonEmpty = found;
                firstNonEmptyIndex = i;
            }
        }
        if (firstNonEmpty != null) {
            report(firstNonEmptyIndex);
            return firstNonEmpty;
        }
        return Collections.emptyList();
    }

    private static boolean anyDisplayed(List<WebElement> elements) {
        for (WebElement element : elements) {
            try {
                if (element.isDisplayed()) {
                    return true;
                }
            } catch (StaleElementReferenceException e) {
                // element vanished between the lookup and the check: treat as not displayed
                logger.trace("Stale element while probing fallback candidates", e);
            }
        }
        return false;
    }

    private void report(int index) {
        if (index <= 0 || !reported.compareAndSet(false, true)) {
            return;
        }
        logger.warn("[ObjectRepository] '{}': primary locator {} found nothing visible; using fallback #{} {}. "
            + "Update the primary locator in the object repository.", key, primary(), index, candidates.get(index));
        try {
            LocatorRepository.recordHeal(new HealingEvent("fallback :: " + key, primary().toString(),
                candidates.get(index).toString(), 1.0, "fallback"));
        } catch (RuntimeException e) {
            logger.debug("Could not record fallback usage for '{}': {}", key, e.getMessage());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FallbackBy that && candidates.equals(that.candidates);
    }

    @Override
    public int hashCode() {
        return candidates.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("By.fallback[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append(" || ");
            }
            sb.append(candidates.get(i));
        }
        return sb.append(']').toString();
    }
}
