from dataclasses import dataclass, field
import json
from typing import Any


PROTOCOL_VERSION = 1

MESSAGE_TYPES = {
    "host_online",
    "client_online",
    "pair_request",
    "pair_success",
    "pair_failed",
    "incoming_call",
    "outgoing_call",
    "answer",
    "reject",
    "hangup",
    "call_state",
    "media_offer",
    "media_answer",
    "ice_candidate",
    "session_error",
}

SESSION_MESSAGES = {
    "incoming_call",
    "outgoing_call",
    "answer",
    "reject",
    "hangup",
    "call_state",
    "media_offer",
    "media_answer",
    "ice_candidate",
    "session_error",
}

REQUIRED_PAYLOAD = {
    "host_online": {"host_id", "pairing_code", "expires_at_ms"},
    "client_online": {"client_id"},
    "pair_request": {"pairing_code"},
    "incoming_call": {"display_identity"},
    "outgoing_call": {"display_identity"},
    "call_state": {"state"},
    "media_offer": {"sdp"},
    "media_answer": {"sdp"},
    "ice_candidate": {"candidate", "sdp_mid", "sdp_mline_index"},
}


@dataclass(frozen=True)
class SignalMessage:
    message_type: str
    session_id: str | None = None
    payload: dict[str, str] = field(default_factory=dict)
    timestamp_ms: int | None = None
    protocol_version: int = PROTOCOL_VERSION

    def to_dict(self) -> dict[str, Any]:
        value: dict[str, Any] = {
            "protocol_version": self.protocol_version,
            "message_type": self.message_type,
            "payload": self.payload,
        }
        if self.session_id is not None:
            value["session_id"] = self.session_id
        if self.timestamp_ms is not None:
            value["timestamp_ms"] = self.timestamp_ms
        return value


class ProtocolError(ValueError):
    pass


def decode_message(raw: str) -> SignalMessage:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exception:
        raise ProtocolError("Malformed JSON message") from exception
    if not isinstance(value, dict):
        raise ProtocolError("Message must be an object")
    allowed = {"protocol_version", "message_type", "session_id", "payload", "timestamp_ms"}
    if set(value) - allowed:
        raise ProtocolError("Message contains unknown fields")
    if value.get("protocol_version") != PROTOCOL_VERSION:
        raise ProtocolError("Unsupported protocol version")
    message_type = value.get("message_type")
    if message_type not in MESSAGE_TYPES:
        raise ProtocolError("Unknown message type")
    session_id = value.get("session_id")
    if message_type in SESSION_MESSAGES and not _valid_text(session_id, 128):
        raise ProtocolError("session_id is required")
    payload = value.get("payload", {})
    if not isinstance(payload, dict) or not all(
        isinstance(key, str) and isinstance(item, str) for key, item in payload.items()
    ):
        raise ProtocolError("payload must contain string values")
    missing = [key for key in REQUIRED_PAYLOAD.get(message_type, set()) if not payload.get(key)]
    if missing:
        raise ProtocolError(f"Missing payload field: {sorted(missing)[0]}")
    timestamp_ms = value.get("timestamp_ms")
    if timestamp_ms is not None and not isinstance(timestamp_ms, int):
        raise ProtocolError("timestamp_ms must be an integer")
    return SignalMessage(
        message_type=message_type,
        session_id=session_id,
        payload=payload,
        timestamp_ms=timestamp_ms,
    )


def encode_message(message: SignalMessage) -> str:
    return json.dumps(message.to_dict(), separators=(",", ":"), sort_keys=True)


def _valid_text(value: object, maximum: int) -> bool:
    return isinstance(value, str) and 0 < len(value) <= maximum
