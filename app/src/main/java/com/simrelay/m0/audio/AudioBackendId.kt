package com.simrelay.m0.audio

enum class AudioBackendId(val value: String) {
    FrameworkInterception("framework_interception"),
    LegacyPrivileged("legacy_privileged"),
    VendorAudio("vendor_audio"),
    Unsupported("unsupported")
}
