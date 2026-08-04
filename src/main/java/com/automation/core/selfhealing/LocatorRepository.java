package com.automation.core.selfhealing;

import com.automation.core.config.ConfigReader;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds the "known good" {@link ElementFingerprint} for every element the
 * self-healing engine has successfully located, keyed by a per-page,
 * per-locator string (see {@link SelfHealingEngine#elementKey}).
 *
 * Loaded once from target/self-healing/locator-repository.json (fingerprints
 * captured by a *previous* run) so healing works from the very first
 * locator failure of a fresh run, not only after this run has already seen
 * the element succeed once. Updated in memory as the run progresses and
 * flushed back to disk via a JVM shutdown hook — the same deferred-cleanup
 * idiom DriverFactory already uses for temp profile directories, chosen for
 * the same reason: avoid contending disk I/O from every parallel test
 * thread on every successful find.
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

    /** Writes the current fingerprint map and healing report to disk. Safe to call more than once. */
    public static synchronized void flush() {
        Path path = repositoryPath();
        try {
            Files.createDirectories(path.getParent());
            mapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), FINGERPRINTS);
        } catch (Exception e) {
            logger.warning("[SelfHealing] Could not persist locator repository to " + path + ": " + e.getMessage());
        }
        SelfHealingReportWriter.write(new ArrayList<>(HEALING_EVENTS));
    }

    private static Path repositoryPath() {
        return Paths.get(ConfigReader.get("self-healing.repository.path",
            "target/self-healing/locator-repository.json"));
    }

    static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        return mapper;
    }
}
