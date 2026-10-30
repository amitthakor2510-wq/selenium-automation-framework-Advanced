package com.automation.core.annotations;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameworkAnnotationsTest {

    @FrameworkAnnotation(author = "ClassOwner", category = "classCat")
    static class Sample {
        @FrameworkAnnotation(author = {"Amit", " ", "Amit"}, category = {"login", "smoke"})
        public void annotated() {
        }

        public void plain() {
        }

        @FrameworkAnnotation(category = "only-category")
        public void partial() {
        }
    }

    static class Unannotated {
        public void test() {
        }
    }

    private static Method method(Class<?> c, String name) throws Exception {
        return c.getMethod(name);
    }

    @Test
    void methodValuesWinAndAreCleaned() throws Exception {
        Method m = method(Sample.class, "annotated");
        assertArrayEquals(new String[] {"Amit"}, FrameworkAnnotations.authors(m, Sample.class));
        assertArrayEquals(new String[] {"login", "smoke"}, FrameworkAnnotations.categories(m, Sample.class));
    }

    @Test
    void classValuesAreTheDefault() throws Exception {
        Method m = method(Sample.class, "plain");
        assertArrayEquals(new String[] {"ClassOwner"}, FrameworkAnnotations.authors(m, Sample.class));
        assertArrayEquals(new String[] {"classCat"}, FrameworkAnnotations.categories(m, Sample.class));
    }

    @Test
    void aBlankMethodFieldFallsBackToTheClassValue() throws Exception {
        Method m = method(Sample.class, "partial");
        assertArrayEquals(new String[] {"ClassOwner"}, FrameworkAnnotations.authors(m, Sample.class));
        assertArrayEquals(new String[] {"only-category"}, FrameworkAnnotations.categories(m, Sample.class));
    }

    @Test
    void noAnnotationMeansEmpty() throws Exception {
        Method m = method(Unannotated.class, "test");
        assertEquals(0, FrameworkAnnotations.authors(m, Unannotated.class).length);
        assertEquals(0, FrameworkAnnotations.categories(m, Unannotated.class).length);
    }

    @Test
    void nullsAreTolerated() {
        assertEquals(0, FrameworkAnnotations.authors(null, null).length);
        assertEquals(0, FrameworkAnnotations.categories(null, null).length);
    }
}
