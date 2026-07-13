package com.automation.core.listeners;

import com.automation.core.config.ConfigReader;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

    private int count = 0;

    @Override
    public boolean retry(ITestResult result) {
        int maxRetry = ConfigReader.getInt("retry.count", 2);
        if (count < maxRetry) {
            count++;
            System.out.println("Retrying test [" + result.getName() + "] attempt " + count + " of " + maxRetry);
            return true;
        }
        return false;
    }
}