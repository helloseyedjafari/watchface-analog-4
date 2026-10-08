package com.seyedjafari.analogplus.watch

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Status screen: installed face version, active or not, last error, and the activation button. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { PushController(this) }
    private lateinit var statusView: TextView
    private lateinit var noteView: TextView
    private lateinit var activateButton: Button
    private lateinit var retryButton: Button
    private var activateAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 28).toInt()
        statusView = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER }
        noteView = TextView(this).apply { textSize = 11f; gravity = Gravity.CENTER }
        activateButton = Button(this).apply { text = "Activate face"; setOnClickListener { onActivateClicked() } }
        retryButton = Button(this).apply { text = "Retry"; visibility = View.GONE; setOnClickListener { refresh() } }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = "Analog Plus"; textSize = 15f; gravity = Gravity.CENTER })
            addView(statusView)
            addView(retryButton)
            addView(activateButton)
            addView(noteView)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        requestMissing(PERM_PUSH, PERM_ACTIVE).also { if (!it) refresh() }
    }

    override fun onResume() { super.onResume(); if (::statusView.isInitialized) refresh() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Asks for the permissions that are not granted yet. Returns true when a request is pending. */
    private fun requestMissing(vararg perms: String): Boolean {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return false
        requestPermissions(missing.toTypedArray(), 1)
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val activate = activateAfterPermission
        activateAfterPermission = false
        if (activate && missingPermissions { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.isNotEmpty()) {
            noteView.text = PERMISSION_DENIED_NOTE // no activation attempt, so the one-shot is not touched
            refresh()
        } else if (activate) activateNow() else refresh()
    }

    private fun onActivateClicked() {
        if (requestMissing(PERM_PUSH, PERM_ACTIVE)) activateAfterPermission = true else activateNow()
    }

    private fun activateNow() {
        scope.launch {
            val result = controller.activateOnce()
            noteView.text = if (result.ok) "" else result.error.orEmpty()
            render()
        }
    }

    private fun refresh() {
        scope.launch { render() }
    }

    private suspend fun render() {
        val s = controller.status()
        val active = when (s.active) {
            ActiveState.ACTIVE -> "active"
            ActiveState.NOT_ACTIVE -> "not active"
            ActiveState.UNKNOWN -> "unknown"
        }
        val installed = s.installed
        statusView.text = buildString {
            when (installed) {
                InstalledState.None -> append("Face: none yet.\nSend one from the phone app.")
                is InstalledState.Version -> append("Face: version ${installed.versionCode}")
                is InstalledState.Unknown -> append("Could not read the watch face state: ${installed.reason}")
            }
            if (installed !is InstalledState.Unknown) append("\nActive: ").append(active)
            s.lastError?.let { append("\nError: ").append(it) }
        }
        retryButton.visibility = if (installed is InstalledState.Unknown) View.VISIBLE else View.GONE
        activateButton.isEnabled = installed is InstalledState.Version && s.active != ActiveState.ACTIVE
        if (s.active != ActiveState.ACTIVE && controller.activationDone && noteView.text.isNullOrEmpty()) {
            noteView.text = "Activation was already used. Pick Analog Plus in the watch face picker."
        }
    }
}
