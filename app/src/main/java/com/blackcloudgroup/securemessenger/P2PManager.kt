package com.blackcloudgroup.securemessenger

import io.libp2p.core.Host

class P2PManager {
    // Just a placeholder to ensure the library is importable without complex initialization for now
    fun checkLibraryAccess(): Boolean {
        return Host::class.java.canonicalName != null
    }
}
