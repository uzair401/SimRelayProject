package com.simrelay.m0.diagnostics

import android.util.Log

object DiagnosticLogcat {
    const val Tag = "SimRelayM0"

    fun emit(event: DiagnosticEvent) {
        Log.i(Tag, DiagnosticEventFormatter.forLogcat(event))
    }
}
