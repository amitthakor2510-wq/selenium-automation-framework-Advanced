package com.automation.sites.listeners;

import org.testng.IAnnotationTransformer;
import org.testng.IRetryAnalyzer;
import org.testng.annotations.ITestAnnotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class RetryListener implements IAnnotationTransformer {

    @Override
    public void transform(ITestAnnotation annotation,
                          Class testClass,
                          Constructor testConstructor,
                          Method testMethod) {
        // TestNG never leaves this null — an @Test with no retryAnalyzer set
        // still reports the IRetryAnalyzer marker interface itself as the
        // "class", not null. The old `== null` check was therefore always
        // false, so setRetryAnalyzer() never ran for a single test in the
        // suite: RetryAnalyzer existed but was silently unreachable dead
        // code, and no test was ever actually retried. Check against the
        // marker interface instead of null.
        Class<? extends IRetryAnalyzer> existing = annotation.getRetryAnalyzerClass();
        if (existing == null || existing == IRetryAnalyzer.class) {
            annotation.setRetryAnalyzer(RetryAnalyzer.class);
        }
    }
}
