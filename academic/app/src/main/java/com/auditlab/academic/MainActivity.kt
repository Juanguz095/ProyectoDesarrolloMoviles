package com.auditlab.academic

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            setText(R.string.base_title)
            textSize = 22f
            val spacing = (24 * resources.displayMetrics.density).toInt()
            setPadding(spacing, spacing * 3, spacing, spacing)
        }
        setContentView(title)
    }
}
