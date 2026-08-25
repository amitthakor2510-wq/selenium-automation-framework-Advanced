package com.automation.core.data.synthetic;

import com.automation.core.config.ConfigReader;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Wraps {@link Faker} (DataFaker — {@code net.datafaker:datafaker}, see the pom.xml dependency
 * comment) to generate realistic-looking synthetic test data, plus a fixed set of deliberately
 * awkward boundary/edge-case values ({@link #edgeCaseStrings()}) a hand-written CSV row would
 * rarely think to include.
 *
 * <p>Two complementary generation strategies live here, matching the two halves of
 * "synthetic/generated test data" from {@code docs/roadmap.md}:
 * <ul>
 *   <li><b>Realistic (Faker)</b> — names/emails/usernames/passwords that look like real user
 *       input, for exercising the happy path across a wider variety of inputs than a handful of
 *       hand-typed rows ever would.</li>
 *   <li><b>Edge case (property-based-ish)</b> — empty, whitespace-only, single-character,
 *       very long, unicode/emoji, and injection-shaped strings, for exercising validation and
 *       boundary handling a realistic-looking value would never trigger.</li>
 * </ul>
 *
 * <p><b>Reproducibility:</b> by default every run gets fresh random data (real "at scale"
 * coverage over time, per the roadmap ask). Set {@code synthetic.data.seed} (a long) in
 * config/global.properties or via {@code -Dsynthetic.data.seed=12345} to pin the Faker instance
 * to a fixed seed instead — the exact same sequence of generated values on every run, useful
 * for reproducing a specific failure. See docs/configuration.md.
 */
public final class SyntheticDataGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticDataGenerator.class);

    private final Faker faker;

    public SyntheticDataGenerator() {
        String seedRaw = ConfigReader.get("synthetic.data.seed", "");
        if (seedRaw.isBlank()) {
            this.faker = new Faker(Locale.ENGLISH);
        } else {
            long seed;
            try {
                seed = Long.parseLong(seedRaw.trim());
            } catch (NumberFormatException e) {
                logger.warn("  synthetic.data.seed='" + seedRaw
                    + "' is not a valid long — ignoring, generating unseeded (random) data instead.");
                this.faker = new Faker(Locale.ENGLISH);
                return;
            }
            logger.info("  Synthetic data generator seeded with " + seed
                + " — every run will produce the identical sequence of values.");
            this.faker = new Faker(Locale.ENGLISH, new Random(seed));
        }
    }

    public String firstName() {
        return faker.name().firstName();
    }

    public String lastName() {
        return faker.name().lastName();
    }

    /**
     * A username guaranteed unique within a single JVM run (Faker's own username generator can
     * repeat within a small sample) — a short UUID suffix appended to a Faker-generated base,
     * matching the same "unique per run to avoid 'username taken'" pattern
     * {@code BookStoreApplicationTest}/{@code BookStoreApiTest} already use with plain UUIDs,
     * just with a more realistic-looking prefix.
     */
    public String uniqueUsername() {
        String base = faker.name().username().replaceAll("[^a-zA-Z0-9]", "");
        return base + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String email() {
        return faker.internet().emailAddress();
    }

    /**
     * A password that satisfies typical strength rules (upper, lower, digit, symbol, 10+ chars)
     * — deliberately not just {@code faker.internet().password()}, whose default charset can
     * produce weak/borderline results that would make a registration test flaky for reasons
     * unrelated to what it's actually testing.
     */
    public String strongPassword() {
        return faker.internet().password(10, 16, true, true, true);
    }

    public String phoneNumber() {
        return faker.phoneNumber().cellPhone();
    }

    public String streetAddress() {
        return faker.address().fullAddress();
    }

    /**
     * Deliberately awkward, boundary-shaped string values — not random, always this exact list —
     * for testing input validation the way property-based testing would, without pulling in a
     * full property-based-testing library: empty, whitespace-only, a single character, a very
     * long run (well past any reasonable maxlength), leading/trailing whitespace around an
     * otherwise-valid value, non-ASCII/unicode, an emoji, and SQL-injection-/XSS-shaped strings
     * (checking they're rejected or safely escaped, not that they execute — this framework never
     * asserts on the payload actually working).
     */
    public static List<String> edgeCaseStrings() {
        return List.of(
            "",
            "   ",
            "a",
            "A".repeat(300),
            "  leadingAndTrailingSpace  ",
            "Ünïcödé Nàme",
            "\uD83D\uDE00EmojiName",
            "' OR '1'='1",
            "<script>alert(1)</script>",
            "Robert'); DROP TABLE Students;--"
        );
    }
}
