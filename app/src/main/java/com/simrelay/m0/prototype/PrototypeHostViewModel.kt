package com.simrelay.m0.prototype

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.prototype.call.CallControlResult
import com.simrelay.prototype.call.FakeCallControlBackend
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.android.WebRtcPcmMediaTransport
import com.simrelay.prototype.pairing.PairingCodeGenerator
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.OkHttpSignalingTransport
import java.util.UUID

data class PrototypeHostUiState(
    val serverUrl: String = "ws://10.0.2.2:8000/ws",
    val pairingCode: String? = null,
    val pairingExpiresAtMillis: Long? = null,
    val signalingState: ConnectionState = ConnectionState.Disconnected,
    val paired: Boolean = false,
    val callState: PrototypeCallState = PrototypeCallState.Idle,
    val sessionId: String? = null,
    val mediaState: ConnectionState = ConnectionState.Disconnected,
    val rxFrames: Long = 0,
    val txFrames: Long = 0,
    val droppedFrames: Long = 0,
    val txRms: Double = 0.0,
    val txPeak: Int = 0,
    val lastError: String? = null
)

class PrototypeHostViewModel(application: Application) : AndroidViewModel(application) {
    private val handler = Handler(Looper.getMainLooper())
    private val pairingCodes = PairingCodeGenerator()
    private val hostId = UUID.randomUUID().toString()
    private val _uiState = mutableStateOf(PrototypeHostUiState())
    val uiState: State<PrototypeHostUiState> = _uiState
    private var coordinator: PrototypeSessionCoordinator? = null
    private var fakeAudio: FakeCallAudioBackend? = null
    private var registrationSent = false

    fun setServerUrl(value: String) = update { it.copy(serverUrl = value) }

    fun connect() {
        disconnect()
        val code = pairingCodes.generate()
        val expiresAt = System.currentTimeMillis() + PairingLifetimeMillis
        val signaling = OkHttpSignalingTransport(uiState.value.serverUrl)
        val media = WebRtcPcmMediaTransport(getApplication(), signaling)
        val audio = FakeCallAudioBackend()
        val value = PrototypeSessionCoordinator(
            FakeCallControlBackend(),
            audio,
            signaling,
            media,
            logger = PrototypeEventLogger(::log)
        )
        fakeAudio = audio
        coordinator = value
        registrationSent = false
        update {
            PrototypeHostUiState(
                serverUrl = it.serverUrl,
                pairingCode = code,
                pairingExpiresAtMillis = expiresAt
            )
        }
        value.setListener { snapshot ->
            val metrics = audio.metrics()
            update {
                it.copy(
                    signalingState = snapshot.signalingState,
                    paired = snapshot.paired,
                    callState = snapshot.callState,
                    sessionId = snapshot.sessionId,
                    mediaState = snapshot.mediaState,
                    rxFrames = snapshot.rxFrames,
                    txFrames = snapshot.txFrames,
                    droppedFrames = snapshot.droppedFrames,
                    txRms = metrics.txRms,
                    txPeak = metrics.txPeak,
                    lastError = snapshot.lastError
                )
            }
            if (snapshot.signalingState == ConnectionState.Connected && !registrationSent) {
                registrationSent = value.registerHost(hostId, code, expiresAt)
            }
        }
        value.connect()
    }

    fun simulateIncomingCall() {
        val result = coordinator?.simulateIncomingCall() ?: CallControlResult.Failure("HOST is not connected")
        if (result is CallControlResult.Failure) update { it.copy(lastError = result.reason) }
    }

    fun hangup() {
        val result = coordinator?.hangup() ?: CallControlResult.Failure("No active call")
        if (result is CallControlResult.Failure) update { it.copy(lastError = result.reason) }
    }

    fun disconnect() {
        coordinator?.close()
        coordinator = null
        fakeAudio = null
        registrationSent = false
        update { PrototypeHostUiState(serverUrl = it.serverUrl) }
    }

    override fun onCleared() {
        disconnect()
    }

    private fun update(transform: (PrototypeHostUiState) -> PrototypeHostUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uiState.value = transform(_uiState.value)
        } else {
            handler.post { _uiState.value = transform(_uiState.value) }
        }
    }

    private fun log(event: String, fields: Map<String, String>) {
        val safeFields = fields.filterKeys { it != "pairing_code" && it != "token" }
        val detail = safeFields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i("SimRelayPrototype", "event=$event${if (detail.isEmpty()) "" else " $detail"}")
    }

    companion object {
        private const val PairingLifetimeMillis = 5 * 60 * 1_000L
    }
}
