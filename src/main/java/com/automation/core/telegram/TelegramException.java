package com.automation.core.telegram;

/** Sending the Telegram report failed, or the settings make sending impossible or unsafe. */
public class TelegramException extends Exception {

    private static final long serialVersionUID = 1L;

    public TelegramException(String message) {
        super(message);
    }

    public TelegramException(String message, Throwable cause) {
        super(message, cause);
    }
}
