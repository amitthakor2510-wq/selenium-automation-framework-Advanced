package com.automation.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records who owns a test and which areas it covers. {@code TestListener} reads it and shows the values
 * in the Extent report as the test's <b>Author</b> and <b>Category</b> (the Dashboard tab's filter chips
 * and "tests by category" chart), so a report can be sliced by owner or feature.
 *
 * <pre>{@code
 * @FrameworkAnnotation(author = "Amit", category = {"login", "smoke"})
 * @Test(groups = "smoke")
 * public void validLogin() { ... }
 * }</pre>
 *
 * <p>Put it on a class to give every test in it a default; a method's own annotation overrides the
 * class's (see {@link FrameworkAnnotations}). It is optional and purely descriptive - it never changes
 * what a test does, and TestNG groups / quarantine tags keep working exactly as before. The categories
 * are added to the test's TestNG groups; they do not replace them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface FrameworkAnnotation {

    /** Test owner(s). Empty = fall back to the class-level value, then to the site name. */
    String[] author() default {};

    /** Feature / area labels. Empty = fall back to the class-level value. */
    String[] category() default {};
}
