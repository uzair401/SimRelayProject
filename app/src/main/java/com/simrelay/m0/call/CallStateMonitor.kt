package com.simrelay.m0.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

class CallStateMonitor(
    private val context: Context,
    private val onStateChanged: (CallState) -> Unit
) : AutoCloseable {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private var registeredListener: Any? = null

    fun start() {
        if (registeredListener != null) return
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            onStateChanged(CallState.Unknown)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerModern()
            } else {
                registerLegacy()
            }
        } catch (_: SecurityException) {
            registeredListener = null
            onStateChanged(CallState.Unknown)
        } catch (_: RuntimeException) {
            registeredListener = null
            onStateChanged(CallState.Unknown)
        }
    }

    override fun close() {
        val listener = registeredListener ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterModern(listener)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        registeredListener = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModern() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                onStateChanged(mapState(state))
            }
        }
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
        registeredListener = callback
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterModern(listener: Any) {
        telephonyManager.unregisterTelephonyCallback(listener as TelephonyCallback)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onStateChanged(mapState(state))
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        registeredListener = listener
    }

    private fun mapState(state: Int): CallState = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> CallState.Idle
        TelephonyManager.CALL_STATE_RINGING -> CallState.Ringing
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OffHook
        else -> CallState.Unknown
    }
}
