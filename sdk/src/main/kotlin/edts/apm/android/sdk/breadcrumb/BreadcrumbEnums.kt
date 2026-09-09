package edts.apm.android.sdk.breadcrumb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Breadcrumb category and level enums. (§4.5)
 * Wire values must match iOS exactly — do NOT rename @SerialName values.
 * Full implementation in A7.
 */
@Serializable
enum class BreadcrumbCategory {
    @SerialName("navigation")    NAVIGATION,
    @SerialName("user_action")   USER_ACTION,
    @SerialName("network")       NETWORK,
    @SerialName("lifecycle")     LIFECYCLE,
    @SerialName("state")         STATE,
    @SerialName("log")           LOG
}

@Serializable
enum class BreadcrumbLevel {
    @SerialName("debug")   DEBUG,
    @SerialName("info")    INFO,
    @SerialName("warning") WARNING,
    @SerialName("error")   ERROR
}
