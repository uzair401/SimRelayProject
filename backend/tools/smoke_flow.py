import asyncio
import json
import secrets
import time

from websockets.asyncio.client import connect


PROTOCOL_VERSION = 1


def message(message_type: str, session_id: str | None = None, **payload: str) -> str:
    value: dict[str, object] = {
        "protocol_version": PROTOCOL_VERSION,
        "message_type": message_type,
        "payload": payload,
    }
    if session_id is not None:
        value["session_id"] = session_id
    return json.dumps(value)


async def receive_type(socket, expected: str, session_id: str | None = None) -> dict:
    value = json.loads(await asyncio.wait_for(socket.recv(), timeout=2))
    assert value["message_type"] == expected, value
    if session_id is not None:
        assert value["session_id"] == session_id, value
    return value


async def run(url: str = "ws://127.0.0.1:8000/ws") -> None:
    pairing_code = f"{secrets.randbelow(1_000_000):06d}"
    expires_at = str(int(time.time() * 1000) + 60_000)
    async with connect(url) as host, connect(url) as client:
        await host.send(message("host_online", host_id="smoke-host", pairing_code=pairing_code, expires_at_ms=expires_at))
        await client.send(message("client_online", client_id="smoke-client"))
        await client.send(message("pair_request", pairing_code=pairing_code))
        await receive_type(host, "pair_success")
        await receive_type(client, "pair_success")

        incoming = "incoming-one"
        await host.send(message("incoming_call", incoming, display_identity="Prototype caller"))
        await receive_type(client, "incoming_call", incoming)
        await client.send(message("answer", incoming))
        await receive_type(host, "answer", incoming)
        await host.send(message("call_state", incoming, state="active"))
        await receive_type(client, "call_state", incoming)
        await host.send(message("media_offer", incoming, sdp="prototype-offer"))
        await receive_type(client, "media_offer", incoming)
        await client.send(message("media_answer", incoming, sdp="prototype-answer"))
        await receive_type(host, "media_answer", incoming)
        await host.send(message("hangup", incoming))
        await receive_type(client, "hangup", incoming)
        await host.send(message("call_state", incoming, state="ended"))
        await receive_type(client, "call_state", incoming)

        rejected = "incoming-reject"
        await host.send(message("incoming_call", rejected, display_identity="Prototype caller"))
        await receive_type(client, "incoming_call", rejected)
        await client.send(message("reject", rejected))
        await receive_type(host, "reject", rejected)
        await host.send(message("call_state", rejected, state="ended"))
        await receive_type(client, "call_state", rejected)

        outgoing = "outgoing-one"
        await client.send(message("outgoing_call", outgoing, display_identity="Prototype destination"))
        await receive_type(host, "outgoing_call", outgoing)
        await host.send(message("call_state", outgoing, state="dialing"))
        await receive_type(client, "call_state", outgoing)
        await host.send(message("call_state", outgoing, state="active"))
        await receive_type(client, "call_state", outgoing)
        await client.send(message("hangup", outgoing))
        await receive_type(host, "hangup", outgoing)
        await host.send(message("call_state", outgoing, state="ended"))
        await receive_type(client, "call_state", outgoing)

        disconnected = "disconnect-one"
        await host.send(message("incoming_call", disconnected, display_identity="Prototype caller"))
        await receive_type(client, "incoming_call", disconnected)
        await client.close()
        ended = await receive_type(host, "hangup", disconnected)
        assert ended["payload"]["reason"] == "peer_disconnected"

    print("pairing=pass")
    print("incoming_answer=pass")
    print("media_signaling=pass")
    print("hangup=pass")
    print("reject=pass")
    print("outgoing=pass")
    print("second_session=pass")
    print("disconnect_cleanup=pass")


if __name__ == "__main__":
    asyncio.run(run())
