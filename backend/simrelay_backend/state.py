from dataclasses import dataclass
import hashlib
import hmac
import secrets
import time
from typing import Awaitable, Callable

from .protocol import SignalMessage


SendMessage = Callable[[SignalMessage], Awaitable[None]]


@dataclass(eq=False)
class Connection:
    send: SendMessage
    role: str | None = None
    identity: str | None = None


@dataclass
class PairingCredential:
    host: Connection
    digest: bytes
    expires_at_ms: int


@dataclass
class Pair:
    host: Connection
    client: Connection
    session_id: str | None = None


class RelayState:
    def __init__(self, now_ms: Callable[[], int] | None = None) -> None:
        self._now_ms = now_ms or (lambda: int(time.time() * 1000))
        self._pepper = secrets.token_bytes(32)
        self._credentials: list[PairingCredential] = []
        self._pairs: list[Pair] = []
        self._connections: set[Connection] = set()

    async def handle(self, connection: Connection, message: SignalMessage) -> None:
        self._connections.add(connection)
        if message.message_type == "host_online":
            await self._register_host(connection, message)
            return
        if message.message_type == "client_online":
            self._register_client(connection, message)
            return
        if message.message_type == "pair_request":
            await self._pair_client(connection, message.payload["pairing_code"])
            return
        await self._route(connection, message)

    async def disconnect(self, connection: Connection) -> None:
        self._connections.discard(connection)
        self._credentials = [item for item in self._credentials if item.host is not connection]
        pair = self._pair_for(connection)
        if pair is None:
            return
        peer = pair.client if pair.host is connection else pair.host
        await peer.send(
            SignalMessage(
                message_type="peer_disconnected",
                session_id=pair.session_id,
                payload={"reason": "peer_disconnected"},
            )
        )
        if pair in self._pairs:
            self._pairs.remove(pair)

    async def _register_host(self, connection: Connection, message: SignalMessage) -> None:
        if connection.role is not None:
            await self._error(connection, None, "Connection is already registered")
            return
        expires_at = _parse_positive_int(message.payload["expires_at_ms"])
        if expires_at is None or expires_at <= self._now_ms():
            await self._error(connection, None, "Pairing credential is expired")
            return
        connection.role = "host"
        connection.identity = message.payload["host_id"]
        self._credentials = [item for item in self._credentials if item.host is not connection]
        self._credentials.append(
            PairingCredential(
                connection,
                self._digest(message.payload["pairing_code"]),
                expires_at,
            )
        )

    def _register_client(self, connection: Connection, message: SignalMessage) -> None:
        if connection.role is not None:
            return
        connection.role = "client"
        connection.identity = message.payload["client_id"]

    async def _pair_client(self, connection: Connection, code: str) -> None:
        if connection.role != "client":
            await self._pair_failed(connection, "Client must register before pairing")
            return
        if self._pair_for(connection) is not None:
            await self._pair_failed(connection, "Client is already paired")
            return
        now = self._now_ms()
        self._credentials = [item for item in self._credentials if item.expires_at_ms > now]
        digest = self._digest(code)
        credential = next(
            (item for item in self._credentials if hmac.compare_digest(item.digest, digest)),
            None,
        )
        if credential is None or self._pair_for(credential.host) is not None:
            await self._pair_failed(connection, "Pairing code is invalid or expired")
            return
        self._credentials.remove(credential)
        pair = Pair(credential.host, connection)
        self._pairs.append(pair)
        success = SignalMessage(message_type="pair_success")
        await credential.host.send(success)
        await connection.send(success)

    async def _route(self, connection: Connection, message: SignalMessage) -> None:
        pair = self._pair_for(connection)
        if pair is None:
            if message.message_type == "call_state" and message.payload.get("state") in {
                "ended",
                "failure",
                "idle",
            }:
                return
            await self._error(connection, message.session_id, "Paired peer is required")
            return
        if message.message_type == "incoming_call" and connection is not pair.host:
            await self._error(connection, message.session_id, "Only HOST may create an incoming call")
            return
        if message.message_type == "outgoing_call" and connection is not pair.client:
            await self._error(connection, message.session_id, "Only CLIENT may create an outgoing call")
            return
        if message.message_type in {"incoming_call", "outgoing_call"}:
            if pair.session_id is not None:
                await self._error(connection, message.session_id, "A session is already active")
                return
            pair.session_id = message.session_id
        elif pair.session_id != message.session_id:
            await self._error(connection, message.session_id, "Session does not match active call")
            return
        peer = pair.client if connection is pair.host else pair.host
        await peer.send(message)
        if message.message_type == "call_state" and message.payload.get("state") in {
            "ended",
            "failure",
        }:
            pair.session_id = None

    def _pair_for(self, connection: Connection) -> Pair | None:
        return next(
            (item for item in self._pairs if item.host is connection or item.client is connection),
            None,
        )

    async def _pair_failed(self, connection: Connection, reason: str) -> None:
        await connection.send(SignalMessage(message_type="pair_failed", payload={"reason": reason}))

    async def _error(self, connection: Connection, session_id: str | None, reason: str) -> None:
        if session_id is None:
            await connection.send(SignalMessage(message_type="pair_failed", payload={"reason": reason}))
            return
        await connection.send(
            SignalMessage(
                message_type="session_error",
                session_id=session_id,
                payload={"reason": reason},
            )
        )

    def _digest(self, code: str) -> bytes:
        return hmac.new(self._pepper, code.encode("utf-8"), hashlib.sha256).digest()


def _parse_positive_int(value: str) -> int | None:
    try:
        parsed = int(value)
    except ValueError:
        return None
    return parsed if parsed > 0 else None
