package com.automation.core.keyword;

import java.util.Locale;
import com.automation.core.exceptions.KeywordExecutionException;
import org.openqa.selenium.By;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locator repository for keyword-driven tests — keeps "how do I find this
 * element" out of the test script and out of Java, in one properties file
 * per site under src/test/resources/objectrepository/.
 *
 * File format (one locator per line):
 *   saucedemo.username    = id:user-name
 *   saucedemo.password    = id:password
 *   saucedemo.loginButton = id:login-button
 *   saucedemo.errorMessage= css:[data-test='error']
 *
 * Supported prefixes: id, name, css, xpath, class, linktext, partiallinktext, tag
 * (case-insensitive). The part after the first ':' is passed through as-is,
 * so css/xpath values can safely contain colons themselves.
 *
 * Usage:
 *   ObjectRepository repo = ObjectRepository.load("objectrepository/saucedemo.properties");
 *   By locator = repo.get("saucedemo.username");
 */
public class ObjectRepository {

    private static final ConcurrentHashMap<String, ObjectRepository> CACHE = new ConcurrentHashMap<>();

    /** Suffix of the optional alternative locators for a key: {@code <key>.fallback.1}, {@code .2}, ... */
    public static final String FALLBACK_SUFFIX = ".fallback.";

    private final Properties locators = new Properties();
    private final String sourcePath;

    private ObjectRepository(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /** Loads (and caches) a locator repository from the classpath, e.g. "objectrepository/saucedemo.properties". */
    public static ObjectRepository load(String classpathResource) {
        return CACHE.computeIfAbsent(classpathResource, ObjectRepository::doLoad);
    }

    private static ObjectRepository doLoad(String classpathResource) {
        ObjectRepository repo = new ObjectRepository(classpathResource);
        try (InputStream is = ObjectRepository.class.getClassLoader()
            .getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new KeywordExecutionException("[ObjectRepository] Not found on classpath: " + classpathResource);
            }
            repo.locators.load(is);
        } catch (IOException e) {
            throw new KeywordExecutionException("[ObjectRepository] Failed to load: " + classpathResource, e);
        }
        return repo;
    }

    /**
     * Loads a repository straight from a file on disk, bypassing the classpath and the cache.
     * Used by the recorder to replay a recording immediately, before the resources have been
     * copied to target/test-classes.
     */
    public static ObjectRepository fromFile(Path file) {
        ObjectRepository repo = new ObjectRepository(file.toString());
        try (InputStream is = Files.newInputStream(file)) {
            repo.locators.load(is);
        } catch (IOException e) {
            throw new KeywordExecutionException("[ObjectRepository] Failed to load: " + file, e);
        }
        return repo;
    }

    /** True if the repository defines {@code key} (fallback entries are not keys of their own here). */
    public boolean contains(String key) {
        return locators.getProperty(key) != null;
    }

    /** The raw {@code type:value} text for a key, or {@code null} if it is not defined. */
    public String raw(String key) {
        String value = locators.getProperty(key);
        return value == null ? null : value.trim();
    }

    /**
     * Resolves a locator key (e.g. "saucedemo.username") to a Selenium By. If the key has
     * {@code <key>.fallback.1..N} entries the result is a {@link FallbackBy} that tries the
     * primary locator first and then each fallback in order; otherwise it is the plain By.
     */
    public By get(String key) {
        String raw = locators.getProperty(key);
        if (raw == null) {
            throw new KeywordExecutionException("[ObjectRepository] No locator for key '" + key
                + "' in " + sourcePath + ". Known keys: " + locators.keySet());
        }
        By primary = parse(key, raw.trim());
        List<By> chain = null;
        for (int n = 1; ; n++) {
            String fallbackKey = key + FALLBACK_SUFFIX + n;
            String fallbackRaw = locators.getProperty(fallbackKey);
            if (fallbackRaw == null) {
                break;
            }
            if (chain == null) {
                chain = new ArrayList<>();
                chain.add(primary);
            }
            chain.add(parse(fallbackKey, fallbackRaw.trim()));
        }
        return chain == null ? primary : new FallbackBy(key, chain);
    }

    /** Parses one {@code type:value} locator string; {@code key} is only used in error messages. */
    public static By parseLocator(String key, String raw) {
        return parse(key, raw.trim());
    }

    private static By parse(String key, String raw) {
        int sep = raw.indexOf(':');
        if (sep < 0) {
            throw new KeywordExecutionException("[ObjectRepository] Locator for '" + key
                + "' must be in 'type:value' form, got: '" + raw + "'");
        }
        String type = raw.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        String value = raw.substring(sep + 1).trim();

        switch (type) {
            case "id": return By.id(value);
            case "name": return By.name(value);
            case "css": return By.cssSelector(value);
            case "xpath": return By.xpath(value);
            case "class": return By.className(value);
            case "linktext": return By.linkText(value);
            case "partiallinktext": return By.partialLinkText(value);
            case "tag": return By.tagName(value);
            default:
                throw new KeywordExecutionException("[ObjectRepository] Unknown locator type '" + type
                    + "' for key '" + key + "'. Supported: id, name, css, xpath, class, "
                    + "linktext, partiallinktext, tag");
        }
    }
}
