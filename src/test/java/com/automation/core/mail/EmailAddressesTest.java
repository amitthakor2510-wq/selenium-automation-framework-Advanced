package com.automation.core.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Address validation shared by the e-mail settings check and the message builder. */
class EmailAddressesTest {

    @Test
    void plainAddressIsValid() {
        assertTrue(EmailAddresses.isValid("user@example.com"));
    }

    @Test
    void bareLocalPartWithoutADomainIsInvalid() {
        assertFalse(EmailAddresses.isValid("nonsense"));
    }

    @Test
    void addressWithoutALocalPartIsInvalid() {
        assertFalse(EmailAddresses.isValid("@example.com"));
    }

    @Test
    void addressWithoutADomainIsInvalid() {
        assertFalse(EmailAddresses.isValid("user@"));
    }

    @Test
    void emptyStringIsInvalid() {
        assertFalse(EmailAddresses.isValid(""));
    }

    @Test
    void twoAddressesAreNotOneValidAddress() {
        assertFalse(EmailAddresses.isValid("a@example.com, b@example.com"));
    }

    @Test
    void lineBreakInAnAddressIsRejectedAsHeaderInjection() {
        assertFalse(EmailAddresses.isValid("a@example.com\nBcc: evil@example.com"));
        assertFalse(EmailAddresses.isValid("a@example.com\r\nBcc: evil@example.com"));
    }

    @Test
    void listMayBeSeparatedByCommasOrSemicolons() {
        assertTrue(EmailAddresses.isValidList("a@example.com, b@example.com"));
        assertTrue(EmailAddresses.isValidList("a@example.com;b@example.com"));
        assertTrue(EmailAddresses.isValidList("a@example.com"));
    }

    @Test
    void oneBadEntryMakesTheWholeListInvalid() {
        assertFalse(EmailAddresses.isValidList("a@example.com;nonsense"));
        assertFalse(EmailAddresses.isValidList("a@example.com, @example.com"));
    }

    @Test
    void emptyListIsInvalid() {
        assertFalse(EmailAddresses.isValidList(""));
    }

    @Test
    void lineBreakInAListIsRejectedAsHeaderInjection() {
        assertFalse(EmailAddresses.isValidList("a@example.com\r\nBcc: evil@example.com"));
    }

    @Test
    void parseListSplitsOnBothSeparators() throws AddressException {
        InternetAddress[] parsed = EmailAddresses.parseList("a@example.com;b@example.com, c@example.com");

        assertEquals(3, parsed.length);
        assertEquals("a@example.com", parsed[0].getAddress());
        assertEquals("b@example.com", parsed[1].getAddress());
        assertEquals("c@example.com", parsed[2].getAddress());
    }
}
