# SimRelay prototype protocol V1

## Envelope

Every signaling message is UTF-8 JSON:

```json
{
  "protocol_version": 1,
  "message_type": "incoming_call",
  "session_id": "4d764fc0-274c-42a0-bd86-dba511c90113",
  "payload": {
    "display_identity": "Prototype caller"
  },
  "timestamp_ms": 1788900000000
}
```

`timestamp_ms` is optional. `session_id` is required for call, media, ICE, and session-error messages. Unknown fields, unsupported versions, invalid message types, non-string payload values, and missing required fields are rejected.

## Messages

| Message | Direction | Required payload |
|---|---|---|
| `host_online` | HOST → backend | `host_id`, `pairing_code`, `expires_at_ms` |
| `client_online` | CLIENT → backend | `client_id` |
| `pair_request` | CLIENT → backend | `pairing_code` |
| `pair_success` | backend → both | none |
| `pair_failed` | backend → sender | optional `reason` |
| `peer_disconnected` | backend → remaining peer | optional `reason` |
| `incoming_call` | HOST → CLIENT | `display_identity` |
| `outgoing_call` | CLIENT → HOST | `display_identity` |
| `answer` | CLIENT → HOST | none |
| `reject` | CLIENT → HOST | none |
| `hangup` | either peer → peer | optional `reason` |
| `call_state` | HOST → CLIENT | `state` |
| `media_offer` | offerer → answerer | `sdp` |
| `media_answer` | answerer → offerer | `sdp` |
| `ice_candidate` | either peer → peer | `candidate`, `sdp_mid`, `sdp_mline_index` |
| `session_error` | backend or peer → peer | optional `reason` |

Only HOST may create `incoming_call`. Only a paired CLIENT may create `outgoing_call` or send call commands. Media and call messages must match the pair's current session. `peer_disconnected` clears pairing and tears down an active session without converting expected disconnect cleanup into an application error.

## Pairing

The HOST generates a cryptographically random six-digit code valid for five minutes. The backend stores only an HMAC digest with a process-local random pepper. Successful use removes the credential. The code is never included in application logs.

V1 has no user accounts or durable device registry. Restarting the backend invalidates all pairing state.

## Call state values

```text
idle
incoming
ringing
answering
active
rejected
dialing
ended
failure
```

The backend owns session routing, while HOST call control remains authoritative for state.

## Media

Primary voice media uses a negotiated WebRTC audio transceiver. SDP is restricted to `audio/opus`, and encoded media travels as RTP/RTCP through the peer connection. Raw PCM is never carried in a DataChannel.

Each endpoint exposes a local mono PCM16 boundary to its application. The current WebRTC adapter uses 48 kHz and approximately 20 ms application frames. `AudioFormatAdapter` converts backend or physical-device PCM at 8, 16, or 48 kHz to and from that boundary. WebRTC owns Opus packetization, RTP timestamps, jitter handling, and decoded audio delivery.

The DataChannel is not part of the current voice path. A future DataChannel may carry non-media test or control data without changing the voice contract.
