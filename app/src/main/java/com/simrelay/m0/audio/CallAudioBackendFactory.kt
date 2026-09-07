package com.simrelay.m0.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.simrelay.m0.audio.framework.FrameworkInterceptionBackend
import com.simrelay.m0.audio.legacy.LegacyPrivilegedBackend
import com.simrelay.m0.audio.vendor.VendorAudioBackend

data class BackendSelection(
    val backend: CallAudioBackend,
    val reports: List<CallAudioCapability>,
    val capability: CallAudioCapability,
    val readiness: CallAudioReadiness
)

object BackendSelectionPolicy {
    fun select(apiLevel: Int, frameworkState: CapabilityState): AudioBackendId =
        if (
            apiLevel >= 33 && frameworkState in setOf(
                CapabilityState.Supported,
                CapabilityState.PermissionMissing
            )
        ) {
            AudioBackendId.FrameworkInterception
        } else {
            AudioBackendId.Unsupported
        }
}

class CallAudioBackendFactory(private val context: Context) {
    fun probeAndSelect(): BackendSelection {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val framework = FrameworkInterceptionBackend(context, audioManager)
        val legacy = LegacyPrivilegedBackend()
        val vendor = VendorAudioBackend()
        val reports = listOf(framework.probe(), legacy.probe(), vendor.probe())
        val selectedCapability = reports.first { it.backendId == AudioBackendId.FrameworkInterception }
        val selectedId = BackendSelectionPolicy.select(Build.VERSION.SDK_INT, selectedCapability.state)
        val selected = if (selectedId == AudioBackendId.FrameworkInterception) {
            framework
        } else {
            framework.close()
            UnsupportedBackend(selectedCapability)
        }
        val capability = if (selectedId == AudioBackendId.FrameworkInterception) {
            selectedCapability
        } else {
            selected.probe()
        }
        return BackendSelection(selected, reports, capability, selected.readiness(capability))
    }
}
