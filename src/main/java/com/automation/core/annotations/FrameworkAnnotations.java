package com.automation.core.annotations;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves the effective {@link FrameworkAnnotation} values for a test: the method's own values win, a
 * blank array falls back to the class's. Blank entries are dropped and duplicates removed. Has no TestNG
 * dependency, so it can be unit-tested directly.
 */
public final class FrameworkAnnotations {

    private FrameworkAnnotations() {
    }

    /** Owner(s) of the test, or an empty array when none were declared. */
    public static String[] authors(Method method, Class<?> testClass) {
        FrameworkAnnotation onMethod = method == null ? null : method.getAnnotation(FrameworkAnnotation.class);
        FrameworkAnnotation onClass = testClass == null ? null : testClass.getAnnotation(FrameworkAnnotation.class);
        String[] chosen = onMethod != null && clean(onMethod.author()).length > 0
            ? onMethod.author() : onClass != null ? onClass.author() : new String[0];
        return clean(chosen);
    }

    /** Category labels of the test, or an empty array when none were declared. */
    public static String[] categories(Method method, Class<?> testClass) {
        FrameworkAnnotation onMethod = method == null ? null : method.getAnnotation(FrameworkAnnotation.class);
        FrameworkAnnotation onClass = testClass == null ? null : testClass.getAnnotation(FrameworkAnnotation.class);
        String[] chosen = onMethod != null && clean(onMethod.category()).length > 0
            ? onMethod.category() : onClass != null ? onClass.category() : new String[0];
        return clean(chosen);
    }

    private static String[] clean(String[] values) {
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(values == null ? new String[0] : values)
            .filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(out::add);
        return out.toArray(new String[0]);
    }
}
