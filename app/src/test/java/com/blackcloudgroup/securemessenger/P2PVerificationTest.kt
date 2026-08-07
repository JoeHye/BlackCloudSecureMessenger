package com.blackcloudgroup.securemessenger

import org.junit.Test
import org.junit.Assert.assertTrue

class P2PVerificationTest {
    @Test
    fun testLibraryAccess() {
        val manager = P2PManager()
        assertTrue(manager.checkLibraryAccess())
    }
}
