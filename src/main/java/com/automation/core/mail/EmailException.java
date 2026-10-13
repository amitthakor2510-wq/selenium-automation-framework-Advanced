package com.automation.core.mail;

/** The report e-mail could not be built or sent. The message never contains credentials. */
public class EmailException extends Exception {

    private static final long serialVersionUID = 1L;

    public EmailException(String message) {
        super(message);
    }

    public EmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
