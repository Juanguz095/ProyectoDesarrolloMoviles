package com.auditlab.domain

import java.io.IOException

data class ExportPermit internal constructor(val context: ConsentContext)
data class ExportReport(val eventCount: Int)
fun interface CsvDestination { @Throws(IOException::class) fun write(bytes: ByteArray) }

/** Revalidates before even opening the destination; holds the shared coordinator through close. */
class ExportService(
    private val audit: AuditService,
    private val events: EventSnapshotSource,
    private val coordinator: AuditCoordinator,
) {
    fun prepare(): AuditResult<ExportPermit> = coordinator.exclusive {
        when (val result = audit.currentAcceptance()) {
            is AuditResult.Success -> AuditResult.Success(ExportPermit(result.value))
            is AuditResult.Rejected -> result
        }
    }

    fun write(permit: ExportPermit, destination: CsvDestination): AuditResult<ExportReport> = coordinator.exclusive {
        val current = audit.currentAcceptance()
        if (current is AuditResult.Rejected) return@exclusive current
        if ((current as AuditResult.Success).value != permit.context) {
            return@exclusive AuditResult.Rejected(AuditError.EXPORT_EXPIRED)
        }
        val snapshot = try { events.snapshot().toList() } catch (_: StorageException) {
            return@exclusive AuditResult.Rejected(AuditError.STORAGE_UNAVAILABLE)
        }
        if (snapshot.size > AuditLimits.MAX_STORED_EVENTS || snapshot.map { it.eventId }.toSet().size != snapshot.size) {
            return@exclusive AuditResult.Rejected(AuditError.INVALID_STORAGE_RESPONSE)
        }
        try {
            destination.write(CsvReport.render(snapshot).toByteArray(Charsets.UTF_8))
            AuditResult.Success(ExportReport(snapshot.size))
        } catch (_: IOException) {
            AuditResult.Rejected(AuditError.EXPORT_WRITE_FAILED)
        } catch (_: RuntimeException) {
            AuditResult.Rejected(AuditError.EXPORT_WRITE_FAILED)
        }
    }
}
