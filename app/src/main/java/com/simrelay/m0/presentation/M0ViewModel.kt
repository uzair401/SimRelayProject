package com.simrelay.m0.presentation

import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.simrelay.m0.audio.AudioAction
import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.AudioOperation
import com.simrelay.m0.audio.BackendRequirements
import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.CallAudioBackend
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.CapabilityFailureMapper
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.DownlinkSession
import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.audio.SafeBackgroundExecutor
import com.simrelay.m0.audio.SessionReadinessState
import com.simrelay.m0.audio.SessionState
import com.simrelay.m0.audio.SessionTransitionPolicy
import com.simrelay.m0.audio.UplinkSession
import com.simrelay.m0.call.AudioModeSnapshot
import com.simrelay.m0.call.CallState
import com.simrelay.m0.call.CallStateMonitor
import com.simrelay.m0.diagnostics.CapabilityProbe
import com.simrelay.m0.diagnostics.CallSessionReadinessEvaluator
import com.simrelay.m0.diagnostics.CaptureArtifactStore
import com.simrelay.m0.diagnostics.CaptureArtifacts
import com.simrelay.m0.diagnostics.CaptureAttemptKind
import com.simrelay.m0.diagnostics.DeviceSnapshot
import com.simrelay.m0.diagnostics.DiagnosticEvent
import com.simrelay.m0.diagnostics.DiagnosticLogcat
import com.simrelay.m0.diagnostics.DiagnosticExporter
import com.simrelay.m0.diagnostics.DiagnosticReport
import com.simrelay.m0.diagnostics.PermissionSnapshot
import com.simrelay.m0.diagnostics.DeviceQualificationProfileFactory
import com.simrelay.m0.diagnostics.SensitiveDiagnosticRedactor
import com.simrelay.m0.pcm.PcmMetricSummary
import com.simrelay.m0.pcm.PcmToneGenerator
import com.simrelay.m0.pcm.StreamingPcmMetrics
import com.simrelay.m0.pcm.WavWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class M0ViewModel(application: Application) : AndroidViewModel(application) {
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executorService: ExecutorService = Executors.newFixedThreadPool(3)
    private val background = SafeBackgroundExecutor(executorService, ::containBackgroundFailure)
    private val lock = Any()
    private val exporter = DiagnosticExporter(application)
    private val artifactStore = CaptureArtifactStore()
    private val callStateMonitor = CallStateMonitor(application, ::onCallStateChanged)
    private val initialDevice = DeviceSnapshot.capture()
    private val pendingEvents = mutableListOf<DiagnosticEvent>()
    private val _uiState = mutableStateOf(
        M0UiState(
            deviceModel = "${initialDevice.manufacturer} ${initialDevice.model}",
            androidVersion = "${initialDevice.release} / API ${initialDevice.sdkInt}",
            buildFingerprint = initialDevice.fingerprint,
            audioMode = AudioModeSnapshot.capture(audioManager).name
        )
    )

    val uiState: State<M0UiState> = _uiState
    private var lifecycleState = SessionState.Idle
    @Volatile
    private var callState = CallState.Unknown
    private var backend: CallAudioBackend? = null
    private var backendCapability: CallAudioCapability? = null
    private var backendReadiness: CallAudioReadiness? = null
    private var backendRequirements = BackendRequirements.None
    private var report: DiagnosticReport? = null
    private var runDirectory: File? = null
    private var downlinkSession: DownlinkSession? = null
    private var uplinkSession: UplinkSession? = null
    @Volatile
    private var captureRequested = false
    @Volatile
    private var injectionRequested = false
    private var lastObservedAudioMode = audioManager.mode

    init {
        startCallMonitorSafely()
        background.execute(AudioOperation.ArtifactWrite) {
            artifactStore.recoverIncomplete(exporter.rootDirectory).forEach { recovered ->
                recordEvent("artifact_recovered", "path=${recovered.absolutePath}")
            }
        }
    }

    fun refreshCallStateMonitor() {
        try {
            callStateMonitor.close()
            callStateMonitor.start()
        } catch (throwable: Throwable) {
            reportNonFatal(AudioOperation.CapabilityProbe, throwable)
        }
    }

    fun runtimePermissionsFor(action: AudioAction): Array<String> = synchronized(lock) {
        backendRequirements.permissionsFor(action)
            .filter { requirement ->
                requirement.runtimeRequestable &&
                    getApplication<Application>().packageManager.checkPermission(
                        requirement.name,
                        getApplication<Application>().packageName
                    ) != PackageManager.PERMISSION_GRANTED
            }
            .map { it.name }
            .toTypedArray()
    }

    fun runCapabilityProbe() {
        synchronized(lock) {
            if (lifecycleState !in setOf(SessionState.Idle, SessionState.Ready, SessionState.Error)) return
            transitionLocked(SessionState.Probing)
        }
        recordAudioModeIfChanged()
        recordEvent("probe_started", "callState=$callState audioMode=${audioManager.mode}")
        background.execute(AudioOperation.CapabilityProbe) {
            var selectedBackend: CallAudioBackend? = null
            var adopted = false
            try {
                val (initialReport, initialSelection) = CapabilityProbe(getApplication()).run(callState)
                val observedCallState = callState
                val currentReadiness = CallSessionReadinessEvaluator.evaluate(
                    initialSelection.capability,
                    initialSelection.backend.readiness(initialSelection.capability),
                    observedCallState
                )
                val selection = initialSelection.copy(readiness = currentReadiness)
                val baseReport = initialReport.copy(
                    readiness = currentReadiness,
                    callState = observedCallState,
                    qualification = DeviceQualificationProfileFactory.create(
                        initialReport.device,
                        initialReport.permissions,
                        initialSelection.capability,
                        currentReadiness,
                        initialSelection.backend.id
                    )
                )
                selectedBackend = selection.backend
                val mergedReport = synchronized(lock) {
                    baseReport.copy(events = pendingEvents.toList() + baseReport.events).also {
                        pendingEvents.clear()
                    }
                }
                val directory = exporter.export(mergedReport)
                val previousSelection = synchronized(lock) {
                    val previous = Triple(backend, backendCapability, backendReadiness)
                    backend = selection.backend
                    backendCapability = selection.capability
                    backendReadiness = selection.readiness
                    backendRequirements = selection.backend.requirements
                    report = mergedReport
                    runDirectory = directory
                    transitionLocked(SessionState.Ready)
                    previous
                }
                adopted = true
                previousSelection.first?.close()
                val framework = selection.reports.firstOrNull {
                    it.backendId == AudioBackendId.FrameworkInterception
                }
                recordEvent("backend_selected", "backend=${selection.backend.id.value}")
                framework?.let { report ->
                    recordEvent(
                        "framework_api_state",
                        "interceptability=${report.apiPresence.interceptability.access} " +
                            "downlink=${report.apiPresence.downlinkExtraction.access} " +
                            "uplink=${report.apiPresence.uplinkInjection.access}"
                    )
                    recordEvent(
                        "pstn_interceptable",
                        "value=${report.pstnInterceptable?.toString() ?: "Unknown"}"
                    )
                }
                if (previousSelection.second?.state != framework?.state && framework != null) {
                    recordEvent(
                        "capability_changed",
                        "backend=${framework.backendId.value} state=${framework.state}"
                    )
                }
                if (previousSelection.third?.state != selection.readiness.state) {
                    recordEvent(
                        "readiness_changed",
                        "state=${selection.readiness.state} callState=$callState audioMode=${selection.readiness.audioMode}"
                    )
                }
                mergedReport.permissions.grants.forEach { permission ->
                    recordEvent(
                        "permission_state",
                        "name=${permission.name} declared=${permission.declared} granted=${permission.granted} protection=${permission.protection}"
                    )
                }
                recordEvent(
                    "probe_completed",
                    "backend=${selection.backend.id.value} capability=${selection.capability.state} readiness=${selection.readiness.state}"
                )
                persistReport()
                postState { state ->
                    state.copy(
                        selectedBackend = selection.backend.id,
                        capabilityState = framework?.state?.name ?: selection.capability.state.name,
                        callAudioInterceptionGranted = mergedReport.permissions.isGranted(
                            PermissionSnapshot.CallAudioInterception
                        ),
                        frameworkApiPresence = framework?.apiPresence ?: state.frameworkApiPresence,
                        pstnInterceptable = framework?.pstnInterceptable,
                        audioMode = mergedReport.audioSystem.mode.name,
                        readiness = selection.readiness.state.name,
                        lastError = selection.capability.takeUnless { it.isSupported }?.message,
                        outputPath = directory.absolutePath,
                        capabilitySupported = selection.capability.isSupported
                    )
                }
            } finally {
                if (!adopted) selectedBackend?.close()
            }
        }
    }

    fun startDownlinkCapture() {
        synchronized(lock) {
            if (!canStartLocked() || captureRequested) return
            captureRequested = true
            transitionLocked(SessionState.Capturing)
        }
        recordAudioModeIfChanged()
        recordEvent("capture_start_requested", "audioMode=${audioManager.mode}")
        postState { it.copy(captureState = "Starting", lastError = null) }
        background.execute(AudioOperation.OpenDownlink) {
            val artifacts = createArtifacts(CaptureAttemptKind.Downlink) ?: return@execute
            recordEvent("session_open_started", "direction=downlink attempt=${artifacts.attemptId}")
            when (val opened = openDownlink()) {
                is BackendResult.Success -> startCapture(opened.value, artifacts)
                is BackendResult.Failure -> if (captureRequested) handleSessionOpenFailure("downlink", opened.capability)
            }
        }
    }

    fun stopDownlinkCapture() {
        val session: DownlinkSession?
        synchronized(lock) {
            if (!captureRequested) return
            captureRequested = false
            session = downlinkSession
        }
        recordEvent("stop_requested", "direction=downlink")
        background.execute(AudioOperation.StopDownlink) {
            closeDownlink(session)
            synchronized(lock) {
                if (lifecycleState == SessionState.FullDuplex) transitionLocked(SessionState.Injecting)
                if (lifecycleState == SessionState.Capturing) transitionLocked(SessionState.Ready)
            }
            postState { it.copy(captureState = "Stopped") }
        }
    }

    fun startInjection() {
        synchronized(lock) {
            if (!canStartLocked() || injectionRequested) return
            injectionRequested = true
            transitionLocked(SessionState.Injecting)
        }
        recordAudioModeIfChanged()
        recordEvent("injection_start_requested", "audioMode=${audioManager.mode}")
        postState { it.copy(injectionState = "Starting", lastError = null) }
        background.execute(AudioOperation.OpenUplink) {
            recordEvent("session_open_started", "direction=uplink")
            when (val opened = openUplink()) {
                is BackendResult.Success -> startInjectionLoop(opened.value)
                is BackendResult.Failure -> if (injectionRequested) handleSessionOpenFailure("uplink", opened.capability)
            }
        }
    }

    fun stopInjection() {
        val session: UplinkSession?
        synchronized(lock) {
            if (!injectionRequested) return
            injectionRequested = false
            session = uplinkSession
        }
        recordEvent("stop_requested", "direction=uplink")
        background.execute(AudioOperation.StopUplink) {
            closeUplink(session)
            synchronized(lock) {
                if (lifecycleState == SessionState.FullDuplex) transitionLocked(SessionState.Capturing)
                if (lifecycleState == SessionState.Injecting) transitionLocked(SessionState.Ready)
            }
            postState { it.copy(injectionState = "Stopped") }
        }
    }

    fun startFullDuplex() {
        synchronized(lock) {
            if (!canStartLocked() || captureRequested || injectionRequested) return
            captureRequested = true
            injectionRequested = true
            transitionLocked(SessionState.FullDuplex)
        }
        recordAudioModeIfChanged()
        recordEvent("full_duplex_start_requested", "audioMode=${audioManager.mode}")
        postState {
            it.copy(captureState = "Starting", injectionState = "Starting", lastError = null)
        }
        background.execute(AudioOperation.OpenDownlink) {
            val artifacts = createArtifacts(CaptureAttemptKind.FullDuplex) ?: return@execute
            recordEvent("session_open_started", "direction=downlink mode=full_duplex attempt=${artifacts.attemptId}")
            val downlink = openDownlink()
            if (downlink is BackendResult.Failure) {
                if (captureRequested) handleSessionOpenFailure("downlink", downlink.capability)
                return@execute
            }
            val downlinkValue = (downlink as BackendResult.Success).value
            recordEvent("session_open_started", "direction=uplink mode=full_duplex attempt=${artifacts.attemptId}")
            val uplink = openUplink()
            if (uplink is BackendResult.Failure) {
                closeDownlink(downlinkValue)
                if (injectionRequested) handleSessionOpenFailure("uplink", uplink.capability)
                return@execute
            }
            val uplinkValue = (uplink as BackendResult.Success).value
            val cancelled = synchronized(lock) {
                if (!captureRequested || !injectionRequested) {
                    true
                } else {
                    downlinkSession = downlinkValue
                    uplinkSession = uplinkValue
                    false
                }
            }
            if (cancelled) {
                closeDownlink(downlinkValue)
                closeUplink(uplinkValue)
                return@execute
            }
            recordEvent("downlink_session_created", "attempt=${artifacts.attemptId}")
            recordEvent("uplink_session_created", "attempt=${artifacts.attemptId}")
            when (val captureStart = downlinkValue.start()) {
                is BackendResult.Failure -> {
                    fail(captureStart.capability)
                    return@execute
                }
                is BackendResult.Success -> recordEvent("capture_started", "attempt=${artifacts.attemptId}")
            }
            when (val injectionStart = uplinkValue.start()) {
                is BackendResult.Failure -> {
                    fail(injectionStart.capability)
                    return@execute
                }
                is BackendResult.Success -> recordEvent("injection_started", "attempt=${artifacts.attemptId}")
            }
            recordEvent("full_duplex_started", "attempt=${artifacts.attemptId}")
            postState {
                it.copy(
                    captureState = "Running at ${downlinkValue.config.sampleRateHz} Hz",
                    injectionState = "Running at ${uplinkValue.config.sampleRateHz} Hz",
                    outputPath = artifacts.partialWav.absolutePath
                )
            }
            background.execute(AudioOperation.ReadDownlink) { captureLoop(downlinkValue, artifacts) }
            background.execute(AudioOperation.WriteUplink) { injectionLoop(uplinkValue, artifacts.attemptId) }
        }
    }

    fun stopAll() {
        val downlink: DownlinkSession?
        val uplink: UplinkSession?
        synchronized(lock) {
            if (lifecycleState in setOf(SessionState.Stopping, SessionState.Probing)) return
            captureRequested = false
            injectionRequested = false
            downlink = downlinkSession
            uplink = uplinkSession
            transitionLocked(SessionState.Stopping)
        }
        recordEvent("stop_requested", "direction=all")
        background.execute(AudioOperation.StopDownlink) {
            closeDownlink(downlink)
            closeUplink(uplink)
            synchronized(lock) {
                transitionLocked(if (report == null) SessionState.Idle else SessionState.Ready)
            }
            postState { it.copy(captureState = "Stopped", injectionState = "Stopped") }
        }
    }

    fun exportDiagnostics() {
        background.execute(AudioOperation.ExportDiagnostics) {
            val currentReport = synchronized(lock) { report } ?: return@execute
            val currentDirectory = synchronized(lock) { runDirectory }
            val directory = exporter.export(currentReport, currentDirectory)
            recordEvent("diagnostics_exported", directory.absolutePath)
            postState { it.copy(outputPath = directory.absolutePath) }
        }
    }

    fun reportPermissionDenied(permission: String) {
        recordEvent("permission_denied", permission)
        postState { it.copy(lastError = "$permission was denied") }
    }

    override fun onCleared() {
        captureRequested = false
        injectionRequested = false
        try {
            closeDownlink(downlinkSession)
            closeUplink(uplinkSession)
            backend?.close()
            callStateMonitor.close()
        } catch (throwable: Throwable) {
            Log.e(LogTag, "ViewModel cleanup failed", throwable)
        } finally {
            executorService.shutdownNow()
        }
        super.onCleared()
    }

    private fun startCapture(session: DownlinkSession, artifacts: CaptureArtifacts) {
        val cancelled = synchronized(lock) {
            if (!captureRequested) true else {
                downlinkSession = session
                false
            }
        }
        if (cancelled) {
            closeDownlink(session)
            return
        }
        recordEvent("downlink_session_created", "attempt=${artifacts.attemptId}")
        when (val start = session.start()) {
            is BackendResult.Failure -> fail(start.capability)
            is BackendResult.Success -> {
                recordEvent("capture_started", "attempt=${artifacts.attemptId}")
                postState {
                    it.copy(
                        captureState = "Running at ${session.config.sampleRateHz} Hz",
                        outputPath = artifacts.partialWav.absolutePath
                    )
                }
                background.execute(AudioOperation.ReadDownlink) { captureLoop(session, artifacts) }
            }
        }
    }

    private fun startInjectionLoop(session: UplinkSession) {
        val cancelled = synchronized(lock) {
            if (!injectionRequested) true else {
                uplinkSession = session
                false
            }
        }
        if (cancelled) {
            closeUplink(session)
            return
        }
        recordEvent("uplink_session_created", "injection-only")
        when (val start = session.start()) {
            is BackendResult.Failure -> fail(start.capability)
            is BackendResult.Success -> {
                recordEvent("injection_started", "attempt=injection-only")
                postState { it.copy(injectionState = "Running at ${session.config.sampleRateHz} Hz") }
                background.execute(AudioOperation.WriteUplink) {
                    injectionLoop(session, "injection-only")
                }
            }
        }
    }

    private fun captureLoop(session: DownlinkSession, artifacts: CaptureArtifacts) {
        val metrics = StreamingPcmMetrics(session.config.sampleRateHz)
        val frame = ShortArray(session.config.frameSampleCount())
        var firstSampleRecorded = false
        var lastMetricsUpdate = 0L
        var lastCheckpoint = 0L
        var failure: CallAudioCapability? = null
        try {
            WavWriter(artifacts.partialWav, session.config).use { writer ->
                while (captureRequested && downlinkSession === session) {
                    when (val result = session.read(frame)) {
                        is BackendResult.Success -> if (result.value > 0) {
                            writer.write(frame, result.value)
                            metrics.add(frame, result.value)
                            val now = SystemClock.elapsedRealtime()
                            if (!firstSampleRecorded) {
                                firstSampleRecorded = true
                                recordEvent("first_rx", "attempt=${artifacts.attemptId}")
                            }
                            if (now - lastCheckpoint >= 1_000L) {
                                lastCheckpoint = now
                                writer.checkpoint()
                            }
                            if (now - lastMetricsUpdate >= 500L) {
                                lastMetricsUpdate = now
                                val snapshot = metrics.snapshot()
                                postState {
                                    it.copy(
                                        latestRms = snapshot.rms,
                                        latestPeak = snapshot.peakAbsolute,
                                        outputPath = artifacts.partialWav.absolutePath
                                    )
                                }
                            }
                        }
                        is BackendResult.Failure -> {
                            if (captureRequested) failure = result.capability
                            break
                        }
                    }
                }
            }
            val completed = artifactStore.complete(artifacts)
            recordEvent("capture_artifact_completed", completed.absolutePath)
            postState { it.copy(outputPath = completed.absolutePath) }
            updateMetrics(metrics.snapshot())
        } catch (throwable: Throwable) {
            failure = CapabilityFailureMapper.fromThrowable(
                sessionBackendId(),
                throwable,
                operation = AudioOperation.ArtifactWrite
            )
        } finally {
            closeDownlink(session)
        }
        failure?.let { if (captureRequested) fail(it) }
    }

    private fun injectionLoop(session: UplinkSession, attemptId: String) {
        val frame = PcmToneGenerator.generate(
            sampleRateHz = session.config.sampleRateHz,
            durationMillis = FrameDurationMillis.toLong()
        )
        var firstWriteRecorded = false
        try {
            while (injectionRequested && uplinkSession === session) {
                when (val result = session.write(frame)) {
                    is BackendResult.Success -> {
                        if (result.value == 0 && injectionRequested) {
                            fail(
                                CallAudioCapability(
                                    sessionBackendId(),
                                    CapabilityState.RuntimeFailure,
                                    "Uplink frame stopped before any samples were written",
                                    operation = AudioOperation.WriteUplink
                                )
                            )
                            return
                        }
                        if (!firstWriteRecorded && result.value > 0) {
                            firstWriteRecorded = true
                            recordEvent("first_tx", "attempt=$attemptId samples=${result.value}")
                        }
                    }
                    is BackendResult.Failure -> {
                        if (injectionRequested) fail(result.capability)
                        return
                    }
                }
            }
        } finally {
            closeUplink(session)
        }
    }

    private fun openDownlink(): BackendResult<DownlinkSession> {
        val selectedBackend = synchronized(lock) { backend } ?: return missingBackend(AudioOperation.OpenDownlink)
        var lastFailure: BackendResult.Failure? = null
        PcmConfig.Candidates.forEach { config ->
            when (val result = selectedBackend.openDownlink(config)) {
                is BackendResult.Success -> return result
                is BackendResult.Failure -> {
                    lastFailure = result
                    if (result.capability.state != CapabilityState.UnsupportedAudioFormat) return result
                }
            }
        }
        return lastFailure ?: missingBackend(AudioOperation.OpenDownlink)
    }

    private fun openUplink(): BackendResult<UplinkSession> {
        val selectedBackend = synchronized(lock) { backend } ?: return missingBackend(AudioOperation.OpenUplink)
        var lastFailure: BackendResult.Failure? = null
        PcmConfig.Candidates.forEach { config ->
            when (val result = selectedBackend.openUplink(config)) {
                is BackendResult.Success -> return result
                is BackendResult.Failure -> {
                    lastFailure = result
                    if (result.capability.state != CapabilityState.UnsupportedAudioFormat) return result
                }
            }
        }
        return lastFailure ?: missingBackend(AudioOperation.OpenUplink)
    }

    private fun <T> missingBackend(operation: AudioOperation): BackendResult<T> = BackendResult.Failure(
        CallAudioCapability(
            sessionBackendId(),
            CapabilityState.InitializationFailed,
            "Run the capability probe before opening audio",
            operation = operation
        )
    )

    private fun createArtifacts(kind: CaptureAttemptKind): CaptureArtifacts? {
        val directory = synchronized(lock) { runDirectory }
        if (directory == null) {
            fail(
                CallAudioCapability(
                    sessionBackendId(),
                    CapabilityState.ArtifactFailure,
                    "No diagnostic run directory is available",
                    operation = AudioOperation.ArtifactWrite
                )
            )
            return null
        }
        return try {
            artifactStore.create(directory, kind).also { artifacts ->
                recordEvent(
                    "artifact_created",
                    "attempt=${artifacts.attemptId} path=${artifacts.partialWav.absolutePath}"
                )
            }
        } catch (throwable: Throwable) {
            fail(
                CapabilityFailureMapper.fromThrowable(
                    sessionBackendId(),
                    throwable,
                    operation = AudioOperation.ArtifactWrite
                )
            )
            null
        }
    }

    private fun fail(capability: CallAudioCapability) {
        val downlink: DownlinkSession?
        val uplink: UplinkSession?
        synchronized(lock) {
            captureRequested = false
            injectionRequested = false
            downlink = downlinkSession
            uplink = uplinkSession
            if (SessionTransitionPolicy.canTransition(lifecycleState, SessionState.Error)) {
                transitionLocked(SessionState.Error)
            } else {
                lifecycleState = SessionState.Error
                postState { it.copy(sessionState = SessionState.Error) }
            }
        }
        recordEvent(
            "failure",
            "operation=${capability.operation} state=${capability.state} exception=${capability.exceptionClass} message=${capability.message}"
        )
        closeDownlink(downlink)
        closeUplink(uplink)
        background.execute(AudioOperation.ExportDiagnostics, ::persistReport)
        postState {
            it.copy(
                captureState = "Stopped",
                injectionState = "Stopped",
                lastError = "${capability.state}: ${capability.message}"
            )
        }
    }

    private fun closeDownlink(session: DownlinkSession?) {
        if (session == null) return
        try {
            session.close()
            recordEvent("session_released", "direction=downlink sampleRate=${session.config.sampleRateHz}")
        } catch (throwable: Throwable) {
            reportCleanupFailure(AudioOperation.StopDownlink, throwable)
        } finally {
            synchronized(lock) {
                if (downlinkSession === session) downlinkSession = null
            }
        }
    }

    private fun closeUplink(session: UplinkSession?) {
        if (session == null) return
        try {
            session.close()
            recordEvent("session_released", "direction=uplink sampleRate=${session.config.sampleRateHz}")
        } catch (throwable: Throwable) {
            reportCleanupFailure(AudioOperation.StopUplink, throwable)
        } finally {
            synchronized(lock) {
                if (uplinkSession === session) uplinkSession = null
            }
        }
    }

    private fun updateMetrics(metrics: PcmMetricSummary) {
        val snapshot = synchronized(lock) {
            report = report?.copy(metrics = metrics)
            report to runDirectory
        }
        snapshot.first?.let { exporter.export(it, snapshot.second) }
    }

    private fun persistReport() {
        val snapshot = synchronized(lock) { report to runDirectory }
        snapshot.first?.let { exporter.export(it, snapshot.second) }
    }

    private fun persistReportSafely() {
        try {
            persistReport()
        } catch (throwable: Throwable) {
            reportNonFatal(AudioOperation.ExportDiagnostics, throwable)
        }
    }

    private fun onCallStateChanged(state: CallState) {
        val previous = callState
        callState = state
        recordEvent("call_state_changed", "from=$previous to=$state")
        recordAudioModeIfChanged()
        refreshSessionReadiness(state)
        postState { it.copy(callState = state, audioMode = AudioModeSnapshot.capture(audioManager).name) }
    }

    private fun recordEvent(name: String, detail: String) {
        val safeDetail = SensitiveDiagnosticRedactor.redact(detail)
        val event = DiagnosticEvent(
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            name = name,
            detail = CapabilityFailureMapper.sanitize(safeDetail).orEmpty()
        )
        synchronized(lock) {
            val current = report
            if (current == null) pendingEvents += event
            else report = current.copy(events = current.events + event)
        }
        DiagnosticLogcat.emit(event)
    }

    private fun recordAudioModeIfChanged() {
        val currentMode = audioManager.mode
        val previousMode = synchronized(lock) {
            if (lastObservedAudioMode == currentMode) null else {
                val previous = lastObservedAudioMode
                lastObservedAudioMode = currentMode
                previous
            }
        }
        if (previousMode != null) {
            recordEvent("audio_mode_changed", "from=$previousMode to=$currentMode")
        }
    }

    private fun refreshSessionReadiness(state: CallState) {
        val snapshot = synchronized(lock) { Triple(backend, backendCapability, backendReadiness) }
        val selectedBackend = snapshot.first ?: return
        val capability = snapshot.second ?: return
        val baseReadiness = selectedBackend.readiness(capability)
        val readiness = CallSessionReadinessEvaluator.evaluate(capability, baseReadiness, state)
        val changed = synchronized(lock) {
            if (backend !== selectedBackend || backendCapability !== capability) return@synchronized false
            val previousState = backendReadiness?.state
            backendReadiness = readiness
            report = report?.let { current ->
                current.copy(
                    readiness = readiness,
                    callState = state,
                    qualification = DeviceQualificationProfileFactory.create(
                        current.device,
                        current.permissions,
                        capability,
                        readiness,
                        selectedBackend.id
                    )
                )
            }
            previousState != readiness.state
        }
        if (changed) recordEvent("readiness_changed", "state=${readiness.state} callState=$state")
        postState { it.copy(readiness = readiness.state.name) }
        persistReportSafely()
    }

    private fun handleSessionOpenFailure(direction: String, capability: CallAudioCapability) {
        recordEvent(
            "session_open_failed",
            "direction=$direction state=${capability.state} operation=${capability.operation}"
        )
        fail(capability)
    }

    private fun containBackgroundFailure(operation: AudioOperation, throwable: Throwable) {
        try {
            if (operation == AudioOperation.ExportDiagnostics) {
                reportNonFatal(operation, throwable)
            } else {
                fail(
                    CapabilityFailureMapper.fromThrowable(
                        sessionBackendId(),
                        throwable,
                        operation = operation
                    )
                )
            }
        } catch (containmentFailure: Throwable) {
            Log.e(LogTag, "Background failure containment failed", containmentFailure)
            captureRequested = false
            injectionRequested = false
            synchronized(lock) { lifecycleState = SessionState.Error }
            postState {
                it.copy(
                    sessionState = SessionState.Error,
                    captureState = "Stopped",
                    injectionState = "Stopped",
                    lastError = "RuntimeFailure: ${describeThrowable(throwable)}"
                )
            }
        }
    }

    private fun reportNonFatal(operation: AudioOperation, throwable: Throwable) {
        val capability = CapabilityFailureMapper.fromThrowable(
            sessionBackendId(),
            throwable,
            operation = operation
        )
        recordEvent(
            "non_fatal_failure",
            "operation=$operation state=${capability.state} ${describeThrowable(throwable)}"
        )
        postState { it.copy(lastError = "${capability.state}: ${capability.message}") }
        Log.e(LogTag, "Non-fatal operation failure", throwable)
    }

    private fun reportCleanupFailure(operation: AudioOperation, throwable: Throwable) {
        val capability = CapabilityFailureMapper.fromThrowable(
            sessionBackendId(),
            throwable,
            operation = operation
        )
        recordEvent(
            "cleanup_failure",
            "operation=$operation state=${capability.state} ${describeThrowable(throwable)}"
        )
        postState { it.copy(lastError = "${capability.state}: ${capability.message}") }
        Log.e(LogTag, "Audio cleanup failure", throwable)
    }

    private fun transitionLocked(next: SessionState) {
        check(SessionTransitionPolicy.canTransition(lifecycleState, next)) {
            "Invalid state transition $lifecycleState to $next"
        }
        lifecycleState = next
        postState { it.copy(sessionState = next) }
    }

    private fun postState(transform: (M0UiState) -> M0UiState) {
        mainHandler.post {
            _uiState.value = transform(_uiState.value)
        }
    }

    private fun canStartLocked(): Boolean =
        lifecycleState == SessionState.Ready &&
            backendCapability?.isSupported == true &&
            backendReadiness?.state in setOf(
                SessionReadinessState.ReadyToAttempt,
                SessionReadinessState.FrameworkValidationRequired
            )

    private fun sessionBackendId(): AudioBackendId = synchronized(lock) {
        backend?.id ?: AudioBackendId.Unsupported
    }

    private fun startCallMonitorSafely() {
        try {
            callStateMonitor.start()
        } catch (throwable: Throwable) {
            reportNonFatal(AudioOperation.CapabilityProbe, throwable)
        }
    }

    private fun describeThrowable(throwable: Throwable): String =
        "${throwable.javaClass.name}: ${CapabilityFailureMapper.sanitize(throwable.message)}"

    companion object {
        private const val FrameDurationMillis = 20
        private const val LogTag = "SimRelayM0"
    }
}
