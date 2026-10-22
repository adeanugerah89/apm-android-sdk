package edts.apm.android.sdk.breadcrumb

import kotlinx.serialization.Serializable

/** One ring buffer entry — `01` §4.5.1. Field names are the literal wire keys, lowercase. */
@Serializable
internal data class BreadcrumbEntry(
    val timestamp: String,   // ISO-8601 UTC
    val category: BreadcrumbCategory,
    val level: BreadcrumbLevel,
    val message: String
)
