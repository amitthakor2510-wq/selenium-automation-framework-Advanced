package com.automation.core.tia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestClassDetectorTest {

    @Test
    void detectsAbstractClass() {
        String source = "package x;\npublic abstract class BaseTest {\n}\n";
        assertTrue(TestClassDetector.isAbstractOrInterface(source, "BaseTest"));
    }

    @Test
    void concreteClassIsNotAbstract() {
        String source = "package x;\npublic class ButtonsTest extends BaseTest {\n}\n";
        assertFalse(TestClassDetector.isAbstractOrInterface(source, "ButtonsTest"));
    }

    @Test
    void detectsInterface() {
        String source = "package x;\npublic interface DriverProvider {\n}\n";
        assertTrue(TestClassDetector.isAbstractOrInterface(source, "DriverProvider"));
    }

    @Test
    void detectsEnum() {
        String source = "package x;\npublic enum ChangeType { ADDED, MODIFIED }\n";
        assertTrue(TestClassDetector.isAbstractOrInterface(source, "ChangeType"));
    }

    @Test
    void unrelatedAbstractClassInSameFileDoesNotMatchDifferentSimpleName() {
        // Guards against a naive "contains(\"abstract\")" check matching the wrong class
        // when a file happens to mention "abstract" in a comment or an unrelated type.
        String source = "package x;\n// note: some other abstract class exists elsewhere\n"
            + "public class ButtonsTest {\n}\n";
        assertFalse(TestClassDetector.isAbstractOrInterface(source, "ButtonsTest"));
    }
}
