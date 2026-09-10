package com.simrelay.client.prototype

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.PrototypeSignalingMode

class ClientActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: ClientViewModel = viewModel()
                ClientScreen(viewModel)
            }
        }
    }
}

@Composable
private fun ClientScreen(viewModel: ClientViewModel) {
    val state by viewModel.uiState
    var pendingMicrophoneAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingMicrophoneAction?.invoke()
        pendingMicrophoneAction = null
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SimRelay Android Client", style = MaterialTheme.typography.headlineSmall)
        Text("Signaling mode: ${state.signalingMode}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.setSignalingMode(PrototypeSignalingMode.DirectPeer) },
                enabled = state.signalingState == ConnectionState.Disconnected,
                modifier = Modifier.weight(1f)
            ) { Text("HOST hotspot / LAN") }
            Button(
                onClick = { viewModel.setSignalingMode(PrototypeSignalingMode.DevelopmentBackend) },
                enabled = state.signalingState == ConnectionState.Disconnected,
                modifier = Modifier.weight(1f)
            ) { Text("Dev backend") }
        }
        if (state.signalingMode == PrototypeSignalingMode.DirectPeer) {
            OutlinedTextField(
                value = state.directPairingPayload,
                onValueChange = viewModel::setDirectPairingPayload,
                label = { Text("Direct pairing payload") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.signalingState == ConnectionState.Disconnected
            )
        } else {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::setServerUrl,
                label = { Text("Development signaling WebSocket URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.signalingState == ConnectionState.Disconnected
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::connect, modifier = Modifier.weight(1f)) { Text("Connect") }
            Button(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
        }
        if (state.signalingMode == PrototypeSignalingMode.DevelopmentBackend) {
            OutlinedTextField(
                value = state.pairingCode,
                onValueChange = viewModel::setPairingCode,
                label = { Text("Pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = state.signalingState == ConnectionState.Connected && !state.paired
            )
            Button(
                onClick = viewModel::pair,
                enabled = state.signalingState == ConnectionState.Connected && !state.paired,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pair with HOST") }
        }
        Text("Signaling: ${state.signalingState}")
        Text("HOST: ${if (state.hostOnline) "online" else "offline"}")
        Text("Paired: ${state.paired}")
        Text("Call: ${state.callState}")
        Text("Identity: ${state.displayIdentity ?: "none"}")
        Text("Media: ${state.mediaState}")
        Text("RX frames: ${state.rxFrames}")
        Text("TX frames: ${state.txFrames}")
        Text("Error: ${state.lastError ?: "none"}")
        if (state.callState == PrototypeCallState.Ringing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        pendingMicrophoneAction = viewModel::answer
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Answer") }
                Button(onClick = viewModel::reject, modifier = Modifier.weight(1f)) { Text("Reject") }
            }
        }
        Button(
            onClick = {
                pendingMicrophoneAction = viewModel::dial
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = state.paired && state.callState == PrototypeCallState.Idle,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Simulate outgoing call") }
        Button(
            onClick = viewModel::hangup,
            enabled = state.sessionId != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Hang up") }
    }
}
