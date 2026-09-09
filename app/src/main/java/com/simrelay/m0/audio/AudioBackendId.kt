package com.simrelay.m0.audio

enum class AudioBackendId(val value: String) {
    FakeDevelopment("fake_development"),
    FrameworkInterception("framework_interception"),
    LegacyPrivileged("legacy_privileged"),
    VendorAudio("vendor_audio"),
    Unsupported("unsupported")
}
