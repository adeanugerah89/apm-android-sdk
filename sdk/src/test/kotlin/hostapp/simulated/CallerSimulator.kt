package hostapp.simulated

import edts.apm.android.sdk.internal.CallSite
import edts.apm.android.sdk.internal.CallSiteLocator
import edts.apm.android.sdk.manual.ManualApi

/**
 * Stands in for "some class in a host app" — deliberately NOT under edts.apm.android.sdk.*,
 * so tests can verify the locator (and anything built on it, like ManualApi.logError) correctly
 * walks past SDK frames to find an external caller, the same way it would for a real
 * integrator's code. A test living in edts.apm.android.sdk.* itself would be filtered out as
 * an SDK frame too, which is the real bug this class exists to avoid in test setup.
 */
object CallerSimulator {
    // Keep this call and the return on the same line pattern intentionally simple so the
    // asserted line number in the test stays obvious to read next to this source.
    fun callFromHere(): CallSite = CallSiteLocator.locateCaller()

    fun reportErrorFromHere(api: ManualApi, throwable: Throwable) = api.logError(throwable)
}
