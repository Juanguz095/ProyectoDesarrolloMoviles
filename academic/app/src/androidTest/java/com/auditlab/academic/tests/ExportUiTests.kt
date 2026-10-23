package com.auditlab.academic.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.DocumentsContract
import com.auditlab.academic.*
import com.auditlab.academic.storage.*
import com.auditlab.domain.CsvReport
import java.io.FileNotFoundException
import java.io.File
import java.util.UUID

internal object ExportUiTests {
    fun cases(instrumentation: Instrumentation): List<InstrumentedCase> {
        fun case(name: String, action: (UiFixture) -> Unit) = InstrumentedCase("ExportUiTests", name) {
            UiFixture(instrumentation).use { it.accept(); action(it) }
        }
        return listOf(
            case("selectedDocumentContainsAll150SnapshotRowsInUtf8") { f ->
                val db = AuditDatabase(instrumentation.targetContext, f.app.databaseName)
                try {
                    val repository = SQLiteEventRepository(db)
                    repeat(150) { repository.insertIfBelowCapacity(event(), 1000) }
                    f.main { f.session.refresh() }; f.idle()
                    f.main { check(f.session.state.events.size == 100) }
                    val uri = document()
                    try {
                        pick(f, Activity.RESULT_OK, uri, "Registros exportados: 150.")
                        val bytes = checkNotNull(instrumentation.targetContext.contentResolver.openInputStream(uri)).use { it.readBytes() }
                        check(bytes.contentEquals(CsvReport.render(repository.snapshot()).toByteArray(Charsets.UTF_8)))
                    } finally { runCatching { DocumentsContract.deleteDocument(instrumentation.targetContext.contentResolver, uri) } }
                } finally { db.close() }
            },
            case("emptyExportWritesHeaderAndReportsZero") { f ->
                val uri = document()
                try {
                    pick(f, Activity.RESULT_OK, uri, "Registros exportados: 0.")
                    val csv = checkNotNull(instrumentation.targetContext.contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    check(csv == CsvReport.HEADER + "\r\n")
                } finally { runCatching { DocumentsContract.deleteDocument(instrumentation.targetContext.contentResolver, uri) } }
            },
            case("selectorCancellationDoesNotClaimSuccess") { f ->
                pick(f, Activity.RESULT_CANCELED, null, "Exportación cancelada")
                f.main { check(f.session.state.exportRequest == null); check(f.session.state.events.isEmpty()) }
            },
            case("nullDocumentResultIsAnErrorInsteadOfCancellationOrSuccess") { f ->
                pick(f, Activity.RESULT_OK, null, "No se pudo guardar el CSV")
            },
            case("providerOpenFailureReportsError") { f ->
                pick(f, Activity.RESULT_OK, document("fail-"), "No se pudo guardar el CSV")
            },
            case("fileUriIsRejectedWithoutOverwritingAnInternalFixture") { f ->
                val target = File(instrumentation.targetContext.cacheDir, "auditlab-untouched-${UUID.randomUUID()}")
                try {
                    target.writeText("keep fixture")
                    pick(f, Activity.RESULT_OK, Uri.fromFile(target), "No se pudo guardar el CSV")
                    check(target.readText() == "keep fixture")
                } finally { target.delete() }
            },
            case("failedWriteAttemptsRemovalOfItsPartialDocument") { f ->
                val uri = document("partial-")
                pick(f, Activity.RESULT_OK, uri, "No se pudo guardar el CSV")
                // DocumentsProvider revokes URI grants on successful deletion. Regrant before
                // checking absence so permission denial cannot masquerade as a missing file.
                grantTestDocument(instrumentation, uri)
                var absent = false
                try { instrumentation.targetContext.contentResolver.openInputStream(uri)?.close() }
                catch (_: FileNotFoundException) { absent = true }
                check(absent)
            },
        )
    }

    private fun document(prefix: String = "ok-"): Uri = DocumentsContract.buildDocumentUri(
        "com.auditlab.academic.test.documents", prefix + UUID.randomUUID())

    private fun pick(f: UiFixture, code: Int, uri: Uri?, expectedMessage: String) {
        if (uri?.authority == "com.auditlab.academic.test.documents") grantTestDocument(f.instrumentation, uri)
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("text/csv") }
        val monitor = f.instrumentation.addMonitor(filter, Instrumentation.ActivityResult(code, Intent().setData(uri)), true)
        try {
            f.main { check(f.button(R.id.export_button).isEnabled); f.button(R.id.export_button).performClick() }
            f.await {
                var complete = false
                f.main { complete = !f.session.state.busy && f.session.state.message.contains(expectedMessage) }
                complete
            }
            check(monitor.hits == 1)
        } finally { f.instrumentation.removeMonitor(monitor) }
    }
}
