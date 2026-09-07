package com.simrelay.m0.diagnostics

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.simrelay.m0.call.AudioModeSnapshot
import com.simrelay.m0.util.JsonText

data class AudioDeviceSnapshot(
    val id: Int,
    val type: Int,
    val typeName: String,
    val productName: String,
    val isSource: Boolean,
    val isSink: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>
) {
    fun toJson(): String = JsonText.obj(
        "id" to id.toString(),
        "type" to type.toString(),
        "typeName" to JsonText.string(typeName),
        "productName" to JsonText.string(productName),
        "isSource" to isSource.toString(),
        "isSink" to isSink.toString(),
        "sampleRates" to JsonText.array(sampleRates.map(Int::toString)),
        "channelCounts" to JsonText.array(channelCounts.map(Int::toString)),
        "encodings" to JsonText.array(encodings.map(Int::toString))
    )

    companion object {
        fun capture(device: AudioDeviceInfo) = AudioDeviceSnapshot(
            id = device.id,
            type = device.type,
            typeName = typeName(device.type),
            productName = device.productName.toString(),
            isSource = device.isSource,
            isSink = device.isSink,
            sampleRates = device.sampleRates.toList(),
            channelCounts = device.channelCounts.toList(),
            encodings = device.encodings.toList()
        )

        private fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            else -> "TYPE_$type"
        }
    }
}

data class AudioSystemSnapshot(
    val mode: AudioModeSnapshot,
    val microphoneMuted: Boolean,
    val speakerphoneOn: Boolean,
    val devices: List<AudioDeviceSnapshot>
) {
    fun toJson(): String = JsonText.obj(
        "mode" to JsonText.obj(
            "value" to mode.value.toString(),
            "name" to JsonText.string(mode.name)
        ),
        "microphoneMuted" to microphoneMuted.toString(),
        "speakerphoneOn" to speakerphoneOn.toString(),
        "devices" to JsonText.array(devices.map(AudioDeviceSnapshot::toJson))
    )

    companion object {
        @Suppress("DEPRECATION")
        fun capture(audioManager: AudioManager) = AudioSystemSnapshot(
            mode = AudioModeSnapshot.capture(audioManager),
            microphoneMuted = audioManager.isMicrophoneMute,
            speakerphoneOn = audioManager.isSpeakerphoneOn,
            devices = (
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) +
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                )
                .distinctBy(AudioDeviceInfo::getId)
                .map(AudioDeviceSnapshot::capture)
        )
    }
}
