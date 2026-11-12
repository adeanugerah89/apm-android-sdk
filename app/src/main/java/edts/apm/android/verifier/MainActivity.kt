package edts.apm.android.verifier

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import edts.apm.android.sdk.APM
import edts.apm.android.sdk.breadcrumb.BreadcrumbCategory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * One button per device-only checklist item in FEATURES.md.
 *
 * Built with plain Views and no layout XML on purpose: the point is to trigger SDK code paths on
 * a device with as little of this app's own behaviour in the way as possible.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        status = TextView(this).apply {
            text = statusLine()
            setPadding(0, 0, 0, 32)
        }
        root.addView(status)

        // Checklist 8 — ANR detected as crash_type: anr.
        //
        // 7 s deliberately: that is the case A10b fixed. The watchdog used to sleep a full
        // threshold between checks, so a 7 s block against a 5 s threshold could begin and end
        // inside one window and be missed — it was, repeatably, on this emulator. It now polls
        // every second, so this must trip.
        root.button("Block main thread 7s (ANR — item 8)") {
            toast("Blocking main thread — the UI will freeze")
            SystemClock.sleep(7_000)
        }

        // Checklist 7 — crash captured, report sent on next launch. Relaunch after this.
        root.button("Crash now (item 7)") {
            throw IllegalStateException("APM verifier: deliberate uncaught exception")
        }

        // A9b Langkah 0 — a real memory fault, not an injected signal.
        root.button("NATIVE crash: real SIGSEGV (A9b)") {
            if (!NativeCrashTrigger.isAvailable()) {
                toast("native trigger library not loaded")
            } else {
                toast("faulting for real…")
                NativeCrashTrigger.crashNow()
            }
        }

        root.button("logError (manual API)") {
            APM.logError(IllegalArgumentException("APM verifier: handled error"))
            toast("logError sent to queue")
        }

        root.button("Add breadcrumbs") {
            APM.breadcrumb("verifier: opened cart", BreadcrumbCategory.NAVIGATION)
            APM.breadcrumb("verifier: tapped pay", BreadcrumbCategory.NAVIGATION)
            toast("2 breadcrumbs recorded")
        }

        root.button("setUser") {
            APM.setUser("+628123456789")
            toast("user_id_source is now host")
        }

        // Checklist 21 — logout. Events captured after this must go out as user_id_source
        // "generated", not under the user who was signed in before (A39/A41).
        root.button("clearUser (item 21)") {
            APM.clearUser()
            toast("user_id_source is back to generated")
        }

        // Exercises the host-attached EventListener (MOB-01/02) — integration step 2.
        root.button("HTTP request (network capture)") { triggerHttpRequest() }

        root.button("Refresh status") { status.text = statusLine() }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = statusLine()
        // APM.start() is asynchronous when called from the main thread (A18), so at first paint the
        // SDK is usually still assembling and the status reads "started: false". That is accurate
        // for the instant it was rendered and misleading a second later — it cost real time during
        // A9b testing, chasing a startup failure that had not happened. Re-read once it settles.
        status.postDelayed({ status.text = statusLine() }, 1_500)
    }

    private fun statusLine(): String = buildString {
        appendLine("APM SDK started: ${APM.isStarted}")
        appendLine("networkEventListener: ${if (APM.networkEventListener() != null) "available" else "null"}")
        appendLine()
        appendLine("Queue dir: ${noBackupFilesDir.resolve("apm-queue")}")
        val queueFiles = noBackupFilesDir.resolve("apm-queue").listFiles()?.filter { it.name.endsWith(".evt") }.orEmpty()
        appendLine("Queued events: ${queueFiles.size} (${queueFiles.sumOf { it.length() }} bytes)")
        appendLine()
        // Checklist 14: on a device the Keystore works, so files must NOT be readable JSON.
        val firstByte = queueFiles.firstOrNull()?.inputStream()?.use { it.read() }
        appendLine(
            when (firstByte) {
                null -> "Encryption: no events queued yet"
                0x01 -> "Encryption: ENCRYPTED (v1 header) — item 14 pass"
                '{'.code -> "Encryption: PLAINTEXT JSON — Keystore fallback, item 14 FAIL"
                else -> "Encryption: unrecognised first byte $firstByte"
            }
        )
    }

    private fun triggerHttpRequest() {
        val client = (application as VerifierApplication).httpClient
        val request = Request.Builder().url("https://example.com/").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { toast("HTTP failed: ${e.message} (captured as network_failure)") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { runOnUiThread { toast("HTTP ${it.code} (captured as network)") } }
            }
        })
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit) {
        addView(
            Button(context).apply {
                text = label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        status.text = statusLine()
    }

}
