package com.simrelay.m0

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simrelay.m0.audio.AudioAction
import com.simrelay.m0.audio.SessionState
import com.simrelay.m0.presentation.M0UiState
import com.simrelay.m0.presentation.M0ViewModel
import com.simrelay.m0.ui.theme.SimRelayM0Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimRelayM0Theme {
                val viewModel: M0ViewModel = viewModel()
                M0App(viewModel, intent.getBooleanExtra(RunProbeExtra, false))
            }
        }
    }
}

@Composable
private fun M0App(viewModel: M0ViewModel, runProbeOnLaunch: Boolean) {
    val state by viewModel.uiState
    val context = LocalContext.current
    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isEmpty()) pendingAudioAction?.invoke()
        else denied.forEach(viewModel::reportPermissionDenied)
        pendingAudioAction = null
    }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) viewModel.reportPermissionDenied(Manifest.permission.READ_PHONE_STATE)
        viewModel.refreshCallStateMonitor()
        viewModel.runCapabilityProbe()
    }
    val runProbe = {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            viewModel.refreshCallStateMonitor()
            viewModel.runCapabilityProbe()
        } else {
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }
    val withBackendPermissions: (AudioAction, () -> Unit) -> Unit = { audioAction, action ->
        val missingPermissions = viewModel.runtimePermissionsFor(audioAction)
        if (missingPermissions.isEmpty()) {
            action()
        } else {
            pendingAudioAction = action
            audioPermissionLauncher.launch(missingPermissions)
        }
    }

    LaunchedEffect(runProbeOnLaunch) {
        if (runProbeOnLaunch) viewModel.runCapabilityProbe()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        DiagnosticScreen(
            state = state,
            onProbe = runProbe,
            onStartCapture = {
                withBackendPermissions(AudioAction.DownlinkCapture, viewModel::startDownlinkCapture)
            },
            onStopCapture = viewModel::stopDownlinkCapture,
            onStartInjection = {
                withBackendPermissions(AudioAction.UplinkInjection, viewModel::startInjection)
            },
            onStopInjection = viewModel::stopInjection,
            onStartFullDuplex = {
                withBackendPermissions(AudioAction.FullDuplex, viewModel::startFullDuplex)
            },
            onStopAll = viewModel::stopAll,
            onExport = viewModel::exportDiagnostics,
            modifier = Modifier.padding(padding)
        )
    }
}

private const val RunProbeExtra = "com.simrelay.m0.RUN_PROBE"

@Composable
private fun DiagnosticScreen(
    state: M0UiState,
    onProbe: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onStartInjection: () -> Unit,
    onStopInjection: () -> Unit,
    onStartFullDuplex: () -> Unit,
    onStopAll: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val details = listOf(
        "Device" to state.deviceModel,
        "Android" to state.androidVersion,
        "Fingerprint" to state.buildFingerprint,
        "Selected backend" to state.selectedBackend.value,
        "Framework capability" to state.capabilityState,
        "Session readiness" to state.readiness,
        "CALL_AUDIO_INTERCEPTION" to if (state.callAudioInterceptionGranted) "granted" else "not granted",
        "Provisioning route" to state.provisioningRoute,
        "Provisioning requirement" to (state.provisioningSummary ?: "not evaluated"),
        "Framework APIs" to frameworkPresenceText(state),
        "PSTN interceptable" to (state.pstnInterceptable?.toString() ?: "not evaluated"),
        "Call state" to state.callState.name,
        "Audio mode" to state.audioMode,
        "Session" to state.sessionState.name,
        "Capture" to state.captureState,
        "Injection" to state.injectionState,
        "RMS" to (state.latestRms?.let { "%.2f".format(it) } ?: "not available"),
        "Peak" to (state.latestPeak?.toString() ?: "not available"),
        "Output" to (state.outputPath ?: "not available"),
        "Last error" to (state.lastError ?: "none")
    )
    val canProbe = state.sessionState in setOf(SessionState.Idle, SessionState.Ready, SessionState.Error)
    val canStart = state.sessionState == SessionState.Ready && state.capabilitySupported

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("SIM Relay M0", style = MaterialTheme.typography.headlineMedium)
        }
        items(details) { (label, value) ->
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { HorizontalDivider() }
        item {
            ActionButton("Run Capability Probe", canProbe, onProbe)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Start Downlink Capture", canStart, onStartCapture, Modifier.weight(1f))
                ActionButton("Stop Downlink Capture", state.captureState != "Stopped", onStopCapture, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Inject 1 kHz Tone", canStart, onStartInjection, Modifier.weight(1f))
                ActionButton("Stop Injection", state.injectionState != "Stopped", onStopInjection, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Start Full Duplex", canStart, onStartFullDuplex, Modifier.weight(1f))
                ActionButton("Stop All", true, onStopAll, Modifier.weight(1f))
            }
        }
        item {
            ActionButton("Export Diagnostics", state.outputPath != null, onExport)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Text(text)
    }
}

private fun frameworkPresenceText(state: M0UiState): String = with(state.frameworkApiPresence) {
    "probe=${interceptability.access}, downlink=${downlinkExtraction.access}, uplink=${uplinkInjection.access}"
}
