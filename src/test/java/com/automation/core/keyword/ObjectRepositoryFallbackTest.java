package com.automation.core.keyword;

import com.automation.core.exceptions.KeywordExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebDriver-free tests for multi-locator keys in {@link ObjectRepository}. */
class ObjectRepositoryFallbackTest {

    @TempDir
    Path dir;

    private ObjectRepository repo(String... lines) throws IOException {
        Path file = dir.resolve("repo.properties");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return ObjectRepository.fromFile(file);
    }

    @Test
    void aKeyWithoutFallbacksStaysAPlainBy() throws IOException {
        By by = repo("a.user = id:user").get("a.user");
        assertFalse(by instanceof FallbackBy);
        assertEquals(By.id("user"), by);
    }

    @Test
    void fallbackEntriesAreChainedInOrderBehindThePrimary() throws IOException {
        By by = repo("a.user = id:user", "a.user.fallback.1 = name:u", "a.user.fallback.2 = css:input.user").get("a.user");
        FallbackBy chain = assertInstanceOf(FallbackBy.class, by);
        assertEquals(List.of(By.id("user"), By.name("u"), By.cssSelector("input.user")), chain.candidates());
        assertEquals(By.id("user"), chain.primary());
        assertTrue(chain.toString().startsWith("By.fallback["));
    }

    @Test
    void theChainStopsAtTheFirstGapAndFallbackKeysAreNotKeysOfTheirOwn() throws IOException {
        ObjectRepository repository = repo("a.x = id:x", "a.x.fallback.1 = name:x", "a.x.fallback.3 = css:.x");
        FallbackBy chain = assertInstanceOf(FallbackBy.class, repository.get("a.x"));
        assertEquals(2, chain.candidates().size());
        assertTrue(repository.contains("a.x"));
        assertEquals("id:x", repository.raw("a.x"));
        assertNull(repository.raw("nope"));
    }

    @Test
    void anUnknownKeyAndABadFallbackTypeBothFailWithAClearMessage() throws IOException {
        ObjectRepository repository = repo("a.x = id:x", "a.x.fallback.1 = bogus:x");
        assertTrue(assertThrows(KeywordExecutionException.class, () -> repository.get("a.missing"))
            .getMessage().contains("No locator for key 'a.missing'"));
        assertThrows(KeywordExecutionException.class, () -> repository.get("a.x"));
    }

    @Test
    void parseLocatorIsPublicAndTrimsItsInput() {
        assertEquals(By.xpath("//a"), ObjectRepository.parseLocator("k", "  xpath://a  "));
    }
}
