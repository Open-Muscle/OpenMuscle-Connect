package org.openmuscle.connect.provisioning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApPskTest {

    @Test
    fun acceptsAValid10CharCode() {
        assertTrue(ApPsk.isValid("k7m2pqrs9n"))
        assertTrue(ApPsk.isValid("abcdefghij"))
        assertTrue(ApPsk.isValid("23456789km"))
    }

    @Test
    fun rejectsWrongLength() {
        assertFalse(ApPsk.isValid("k7m2pqrs9"))    // 9
        assertFalse(ApPsk.isValid("k7m2pqrs9nn"))  // 11
        assertFalse(ApPsk.isValid(""))
    }

    @Test
    fun rejectsAmbiguousAndOutOfSetChars() {
        assertFalse(ApPsk.isValid("k7m2pqrs9l"))   // 'l' excluded
        assertFalse(ApPsk.isValid("k7m2pqrs9o"))   // 'o' excluded
        assertFalse(ApPsk.isValid("k7m2pqrs90"))   // '0' excluded
        assertFalse(ApPsk.isValid("k7m2pqrs91"))   // '1' excluded
        assertFalse(ApPsk.isValid("K7M2PQRS9N"))   // uppercase excluded
        assertFalse(ApPsk.isValid("k7m2-qrs9n"))   // punctuation excluded
    }
}
