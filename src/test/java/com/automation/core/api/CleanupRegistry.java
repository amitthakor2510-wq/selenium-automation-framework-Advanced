package com.automation.core.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A last-in-first-out list of "undo" actions a test registers as it creates data, run once the
 * test (or class) is over. It is how API-driven setup gets a matching API-driven teardown:
 *
 * <pre>
 *   ApiUser user = api.createUniqueUser();
 *   cleanup.add("delete account " + user.username(), () -&gt; api.deleteUser(user));
 * </pre>
 *
 * <p>Register the undo <i>right after</i> the create succeeds — not at the end of the test — so a
 * test that fails halfway still cleans up what it managed to create. Actions run newest first, so
 * a book added to an account is removed before the account itself.
 *
 * <p>A cleanup action that throws is logged and skipped, never rethrown: a leaked test account is
 * worth a loud warning, but it must not turn a passing test red or stop the remaining cleanups
 * from running.
 */
public final class CleanupRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CleanupRegistry.class);

    private final String scope;
    private final Deque<Entry> actions = new ArrayDeque<>();

    /** @param scope label used in log lines, e.g. "class" or "method" */
    public CleanupRegistry(String scope) {
        this.scope = scope;
    }

    public synchronized void add(String description, Runnable action) {
        actions.push(new Entry(description, action));
    }

    public synchronized int size() {
        return actions.size();
    }

    /**
     * Runs and clears every registered action, newest first.
     *
     * @return how many actions failed (0 when everything was cleaned up)
     */
    public synchronized int runAll() {
        int failed = 0;
        while (!actions.isEmpty()) {
            Entry entry = actions.pop();
            try {
                entry.action.run();
                logger.info("[cleanup:{}] {}", scope, entry.description);
            } catch (Exception | AssertionError e) {
                failed++;
                logger.warn("[cleanup:{}] FAILED — {} — data may be left behind: {}",
                    scope, entry.description, e.toString());
            }
        }
        return failed;
    }

    private static final class Entry {
        private final String description;
        private final Runnable action;

        private Entry(String description, Runnable action) {
            this.description = description;
            this.action = action;
        }
    }
}
