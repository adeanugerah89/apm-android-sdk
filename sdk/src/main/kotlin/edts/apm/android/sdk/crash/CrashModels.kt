package edts.apm.android.sdk.crash

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `threads`/`binary_images` shape — `01` §4.3.1/§4.3.2. Address-oriented fields are designed
 * for native crash symbolication (xCrash); for a Java/JVM crash they're genuinely inapplicable
 * (see CONSTITUTION 2026-09-07 D1 decision) — `objectAddr`/`instructionAddr` stay null and
 * `symbolName`/`file`/`line` are filled directly at capture time instead.
 */
@Serializable
internal data class CrashFrame(
    val index: Int,
    @SerialName("object_name") val objectName: String? = null,
    @SerialName("object_addr") val objectAddr: String? = null,
    @SerialName("instruction_addr") val instructionAddr: String? = null,
    @SerialName("is_app") val isApp: Boolean,
    @SerialName("symbol_name") val symbolName: String? = null,
    val file: String? = null,
    val line: Int? = null
)

@Serializable
internal data class CrashThread(
    val index: Int,
    val crashed: Boolean,
    val name: String,
    val frames: List<CrashFrame>
)

@Serializable
internal data class BinaryImage(
    val name: String,
    val uuid: String,
    @SerialName("base_addr") val baseAddr: String,
    val size: Long,
    val arch: String,
    @SerialName("is_app") val isApp: Boolean
)
