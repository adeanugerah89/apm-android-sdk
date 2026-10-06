package edts.apm.android.verifier

import android.app.Application
import edts.apm.android.sdk.APM
import edts.apm.android.sdk.ApmConfig
import okhttp3.OkHttpClient

/**
 * Integration step 1 of 2 (MOB-25): call [APM.start] from `Application.onCreate()`.
 *
 * This class is also the honest test of the integration documentation — if adopting the SDK needs
 * more than what is written here, the documentation is wrong.
 */
class VerifierApplication : Application() {

    /**
     * Integration step 2 of 2: the host app's own client, with the SDK's listener attached.
     * The SDK does not own this client and cannot attach itself to it (MOB-01/02), which is why
     * this step exists at all. Lazy so it is built after [APM.start] has run.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { APM.networkEventListener()?.let { eventListener(it) } }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        APM.start(
            context = this,
            config = ApmConfig(
                appId = BuildConfig.APPLICATION_ID,
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE.toString(),
                // Points at a host that does not exist on purpose: the checklist items are about
                // capture, queueing and fail-closed behaviour, none of which need a live backend.
                // Uploads fail as transport failures and the data stays on disk — which is itself
                // checklist item 4. Replace with a real ingest URL to exercise upload.
                // Points at a host that does not exist, so upload fails and events stay on disk —
                // which is itself checklist item 4.
                //
                // To exercise the real upload path instead, run the local HTTPS ingest server and
                // change this to "https://10.0.2.2:8443/v1/ingest". The debug build already trusts
                // that server's certificate (src/debug/res/xml/network_security_config.xml), which
                // is how checklist items 5, 11, 13 and 15 were verified — see TESTING-ON-DEVICE.md.
                ingestUrl = "https://ingest.invalid.example.com/v1/ingest",
                appKey = "verifier-app-key",
                // MOB-26. Takes effect only in a debuggable build — the release APK below ignores
                // it, which is the point of the requirement.
                debugLogging = true
            )
        )
    }
}
