# Rooted HOST integration contract

## Replacement boundary

The living prototype uses:

```text
PrototypeSessionCoordinator
        ↓
CallAudioBackend
        ↓
FakeCallAudioBackend
```

When the provisioned PSTN HOST is available, construction changes to:

```text
PrototypeSessionCoordinator
        ↓
CallAudioBackend
        ↓
FrameworkInterceptionBackend
```

No signaling, protocol, pairing, backend, media transport, Android CLIENT, or coordinator API changes are intended.

## Required audio contract

The coordinator consumes only the existing `CallAudioBackend` contract:

- capability and readiness
- PCM format metadata
- downlink session open/start/read/stop/close
- uplink session open/start/write/stop/close
- typed `BackendResult` failures

HOST downlink is always a PCM source. HOST uplink is always a PCM sink. Full duplex uses one selected backend for both directions.

The coordinator uses 20 ms frames and handles partial uplink writes. It stops sessions before closing them and waits for the downlink loop before release.

## Call-control replacement

The prototype separately depends on `CallControlBackend`:

```text
FakeCallControlBackend
        ↓ replace
AndroidPstnCallControlBackend
```

The real implementation must preserve session identity and report incoming, ringing, answering, active, dialing, rejected, ended, and failure transitions. It must not expose Telecom implementation details to signaling or media.

## Integration sequence

1. Provision `CALL_AUDIO_INTERCEPTION` externally and verify the actual PackageManager grant.
2. Confirm the idle framework capability probe progresses beyond `PermissionMissing`.
3. Inject `FrameworkInterceptionBackend` where `FakeCallAudioBackend` is currently constructed.
4. Keep the fake call-control backend initially and validate real framework RX/TX with a controlled PSTN call.
5. Replace fake call control only after media teardown and repeated-call behavior are proven.
6. Preserve the existing fallback order: framework, legacy privileged, vendor, unsupported.

Root, Magisk, shell, manufacturer, chipset, and provisioning behavior remain external to generic orchestration.

## Real-device acceptance

The replacement is successful only when a real carrier call demonstrates downlink capture, uplink injection, full duplex, microphone exclusion, speaker isolation, feedback classification, clean teardown, and a second call without stale routing.
