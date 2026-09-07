package com.simrelay.m0.diagnostics

import com.simrelay.m0.pcm.WavWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class CaptureAttemptKind(val value: String) {
    Downlink("downlink"),
    FullDuplex("full-duplex")
}

data class CaptureArtifacts(
    val attemptId: String,
    val directory: File,
    val partialWav: File,
    val completedWav: File
)

class AttemptIdGenerator(
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString() }
) {
    private val sequence = AtomicLong()

    fun next(kind: CaptureAttemptKind): String =
        "${kind.value}-${wallClockMillis()}-${sequence.incrementAndGet()}-${nonce()}"
}

class CaptureArtifactStore(
    private val idGenerator: AttemptIdGenerator = AttemptIdGenerator()
) {
    fun create(runDirectory: File, kind: CaptureAttemptKind): CaptureArtifacts {
        val attemptId = idGenerator.next(kind)
        val directory = File(File(runDirectory, "attempts"), attemptId)
        check(directory.mkdirs()) { "Unable to create capture attempt directory $attemptId" }
        return CaptureArtifacts(
            attemptId = attemptId,
            directory = directory,
            partialWav = File(directory, "rx.partial.wav"),
            completedWav = File(directory, "rx.wav")
        )
    }

    fun complete(artifacts: CaptureArtifacts): File {
        if (!artifacts.partialWav.exists()) return artifacts.partialWav
        check(!artifacts.completedWav.exists()) { "Completed capture artifact already exists" }
        if (!artifacts.partialWav.renameTo(artifacts.completedWav)) {
            artifacts.partialWav.copyTo(artifacts.completedWav, overwrite = false)
            check(artifacts.partialWav.delete()) { "Unable to remove completed partial artifact" }
        }
        return artifacts.completedWav
    }

    fun recoverIncomplete(rootDirectory: File): List<File> {
        if (!rootDirectory.isDirectory) return emptyList()
        return rootDirectory.walkTopDown()
            .filter { it.isFile && it.name == PartialWavName }
            .filter(WavWriter::recover)
            .toList()
    }

    companion object {
        private const val PartialWavName = "rx.partial.wav"
    }
}
