package edts.apm.android.sdk.core

/**
 * The one place the SDK names itself on the wire — `sdk.name` in the envelope (`01` §2) and the
 * `X-APM-Sdk` header (`01` §7) both read it, so they cannot disagree.
 *
 * It is `apm-android-sdk`, **not** `apmkit-android`, and not the `apmkit-<platform>` pattern iOS uses
 * (`apmkit-ios`). Decided 2026-09-07 and confirmed again 2026-10-02 (CONSTITUTION, A36). The backend
 * has to know this name: `apm-ingest`'s `LATEST_SDK_VERSIONS` looks SDKs up by `sdk_name`, and a name it
 * has never heard of makes the 'outdated SDK' warning silently unavailable for Android.
 *
 * Changing this value is a wire-contract change, not a rename — which is why a test pins it.
 */
internal object SdkIdentity {
    const val NAME = "apm-android-sdk"
}
