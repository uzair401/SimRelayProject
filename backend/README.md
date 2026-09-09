# SimRelay prototype signaling backend

This is the in-memory V0 signaling and pairing service. It routes call control and WebRTC negotiation messages between one paired HOST and CLIENT. Restarting the process clears connections, pairing credentials, and sessions.

Run locally:

```bash
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
.venv/bin/uvicorn --app-dir backend simrelay_backend.app:app --host 0.0.0.0 --port 8000
```

Android emulators use `ws://10.0.2.2:8000/ws`. Physical devices use the development computer's LAN address. Cleartext WebSocket support is enabled only for this local prototype; internet deployment requires TLS.

Run tests:

```bash
.venv/bin/pytest backend/tests
```

With the server running, exercise the live WebSocket flows:

```bash
.venv/bin/python backend/tools/smoke_flow.py
```
