package com.simrelay.m0.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simrelay.m0.ui.theme.SimRelayM0Theme
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.transport.ConnectionState
import java.text.DateFormat
import java.util.Date

class PrototypeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SimRelayM0Theme {
                val viewModel: PrototypeHostViewModel = viewModel()
                PrototypeHostScreen(viewModel)
            }
        }
    }
}

@Composable
private fun PrototypeHostScreen(viewModel: PrototypeHostViewModel) {
    val state by viewModel.uiState
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SimRelay Fake HOST", style = MaterialTheme.typography.headlineSmall)
        Text("Development backend only — no PSTN or physical HOST audio")
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::setServerUrl,
            label = { Text("Signaling WebSocket URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.signalingState == ConnectionState.Disconnected
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::connect, modifier = Modifier.weight(1f)) { Text("Connect HOST") }
            Button(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
        }
        Text("Pairing code: ${state.pairingCode ?: "connect first"}")
        Text(
            "Expires: ${state.pairingExpiresAtMillis?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "not active"}"
        )
        Text("Signaling: ${state.signalingState}")
        Text("Paired: ${state.paired}")
        Text("Call: ${state.callState}")
        Text("Session: ${state.sessionId ?: "none"}")
        Text("Media: ${state.mediaState}")
        Text("RX/TX frames: ${state.rxFrames}/${state.txFrames}")
        Text("Dropped frames: ${state.droppedFrames}")
        Text("HOST TX RMS/peak: ${"%.2f".format(state.txRms)} / ${state.txPeak}")
        Text("Error: ${state.lastError ?: "none"}")
        Button(
            onClick = viewModel::simulateIncomingCall,
            enabled = state.paired && state.callState == PrototypeCallState.Idle,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Simulate incoming call") }
        Button(
            onClick = viewModel::hangup,
            enabled = state.sessionId != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Hang up") }
    }
}
