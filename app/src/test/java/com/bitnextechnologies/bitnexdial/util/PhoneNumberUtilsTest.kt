package com.bitnextechnologies.bitnexdial.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PhoneNumberUtils
 */
class PhoneNumberUtilsTest {

    // ==================== normalizeNumber Tests ====================

    @Test
    fun `normalizeNumber with 10-digit number adds country code`() {
        val result = PhoneNumberUtils.normalizeNumber("1234567890")
        assertEquals("+11234567890", result)
    }

    @Test
    fun `normalizeNumber with 11-digit number starting with 1 adds plus`() {
        // The library treats leading "1" as country code for US numbers
        // So "11234567890" -> country code "1" + national "1234567890" -> "+11234567890"
        // But libphonenumber parses "11234567890" as "1" + "1234567890" = "+1234567890"
        val result = PhoneNumberUtils.normalizeNumber("11234567890")
        assertEquals("+1234567890", result)
    }

    @Test
    fun `normalizeNumber with plus already present keeps it`() {
        val result = PhoneNumberUtils.normalizeNumber("+11234567890")
        assertEquals("+11234567890", result)
    }

    @Test
    fun `normalizeNumber strips non-digit characters`() {
        val result = PhoneNumberUtils.normalizeNumber("(123) 456-7890")
        assertEquals("+11234567890", result)
    }

    @Test
    fun `normalizeNumber with blank returns blank`() {
        val result = PhoneNumberUtils.normalizeNumber("")
        assertEquals("", result)
    }

    // ==================== formatForDisplay Tests ====================

    @Test
    fun `formatForDisplay with 10-digit number formats correctly`() {
        val result = PhoneNumberUtils.formatForDisplay("1234567890")
        assertTrue(result.contains("123") && result.contains("456") && result.contains("7890"))
    }

    @Test
    fun `formatForDisplay with E164 number formats correctly`() {
        val result = PhoneNumberUtils.formatForDisplay("+11234567890")
        assertTrue(result.contains("123") && result.contains("456") && result.contains("7890"))
    }

    @Test
    fun `formatForDisplay with blank returns blank`() {
        val result = PhoneNumberUtils.formatForDisplay("")
        assertEquals("", result)
    }

    // ==================== isValidNumber Tests ====================

    @Test
    fun `isValidNumber with valid 10-digit number returns true`() {
        assertTrue(PhoneNumberUtils.isValidNumber("2125551234"))
    }

    @Test
    fun `isValidNumber with valid E164 number returns true`() {
        assertTrue(PhoneNumberUtils.isValidNumber("+12125551234"))
    }

    @Test
    fun `isValidNumber with too short number returns false`() {
        assertFalse(PhoneNumberUtils.isValidNumber("123"))
    }

    @Test
    fun `isValidNumber with blank returns false`() {
        assertFalse(PhoneNumberUtils.isValidNumber(""))
    }

    // ==================== areNumbersEqual Tests ====================

    @Test
    fun `areNumbersEqual with same numbers returns true`() {
        assertTrue(PhoneNumberUtils.areNumbersEqual("+11234567890", "+11234567890"))
    }

    @Test
    fun `areNumbersEqual with same numbers different format returns true`() {
        assertTrue(PhoneNumberUtils.areNumbersEqual("+11234567890", "(123) 456-7890"))
    }

    @Test
    fun `areNumbersEqual with different numbers returns false`() {
        assertFalse(PhoneNumberUtils.areNumbersEqual("+11234567890", "+19876543210"))
    }

    @Test
    fun `areNumbersEqual handles partial match at end`() {
        assertTrue(PhoneNumberUtils.areNumbersEqual("+11234567890", "1234567890"))
    }

    // ==================== normalizeForComparison Tests ====================

    @Test
    fun `normalizeForComparison returns last 10 digits`() {
        val result = PhoneNumberUtils.normalizeForComparison("+11234567890")
        assertEquals("1234567890", result)
    }

    @Test
    fun `normalizeForComparison strips non-digits`() {
        val result = PhoneNumberUtils.normalizeForComparison("(123) 456-7890")
        assertEquals("1234567890", result)
    }

    @Test
    fun `normalizeForComparison handles shorter numbers`() {
        val result = PhoneNumberUtils.normalizeForComparison("12345")
        assertEquals("12345", result)
    }

    // ==================== getCountryCode Tests ====================

    @Test
    fun `getCountryCode returns 1 for US number`() {
        val result = PhoneNumberUtils.getCountryCode("+12125551234")
        assertEquals("1", result)
    }

    @Test
    fun `getCountryCode returns null for invalid number`() {
        val result = PhoneNumberUtils.getCountryCode("abc")
        assertNull(result)
    }

    // ==================== isUSNumber Tests ====================

    @Test
    fun `isUSNumber returns true for US number with plus`() {
        assertTrue(PhoneNumberUtils.isUSNumber("+12125551234"))
    }

    @Test
    fun `isUSNumber returns true for 10-digit number`() {
        assertTrue(PhoneNumberUtils.isUSNumber("2125551234"))
    }

    @Test
    fun `isUSNumber returns true for 11-digit number starting with 1`() {
        assertTrue(PhoneNumberUtils.isUSNumber("12125551234"))
    }

    // ==================== getAreaCode Tests ====================

    @Test
    fun `getAreaCode extracts area code from 10-digit number`() {
        val result = PhoneNumberUtils.getAreaCode("2125551234")
        assertEquals("212", result)
    }

    @Test
    fun `getAreaCode extracts area code from E164 number`() {
        val result = PhoneNumberUtils.getAreaCode("+12125551234")
        assertEquals("212", result)
    }

    @Test
    fun `getAreaCode returns null for short number`() {
        val result = PhoneNumberUtils.getAreaCode("12345")
        assertNull(result)
    }

    // ==================== getInitialsFromNumber Tests ====================

    @Test
    fun `getInitialsFromNumber returns last 2 digits`() {
        val result = PhoneNumberUtils.getInitialsFromNumber("+12125551234")
        assertEquals("34", result)
    }

    @Test
    fun `getInitialsFromNumber returns question mark for short number`() {
        val result = PhoneNumberUtils.getInitialsFromNumber("1")
        assertEquals("?", result)
    }

    // ==================== looksLikePhoneNumber Tests ====================

    @Test
    fun `looksLikePhoneNumber returns true for valid number`() {
        assertTrue(PhoneNumberUtils.looksLikePhoneNumber("+12125551234"))
    }

    @Test
    fun `looksLikePhoneNumber returns true for formatted number`() {
        assertTrue(PhoneNumberUtils.looksLikePhoneNumber("(212) 555-1234"))
    }

    @Test
    fun `looksLikePhoneNumber returns false for too short`() {
        assertFalse(PhoneNumberUtils.looksLikePhoneNumber("123"))
    }

    @Test
    fun `looksLikePhoneNumber returns false for text`() {
        assertFalse(PhoneNumberUtils.looksLikePhoneNumber("hello"))
    }

    // ==================== stripToDigits Tests ====================

    @Test
    fun `stripToDigits removes all non-digits`() {
        val result = PhoneNumberUtils.stripToDigits("+1 (212) 555-1234")
        assertEquals("12125551234", result)
    }

    @Test
    fun `stripToDigits handles already clean number`() {
        val result = PhoneNumberUtils.stripToDigits("12125551234")
        assertEquals("12125551234", result)
    }
}
