package com.simrelay.m0.prototype

import android.content.Context
import android.media.AudioManager
import com.simrelay.m0.audio.CallAudioBackend
import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.m0.audio.framework.FrameworkInterceptionBackend
import com.simrelay.m0.call.AndroidPstnCallControlBackend
import com.simrelay.prototype.call.CallControlBackend
import com.simrelay.prototype.call.FakeCallControlBackend

enum class PrototypeAudioBackendChoice {
    Fake,
    FrameworkInterception
}

enum class PrototypeCallControlBackendChoice {
    Fake,
    AndroidPstn
}

data class PrototypeHostConfiguration(
    val audioBackend: PrototypeAudioBackendChoice = PrototypeAudioBackendChoice.Fake,
    val callControlBackend: PrototypeCallControlBackendChoice = PrototypeCallControlBackendChoice.Fake,
    val callAudioConfig: PcmConfig = PcmConfig(16_000)
)

data class PrototypeHostBackends(
    val audio: CallAudioBackend,
    val callControl: CallControlBackend,
    val audioConfig: PcmConfig
)

class PrototypeHostBackendFactory(
    context: Context
) {
    private val applicationContext = context.applicationContext

    fun create(configuration: PrototypeHostConfiguration): PrototypeHostBackends {
        val audio = when (configuration.audioBackend) {
            PrototypeAudioBackendChoice.Fake -> FakeCallAudioBackend()
            PrototypeAudioBackendChoice.FrameworkInterception -> FrameworkInterceptionBackend(
                applicationContext,
                applicationContext.getSystemService(AudioManager::class.java)
            )
        }
        return runCatching {
            PrototypeHostBackends(
                audio = audio,
                callControl = when (configuration.callControlBackend) {
                    PrototypeCallControlBackendChoice.Fake -> FakeCallControlBackend()
                    PrototypeCallControlBackendChoice.AndroidPstn -> AndroidPstnCallControlBackend(applicationContext)
                },
                audioConfig = configuration.callAudioConfig
            )
        }.onFailure {
            runCatching { audio.close() }
        }.getOrThrow()
    }
}
