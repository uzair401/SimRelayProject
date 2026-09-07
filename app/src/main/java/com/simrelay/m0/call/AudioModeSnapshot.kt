package com.simrelay.m0.call

import android.media.AudioManager

data class AudioModeSnapshot(
    val value: Int,
    val name: String
) {
    companion object {
        fun capture(audioManager: AudioManager): AudioModeSnapshot {
            val value = audioManager.mode
            val name = when (value) {
                AudioManager.MODE_NORMAL -> "MODE_NORMAL"
                AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
                AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
                AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
                else -> "MODE_$value"
            }
            return AudioModeSnapshot(value, name)
        }
    }
}
