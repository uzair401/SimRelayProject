import logging

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .protocol import ProtocolError, SignalMessage, decode_message, encode_message
from .state import Connection, RelayState


logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
logger = logging.getLogger("simrelay.prototype")
app = FastAPI(title="SimRelay prototype signaling", version="0.1")
relay = RelayState()


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "protocol_version": "1"}


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()

    async def send(message: SignalMessage) -> None:
        await websocket.send_text(encode_message(message))

    connection = Connection(send)
    logger.info("event=signaling_connected")
    try:
        while True:
            raw = await websocket.receive_text()
            try:
                message = decode_message(raw)
            except ProtocolError as exception:
                await send(
                    SignalMessage(
                        message_type="pair_failed",
                        payload={"reason": str(exception)},
                    )
                )
                continue
            await relay.handle(connection, message)
    except WebSocketDisconnect:
        pass
    finally:
        await relay.disconnect(connection)
        logger.info("event=signaling_disconnected role=%s", connection.role or "unregistered")
