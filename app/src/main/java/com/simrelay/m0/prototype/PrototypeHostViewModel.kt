package com.simrelay.m0.prototype

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.prototype.call.CallControlResult
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.android.WebRtcAudioMediaTransport
import com.simrelay.prototype.pairing.DirectPairingPayload
import com.simrelay.prototype.pairing.DirectPairingPayloadCodec
import com.simrelay.prototype.pairing.PairingCodeGenerator
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.DevelopmentBackendSignalingTransport
import com.simrelay.prototype.transport.DirectPeerAddressResolver
import com.simrelay.prototype.transport.DirectPeerConfiguration
import com.simrelay.prototype.transport.DirectPeerSignalingTransport
import com.simrelay.prototype.transport.PrototypeSignalingMode
import com.simrelay.prototype.transport.SignalingTransport
import java.util.UUID

data class PrototypeHostUiState(
    val signalingMode: PrototypeSignalingMode = PrototypeSignalingMode.DirectPeer,
    val serverUrl: String = "ws://10.0.2.2:8000/ws",
    val directAddress: String = DirectPeerAddressResolver.findLanIpv4() ?: "127.0.0.1",
    val directPort: String = DefaultDirectPort.toString(),
    val directPairingPayload: String? = null,
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
    val audioBackendId: AudioBackendId = AudioBackendId.FakeDevelopment,
    val callControlBackend: String = PrototypeCallControlBackendChoice.Fake.name,
    val mediaCodec: String? = null,
    val outboundRtpPackets: Long = 0,
    val inboundRtpPackets: Long = 0,
    val lastError: String? = null
) {
    companion object {
        const val DefaultDirectPort = 38_475
    }
}

class PrototypeHostViewModel(application: Application) : AndroidViewModel(application) {
    private val handler = Handler(Looper.getMainLooper())
    private val pairingCodes = PairingCodeGenerator(digitCount = 9)
    private val backendFactory = PrototypeHostBackendFactory(application)
    private val configuration = PrototypeHostConfiguration()
    private val hostId = UUID.randomUUID().toString()
    private val _uiState = mutableStateOf(PrototypeHostUiState())
    val uiState: State<PrototypeHostUiState> = _uiState
    private var coordinator: PrototypeSessionCoordinator? = null
    private var fakeAudio: FakeCallAudioBackend? = null
    private var registrationSent = false

    fun setServerUrl(value: String) = update { it.copy(serverUrl = value) }

    fun setDirectAddress(value: String) = update { it.copy(directAddress = value.trim()) }

    fun setDirectPort(value: String) = update { it.copy(directPort = value.filter(Char::isDigit).take(5)) }

    fun refreshDirectAddress() {
        if (uiState.value.signalingState != ConnectionState.Disconnected) return
        val address = DirectPeerAddressResolver.findLanIpv4()
        update {
            it.copy(
                directAddress = address ?: it.directAddress,
                lastError = if (address == null) "No reachable hotspot/LAN IPv4 address detected" else null
            )
        }
    }

    fun setSignalingMode(value: PrototypeSignalingMode) {
        if (uiState.value.signalingState == ConnectionState.Disconnected) {
            update { it.copy(signalingMode = value, lastError = null) }
        }
    }

    fun connect() {
        disconnect()
        val code = pairingCodes.generate()
        val expiresAt = System.currentTimeMillis() + PairingLifetimeMillis
        val signalingSetup = createSignaling(code, expiresAt).getOrElse { throwable ->
            update { it.copy(lastError = throwable.message ?: "Invalid signaling configuration") }
            return
        }
        val signaling = signalingSetup.transport
        var createdBackends: PrototypeHostBackends? = null
        var createdMedia: WebRtcAudioMediaTransport? = null
        val setup = runCatching {
            val backends = backendFactory.create(configuration)
            createdBackends = backends
            val media = WebRtcAudioMediaTransport(getApplication(), signaling)
            createdMedia = media
            val coordinator = PrototypeSessionCoordinator(
                backends.callControl,
                backends.audio,
                signaling,
                media,
                logger = PrototypeEventLogger(::log),
                audioConfig = backends.audioConfig
            )
            backends to coordinator
        }.getOrElse { throwable ->
            runCatching { createdMedia?.close() }
            runCatching { createdBackends?.callControl?.close() }
            runCatching { createdBackends?.audio?.close() }
            signaling.close()
            update { it.copy(lastError = "HOST setup failed: ${throwable.javaClass.simpleName}") }
            return
        }
        val backends = setup.first
        val audio = backends.audio
        val value = setup.second
        fakeAudio = audio as? FakeCallAudioBackend
        coordinator = value
        registrationSent = false
        update {
            PrototypeHostUiState(
                signalingMode = it.signalingMode,
                serverUrl = it.serverUrl,
                directAddress = it.directAddress,
                directPort = it.directPort,
                directPairingPayload = signalingSetup.directPairingPayload,
                pairingCode = if (it.signalingMode == PrototypeSignalingMode.DevelopmentBackend) code else null,
                pairingExpiresAtMillis = expiresAt,
                audioBackendId = audio.id,
                callControlBackend = configuration.callControlBackend.name
            )
        }
        value.setListener { snapshot ->
            val metrics = fakeAudio?.metrics()
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
                    txRms = metrics?.txRms ?: 0.0,
                    txPeak = metrics?.txPeak ?: 0,
                    mediaCodec = snapshot.mediaStats.codec,
                    outboundRtpPackets = snapshot.mediaStats.outboundPackets,
                    inboundRtpPackets = snapshot.mediaStats.inboundPackets,
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
        update {
            PrototypeHostUiState(
                signalingMode = it.signalingMode,
                serverUrl = it.serverUrl,
                directAddress = it.directAddress,
                directPort = it.directPort
            )
        }
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

    private fun createSignaling(pairingToken: String, expiresAtMillis: Long): Result<HostSignalingSetup> = runCatching {
        when (uiState.value.signalingMode) {
            PrototypeSignalingMode.DirectPeer -> {
                val port = uiState.value.directPort.toIntOrNull()
                require(port != null && port in 1..65_535) { "Direct signaling port must be between 1 and 65535" }
                val payload = DirectPairingPayload(
                    host = uiState.value.directAddress,
                    port = port,
                    pairingToken = pairingToken,
                    expiresAtMillis = expiresAtMillis
                )
                HostSignalingSetup(
                    DirectPeerSignalingTransport(
                        DirectPeerConfiguration.Host(port, pairingToken, expiresAtMillis)
                    ),
                    DirectPairingPayloadCodec.encode(payload)
                )
            }
            PrototypeSignalingMode.DevelopmentBackend -> HostSignalingSetup(
                DevelopmentBackendSignalingTransport(uiState.value.serverUrl),
                null
            )
        }
    }

    companion object {
        private const val PairingLifetimeMillis = 5 * 60 * 1_000L
    }
}

private data class HostSignalingSetup(
    val transport: SignalingTransport,
    val directPairingPayload: String?
)
