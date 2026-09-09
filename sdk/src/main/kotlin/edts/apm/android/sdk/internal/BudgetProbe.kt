package edts.apm.android.sdk.internal

import android.content.Context
import java.io.File

/**
 * Negative control for the `02` §5 main-thread I/O budget test.
 *
 * It exists so a green budget result can be told apart from a broken detector: this performs a
 * deliberate blocking disk write from a frame inside `edts.apm.android.sdk`, which the StrictMode
 * harness must flag. Nothing in the SDK's real paths calls it.
 */
internal object BudgetProbe {
    fun writeAFileDeliberately(context: Context) {
        File(context.filesDir, "budget-probe.tmp").writeBytes(ByteArray(64))
    }
}
