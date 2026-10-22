package com.auditlab.academic.tests

import android.app.Activity
import android.app.Instrumentation
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.io.PrintWriter
import java.io.StringWriter

/** Uses the framework runner protocol; no new test library or production dependency. */
class AuditLabTestRunner : Instrumentation() {
    private var selectedGroup: String? = null
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(javaClass.classLoader, TestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selectedGroup = arguments?.getString("testGroup")
        start()
    }

    override fun onStart() {
        val all = SQLiteEventTests.cases(targetContext) + SQLiteAcceptanceTests.cases(targetContext) + SimulationUiTests.cases(this) + ExportUiTests.cases(this)
        val cases = all.filter { selectedGroup == null || it.group == selectedGroup }
        if (cases.isEmpty()) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("shortMsg", "No matching instrumented tests") })
            return
        }
        var failures = 0
        cases.forEachIndexed { index, case ->
            val status = Bundle().apply {
                putString("id", "AuditLabTestRunner")
                putString("class", case.group)
                putString("test", case.name)
                putInt("current", index + 1)
                putInt("numtests", cases.size)
            }
            sendStatus(1, status)
            try {
                case.run()
                status.putString("stream", ".")
                sendStatus(0, status)
            } catch (failure: Throwable) {
                failures++
                val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
                status.putString("stack", trace)
                status.putString("stream", "\n${case.name}: $trace\n")
                sendStatus(-2, status)
            }
        }
        finish(Activity.RESULT_OK, Bundle().apply {
            putString("stream", "\nTests run: ${cases.size}, Failures: $failures\n")
            if (failures != 0) putString("shortMsg", "$failures instrumented tests failed")
        })
    }
}

internal data class InstrumentedCase(val group: String, val name: String, val run: () -> Unit)
