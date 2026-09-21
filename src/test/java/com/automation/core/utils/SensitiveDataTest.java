package com.automation.core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataTest {

    @Test
    void passwordLikeFieldNamesAreSensitive() {
        assertTrue(SensitiveData.isSensitiveName("By.id: password"));
        assertTrue(SensitiveData.isSensitiveName("loginPassword"));
        assertTrue(SensitiveData.isSensitiveName("input[type='password']"));
        assertTrue(SensitiveData.isSensitiveName("By.name: passwd"));
        assertTrue(SensitiveData.isSensitiveName("confirm_pwd"));
        assertTrue(SensitiveData.isSensitiveName("apiKeyField"));
        assertTrue(SensitiveData.isSensitiveName("auth-token"));
        assertTrue(SensitiveData.isSensitiveName("By.id: otp"));
    }

    @Test
    void ordinaryFieldNamesAreNotSensitive() {
        assertFalse(SensitiveData.isSensitiveName("By.id: user-name"));
        assertFalse(SensitiveData.isSensitiveName("passengerCount"));
        assertFalse(SensitiveData.isSensitiveName("passport"));
        assertFalse(SensitiveData.isSensitiveName("shippingAddress"));
        assertFalse(SensitiveData.isSensitiveName("spinner"));
        assertFalse(SensitiveData.isSensitiveName("hotel"));
        assertFalse(SensitiveData.isSensitiveName(""));
        assertFalse(SensitiveData.isSensitiveName(null));
    }

    @Test
    void maskIfSensitiveReplacesOnlySensitiveValues() {
        assertEquals(SensitiveData.MASK, SensitiveData.maskIfSensitive("passwordField", "secret_sauce"));
        assertEquals("standard_user", SensitiveData.maskIfSensitive("usernameField", "standard_user"));
    }
}
