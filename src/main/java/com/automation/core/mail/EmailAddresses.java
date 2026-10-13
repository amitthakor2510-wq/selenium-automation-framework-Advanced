package com.automation.core.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** Strict address validation shared by the settings check and the message builder. */
final class EmailAddresses {

    private EmailAddresses() {
    }

    static boolean isValid(String address) {
        try {
            InternetAddress[] parsed = InternetAddress.parse(address, true);
            return parsed.length == 1 && wellFormed(parsed) && noLineBreaks(address);
        } catch (AddressException e) {
            return false;
        }
    }

    /** Comma/semicolon separated list; every entry must be valid. */
    static boolean isValidList(String list) {
        try {
            InternetAddress[] parsed = InternetAddress.parse(normalise(list), true);
            return parsed.length > 0 && wellFormed(parsed) && noLineBreaks(list);
        } catch (AddressException e) {
            return false;
        }
    }

    /** Strict parsing still accepts a bare local part ("nonsense"); require user@domain. */
    private static boolean wellFormed(InternetAddress[] parsed) {
        for (InternetAddress a : parsed) {
            String addr = a.getAddress();
            int at = addr == null ? -1 : addr.lastIndexOf('@');
            if (at < 1 || at == addr.length() - 1 || addr.chars().anyMatch(Character::isWhitespace)) {
                return false;
            }
        }
        return true;
    }

    static InternetAddress[] parseList(String list) throws AddressException {
        return InternetAddress.parse(normalise(list), true);
    }

    private static String normalise(String list) {
        return list.replace(';', ',');
    }

    /** A CR/LF inside an address is a header-injection attempt, however the parser treats it. */
    private static boolean noLineBreaks(String s) {
        return s.indexOf('\r') < 0 && s.indexOf('\n') < 0;
    }
}
