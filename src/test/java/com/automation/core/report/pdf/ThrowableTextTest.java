package com.automation.core.report.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrowableTextTest {

    private static Throwable withFrames(String message, StackTraceElement... frames) {
        RuntimeException e = new RuntimeException(message);
        e.setStackTrace(frames);
        return e;
    }

    private static StackTraceElement frame(String cls, String method, int line) {
        return new StackTraceElement(cls, method, cls.substring(cls.lastIndexOf('.') + 1) + ".java", line);
    }

    @Test
    void messageHasClassNameAndMasksSecrets() {
        String m = ThrowableText.message(new IllegalStateException("login failed password=hunter2 for bob"));
        assertTrue(m.startsWith("java.lang.IllegalStateException: login failed"), m);
        assertFalse(m.contains("hunter2"), m);
    }

    @Test
    void nullThrowableGivesEmptyText() {
        assertEquals("", ThrowableText.message(null));
        assertEquals("", ThrowableText.stackTrace(null, 10));
    }

    @Test
    void frameworkNoiseIsDropped() {
        Throwable t = withFrames("x",
            frame("com.automation.sites.pages.LoginPage", "submit", 42),
            frame("org.testng.internal.invokers.MethodInvocationHelper", "invoke", 1),
            frame("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke", 2),
            frame("com.automation.sites.tests.LoginTest", "login", 17));
        String text = ThrowableText.stackTrace(t, 10);
        assertTrue(text.contains("LoginPage.submit"), text);
        assertTrue(text.contains("LoginTest.login"), text);
        assertFalse(text.contains("org.testng"), text);
        assertFalse(text.contains("jdk.internal"), text);
    }

    @Test
    void lineCapAndZeroAreHonoured() {
        Throwable t = withFrames("x", frame("a.A", "a", 1), frame("b.B", "b", 2), frame("c.C", "c", 3));
        assertEquals(2, ThrowableText.stackTrace(t, 2).split("\n").length);
        assertEquals("", ThrowableText.stackTrace(t, 0));
    }

    @Test
    void rootCauseIsIncluded() {
        RuntimeException cause = new IllegalArgumentException("connection refused");
        cause.setStackTrace(new StackTraceElement[] {frame("net.Connect", "open", 9)});
        RuntimeException top = new RuntimeException("wrapper", cause);
        top.setStackTrace(new StackTraceElement[] {frame("a.A", "a", 1)});
        String text = ThrowableText.stackTrace(top, 10);
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: connection refused"), text);
        assertTrue(text.contains("net.Connect.open"), text);
    }
}
