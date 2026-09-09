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

Only HOST may create `incoming_call`. Only a paired CLIENT may create `outgoing_call` or send call commands. Media and call messages must match the pair's current session.

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

## Binary media frames

Media uses a WebRTC data channel named `simrelay-pcm`. Frames are unordered with zero retransmissions.

Header fields are big-endian; PCM samples are little-endian:

| Bytes | Field |
|---:|---|
| 4 | magic `SRM0` |
| 2 | media-frame version `1` |
| 2 | channel count |
| 4 | sample rate Hz |
| 2 | bits per sample |
| 2 | frame duration ms |
| 8 | sequence number |
| 8 | monotonic elapsed nanoseconds |
| remaining | signed PCM16 samples |

The initial wire format is mono, 16 kHz, PCM16, 20 ms, or 320 samples per frame. Invalid magic, version, lengths, formats, or sample counts are dropped.
