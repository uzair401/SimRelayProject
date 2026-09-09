import pytest

from simrelay_backend.protocol import ProtocolError, SignalMessage, decode_message, encode_message


def test_round_trip() -> None:
    message = SignalMessage(
        message_type="incoming_call",
        session_id="session",
        payload={"display_identity": "Prototype caller"},
    )

    assert decode_message(encode_message(message)) == message


@pytest.mark.parametrize(
    "raw",
    [
        "{",
        '[]',
        '{"protocol_version":9,"message_type":"client_online","payload":{"client_id":"x"}}',
        '{"protocol_version":1,"message_type":"answer","payload":{}}',
        '{"protocol_version":1,"message_type":"client_online","payload":{},"extra":true}',
    ],
)
def test_rejects_malformed_messages(raw: str) -> None:
    with pytest.raises(ProtocolError):
        decode_message(raw)
