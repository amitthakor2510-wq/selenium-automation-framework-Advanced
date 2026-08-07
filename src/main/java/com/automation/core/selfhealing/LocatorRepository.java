package com.automation.core.selfhealing;

import com.automation.core.config.ConfigReader;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds the "known good" {@link ElementFingerprint} for every element the
 * self-healing engine has successfully located, keyed by a per-page,
 * per-locator string (see {@link SelfHealingEngine#elementKey}).
 *
 * Loaded once from self-healing-data/locator-repository.json (fingerprints
 * captured by a *previous* run) so healing works from the very first
 * locator failure of a fresh run, not only after this run has already seen
 * the element succeed once. Updated in memory as the run progresses and
 * flushed back to disk via a JVM shutdown hook — the same deferred-cleanup
 * idiom DriverFactory already uses for temp profile directories, chosen for
 * the same reason: avoid contending disk I/O from every parallel test
 * thread on every successful find.
 *
 * BUG FIX: this used to default to target/self-healing/locator-repository.json.
 * target/ is Maven's build output directory — every CI pipeline here runs
 * `mvn clean` before tests (see Jenkinsfile/.github/.gitlab-ci.yml Build
 * stage), which deletes the whole directory, repository file included,
 * before the very first test of every single run. The "loaded once ... so
 * healing works from the very first locator failure of a fresh run" promise
 * above was never actually true in CI as a result — there was never a prior
 * run's baseline left to load, only whatever this run had already
 * fingerprinted earlier in the same run. Moved out of target/ so a plain
 * `mvn clean test` (local or CI) no longer wipes it. Jenkins additionally
 * wipes the *entire* workspace after every build via cleanWs() — see the
 * "Self-Healing Repository" cache/restore steps added around that in
 * Jenkinsfile, which is what makes this survive there too.
 */
public final class LocatorRepository {

    private static final Logger logger = Logger.getLogger(LocatorRepository.class.getName());

    private static final Map<String, ElementFingerprint> FINGERPRINTS = new ConcurrentHashMap<>();
    private static final List<HealingEvent> HEALING_EVENTS = Collections.synchronizedList(new ArrayList<>());

    private static volatile boolean loaded = false;
    private static volatile boolean shutdownHookRegistered = false;
    private static final Object initLock = new Object();

    private LocatorRepository() {
    }

    public static ElementFingerprint get(String key) {
        ensureLoaded();
        return FINGERPRINTS.get(key);
    }

    public static void put(String key, ElementFingerprint fingerprint) {
        ensureLoaded();
        FINGERPRINTS.put(key, fingerprint);
    }

    public static void recordHeal(HealingEvent event) {
        ensureLoaded();
        HEALING_EVENTS.add(event);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (initLock) {
            if (loaded) {
                return;
            }
            loadFromDisk();
            registerShutdownFlush();
            loaded = true;
        }
    }

    private static void loadFromDisk() {
        Path path = repositoryPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, ElementFingerprint> onDisk = mapper().readValue(path.toFile(),
                new TypeReference<Map<String, ElementFingerprint>>() {
                });
            FINGERPRINTS.putAll(onDisk);
            logger.info("[SelfHealing] Loaded " + onDisk.size()
                + " known-good element fingerprint(s) from " + path);
        } catch (Exception e) {
            // A corrupt/stale repository file should never block a test run —
            // self-healing just starts cold (no baseline to heal against yet)
            // until fresh fingerprints are captured this run.
            logger.warning("[SelfHealing] Could not load locator repository at " + path
                + " (starting fresh): " + e.getMessage());
        }
    }

    private static synchronized void registerShutdownFlush() {
        if (shutdownHookRegistered) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(LocatorRepository::flush, "self-healing-repository-flush"));
        shutdownHookRegistered = true;
    }

    /**
     * Writes the current fingerprint map and healing report to disk. Safe to call more than once.
     * <p>
     * BUG FIX (2026-08-07 build, diagnosed from console log): repositoryPath() is one shared
     * path with no per-site scoping, but Jenkins' "Run Tests Per Site" stage runs demoqa and
     * saucedemo as two SEPARATE `mvn test` OS processes sharing the same workspace — each with
     * its own JVM, its own in-memory FINGERPRINTS map, and its own shutdown-hook flush(). The
     * {@code synchronized} keyword here only ever serialized calls *within* one JVM; it did
     * nothing to stop the other site's JVM from writing the same file at the same time. The old
     * body called writeValue() directly, which blindly overwrites the file with only this JVM's
     * own map — so whichever site's process happened to exit last silently discarded every
     * fingerprint the other site had learned and already flushed, with no error or warning
     * anywhere (self-healing degrading is not fail-fast by design). Fixed by taking a real
     * exclusive OS-level FileLock on the destination for the read-merge-write, and merging
     * with whatever is currently on disk instead of overwriting it — so two concurrent
     * flushes from different site processes now compose instead of racing.
     */
    public static synchronized void flush() {
        Path path = repositoryPath();
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {

                Map<String, ElementFingerprint> merged = new HashMap<>();
                long existingSize = channel.size();
                if (existingSize > 0) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) existingSize);
                    channel.read(buffer, 0);
                    try {
                        merged.putAll(mapper().readValue(buffer.array(),
                            new TypeReference<Map<String, ElementFingerprint>>() {
                            }));
                    } catch (Exception parseEx) {
                        // Another process's flush() could theoretically be caught mid-write
                        // (extremely unlikely under the FileLock above, but not impossible on
                        // filesystems where locking is advisory-only) — proceed with just this
                        // JVM's own fingerprints rather than losing the whole flush over it.
                        logger.warning("[SelfHealing] Existing locator repository at " + path
                            + " was unreadable during merge (starting from this run's data only): "
                            + parseEx.getMessage());
                    }
                }
                // This run's own findings take precedence on key conflicts — they're the
                // freshest confirmation of that element's current locator.
                merged.putAll(FINGERPRINTS);

                byte[] out = mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(merged);
                channel.truncate(0);
                channel.write(ByteBuffer.wrap(out), 0);
            }
        } catch (Exception e) {
            logger.warning("[SelfHealing] Could not persist locator repository to " + path + ": " + e.getMessage());
        }
        SelfHealingReportWriter.write(new ArrayList<>(HEALING_EVENTS));
    }

    private static Path repositoryPath() {
        return Paths.get(ConfigReader.get("self-healing.repository.path",
            "self-healing-data/locator-repository.json"));
    }

    static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        return mapper;
    }
}
