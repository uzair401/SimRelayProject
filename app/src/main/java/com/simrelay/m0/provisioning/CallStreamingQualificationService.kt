package com.simrelay.m0.provisioning

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CallStreamingQualificationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ServiceAction = "android.telecom.CallStreamingService"
        const val BindPermission = "android.permission.BIND_CALL_STREAMING_SERVICE"
    }
}
