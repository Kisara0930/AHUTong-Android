package com.ahu.ahutong.data.security

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStoreException

class StrictStorageKeyPolicyTest {
    @Test fun missingKeyWithUnrelatedOldValuesNeverCallsKeyCreation() {
        var created = false
        assertThrows(KeyStoreException::class.java) {
            resolveStrictStorageKey<String>(null, hasStoredValues = true) { created = true; "replacement" }
        }
        assertFalse(created)
    }
    @Test fun existingKeyIsRetainedAndNewInstallationCanCreateItsFirstKey() {
        val existing = Any()
        assertSame(existing, resolveStrictStorageKey(existing, true) { error("must not replace") })
        var count = 0
        assertEquals("first", resolveStrictStorageKey<String>(null, false) { count++; "first" })
        assertEquals(1, count)
    }
}
