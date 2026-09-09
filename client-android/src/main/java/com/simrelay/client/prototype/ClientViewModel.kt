package com.simrelay.client.prototype

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.android.WebRtcPcmMediaTransport
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.OkHttpSignalingTransport
import com.simrelay.prototype.transport.SignalingTransport
import java.util.UUID

data class ClientUiState(
    val serverUrl: String = "ws://10.0.2.2:8000/ws",
    val pairingCode: String = "",
    val signalingState: ConnectionState = ConnectionState.Disconnected,
    val paired: Boolean = false,
    val hostOnline: Boolean = false,
    val callState: PrototypeCallState = PrototypeCallState.Idle,
    val sessionId: String? = null,
    val displayIdentity: String? = null,
    val mediaState: ConnectionState = ConnectionState.Disconnected,
    val rxFrames: Long = 0,
    val txFrames: Long = 0,
    val lastError: String? = null
)

class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = AndroidClientAudio(application)
    private val clientId = UUID.randomUUID().toString()
    private val _uiState = mutableStateOf(ClientUiState())
    val uiState: State<ClientUiState> = _uiState
    private var signaling: SignalingTransport? = null
    private var media: MediaTransport? = null
    private var firstRx = false
    private var firstTx = false

    fun setServerUrl(value: String) = update { it.copy(serverUrl = value) }

    fun setPairingCode(value: String) = update { it.copy(pairingCode = value.filter(Char::isDigit).take(9)) }

    fun connect() {
        disconnect()
        val transport = OkHttpSignalingTransport(uiState.value.serverUrl)
        val mediaTransport = WebRtcPcmMediaTransport(getApplication(), transport)
        signaling = transport
        media = mediaTransport
        transport.setStateListener { state, detail ->
            update {
                it.copy(
                    signalingState = state,
                    lastError = detail.takeIf { state == ConnectionState.Failed }
                )
            }
            if (state == ConnectionState.Connected) {
                transport.send(
                    SignalMessage(
                        messageType = SignalMessageType.ClientOnline,
                        payload = mapOf("client_id" to clientId)
                    )
                )
            }
            if (state == ConnectionState.Disconnected || state == ConnectionState.Failed) {
                audio.stop()
                update { it.copy(paired = false, hostOnline = false, callState = PrototypeCallState.Idle, sessionId = null) }
            }
        }
        transport.setMessageListener(::onSignal)
        mediaTransport.setStateListener { state, detail ->
            update { it.copy(mediaState = state, lastError = detail.takeIf { state == ConnectionState.Failed }) }
            if (state == ConnectionState.Connected) startAudio(mediaTransport)
            if (state == ConnectionState.Disconnected || state == ConnectionState.Failed) audio.stop()
        }
        mediaTransport.setFrameListener { frame ->
            if (audio.play(frame)) {
                if (!firstRx) {
                    firstRx = true
                    log("first_rx")
                }
                update { it.copy(rxFrames = it.rxFrames + 1) }
            }
        }
        transport.connect()
    }

    fun pair() {
        val code = uiState.value.pairingCode
        if (code.isBlank()) {
            update { it.copy(lastError = "Enter the HOST pairing code") }
            return
        }
        val sent = signaling?.send(
            SignalMessage(
                messageType = SignalMessageType.PairRequest,
                payload = mapOf("pairing_code" to code)
            )
        ) == true
        if (!sent) update { it.copy(lastError = "Pair request could not be sent") }
    }

    fun answer() = sendCallAction(SignalMessageType.Answer, PrototypeCallState.Answering)

    fun reject() = sendCallAction(SignalMessageType.Reject, PrototypeCallState.Rejected)

    fun hangup() = sendCallAction(SignalMessageType.Hangup, PrototypeCallState.Ended)

    fun dial() {
        val state = uiState.value
        if (!state.paired || state.callState != PrototypeCallState.Idle) return
        val sessionId = UUID.randomUUID().toString()
        if (signaling?.send(
                SignalMessage(
                    messageType = SignalMessageType.OutgoingCall,
                    sessionId = sessionId,
                    payload = mapOf("display_identity" to "Prototype destination")
                )
            ) == true
        ) {
            update { it.copy(callState = PrototypeCallState.Dialing, sessionId = sessionId, lastError = null) }
        }
    }

    fun disconnect() {
        audio.stop()
        media?.close()
        signaling?.close()
        media = null
        signaling = null
        update {
            it.copy(
                signalingState = ConnectionState.Disconnected,
                mediaState = ConnectionState.Disconnected,
                paired = false,
                hostOnline = false,
                callState = PrototypeCallState.Idle,
                sessionId = null
            )
        }
    }

    override fun onCleared() {
        disconnect()
        audio.close()
    }

    private fun onSignal(message: SignalMessage) {
        when (message.messageType) {
            SignalMessageType.PairSuccess -> {
                log("pair_success")
                update { it.copy(paired = true, hostOnline = true, pairingCode = "", lastError = null) }
            }
            SignalMessageType.PairFailed -> update { it.copy(lastError = message.payload["reason"] ?: "Pairing failed") }
            SignalMessageType.IncomingCall -> update {
                it.copy(
                    callState = PrototypeCallState.Ringing,
                    sessionId = message.sessionId,
                    displayIdentity = message.payload["display_identity"] ?: "Prototype caller",
                    lastError = null
                )
            }
            SignalMessageType.CallState -> applyCallState(message)
            SignalMessageType.MediaOffer,
            SignalMessageType.MediaAnswer,
            SignalMessageType.IceCandidate -> media?.handleSignal(message)
            SignalMessageType.Hangup,
            SignalMessageType.Reject -> endCall(message.payload["reason"] ?: message.messageType.name.lowercase())
            SignalMessageType.SessionError -> update { it.copy(lastError = message.payload["reason"] ?: "Session error") }
            SignalMessageType.HostOnline -> update { it.copy(hostOnline = true) }
            SignalMessageType.ClientOnline,
            SignalMessageType.PairRequest,
            SignalMessageType.OutgoingCall,
            SignalMessageType.Answer -> Unit
        }
    }

    private fun applyCallState(message: SignalMessage) {
        if (message.sessionId != uiState.value.sessionId) return
        val state = message.payload["state"]?.let { value ->
            PrototypeCallState.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        } ?: return
        update { it.copy(callState = state) }
        if (state in setOf(PrototypeCallState.Idle, PrototypeCallState.Ended, PrototypeCallState.Rejected, PrototypeCallState.Failure)) {
            endCall("remote_${state.name.lowercase()}")
        }
    }

    private fun sendCallAction(type: SignalMessageType, localState: PrototypeCallState) {
        val sessionId = uiState.value.sessionId ?: return
        if (signaling?.send(SignalMessage(messageType = type, sessionId = sessionId)) == true) {
            update { it.copy(callState = localState, lastError = null) }
            if (type != SignalMessageType.Answer) endCall(type.name.lowercase())
        }
    }

    private fun startAudio(activeMedia: MediaTransport) {
        firstRx = false
        firstTx = false
        val error = audio.start { frame ->
            if (activeMedia.send(frame)) {
                if (!firstTx) {
                    firstTx = true
                    log("first_tx")
                }
                update { it.copy(txFrames = it.txFrames + 1) }
            }
        }
        if (error != null) update { it.copy(lastError = error) }
    }

    private fun endCall(reason: String) {
        log("session_cleanup", mapOf("reason" to reason))
        audio.stop()
        media?.stop(reason)
        update {
            it.copy(
                callState = PrototypeCallState.Idle,
                sessionId = null,
                displayIdentity = null,
                mediaState = ConnectionState.Disconnected
            )
        }
    }

    private fun update(transform: (ClientUiState) -> ClientUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uiState.value = transform(_uiState.value)
        } else {
            handler.post { _uiState.value = transform(_uiState.value) }
        }
    }

    private fun log(event: String, fields: Map<String, String> = emptyMap()) {
        val detail = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i("SimRelayPrototype", "event=$event${if (detail.isEmpty()) "" else " $detail"}")
    }
}
