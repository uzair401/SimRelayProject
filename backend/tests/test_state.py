import pytest

from simrelay_backend.protocol import SignalMessage
from simrelay_backend.state import Connection, RelayState


class Sink:
    def __init__(self) -> None:
        self.messages: list[SignalMessage] = []

    async def send(self, message: SignalMessage) -> None:
        self.messages.append(message)


@pytest.mark.asyncio
async def test_pairing_is_one_time_and_routes_call_flow() -> None:
    state = RelayState(now_ms=lambda: 1_000)
    host_sink = Sink()
    client_sink = Sink()
    second_client_sink = Sink()
    host = Connection(host_sink.send)
    client = Connection(client_sink.send)
    second_client = Connection(second_client_sink.send)

    await state.handle(
        host,
        SignalMessage(
            message_type="host_online",
            payload={"host_id": "host", "pairing_code": "123456", "expires_at_ms": "2000"},
        ),
    )
    await state.handle(client, SignalMessage(message_type="client_online", payload={"client_id": "client"}))
    await state.handle(client, SignalMessage(message_type="pair_request", payload={"pairing_code": "123456"}))

    assert host_sink.messages[-1].message_type == "pair_success"
    assert client_sink.messages[-1].message_type == "pair_success"

    await state.handle(
        host,
        SignalMessage(
            message_type="incoming_call",
            session_id="first",
            payload={"display_identity": "Prototype caller"},
        ),
    )
    assert client_sink.messages[-1].message_type == "incoming_call"
    await state.handle(client, SignalMessage(message_type="answer", session_id="first"))
    assert host_sink.messages[-1].message_type == "answer"
    await state.handle(host, SignalMessage(message_type="hangup", session_id="first"))
    assert client_sink.messages[-1].message_type == "hangup"
    await state.handle(
        host,
        SignalMessage(message_type="call_state", session_id="first", payload={"state": "ended"}),
    )

    await state.handle(
        client,
        SignalMessage(
            message_type="outgoing_call",
            session_id="second",
            payload={"display_identity": "Prototype destination"},
        ),
    )
    assert host_sink.messages[-1].session_id == "second"

    await state.handle(second_client, SignalMessage(message_type="client_online", payload={"client_id": "other"}))
    await state.handle(second_client, SignalMessage(message_type="pair_request", payload={"pairing_code": "123456"}))
    assert second_client_sink.messages[-1].message_type == "pair_failed"


@pytest.mark.asyncio
async def test_expired_pairing_is_rejected() -> None:
    now = 1_000
    state = RelayState(now_ms=lambda: now)
    host_sink = Sink()
    client_sink = Sink()
    host = Connection(host_sink.send)
    client = Connection(client_sink.send)

    await state.handle(
        host,
        SignalMessage(
            message_type="host_online",
            payload={"host_id": "host", "pairing_code": "123456", "expires_at_ms": "1001"},
        ),
    )
    await state.handle(client, SignalMessage(message_type="client_online", payload={"client_id": "client"}))
    now = 2_000
    await state.handle(client, SignalMessage(message_type="pair_request", payload={"pairing_code": "123456"}))
    assert client_sink.messages[-1].message_type == "pair_failed"


@pytest.mark.asyncio
async def test_disconnect_ends_active_session_for_peer() -> None:
    state = RelayState(now_ms=lambda: 1_000)
    host_sink = Sink()
    client_sink = Sink()
    host = Connection(host_sink.send)
    client = Connection(client_sink.send)
    await state.handle(
        host,
        SignalMessage(
            message_type="host_online",
            payload={"host_id": "host", "pairing_code": "123456", "expires_at_ms": "2000"},
        ),
    )
    await state.handle(client, SignalMessage(message_type="client_online", payload={"client_id": "client"}))
    await state.handle(client, SignalMessage(message_type="pair_request", payload={"pairing_code": "123456"}))
    await state.handle(
        host,
        SignalMessage(
            message_type="incoming_call",
            session_id="session",
            payload={"display_identity": "Prototype caller"},
        ),
    )

    await state.disconnect(client)

    assert host_sink.messages[-1].message_type == "hangup"
    assert host_sink.messages[-1].payload["reason"] == "peer_disconnected"
