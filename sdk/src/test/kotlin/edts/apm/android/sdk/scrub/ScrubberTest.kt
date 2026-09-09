package edts.apm.android.sdk.scrub

import edts.apm.android.sdk.core.Event
import edts.apm.android.sdk.core.EventContext
import edts.apm.android.sdk.core.EventType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * §6.1 SEC-01/03b/05/05b of docs/02-Mobile-SDK.md.
 */
class ScrubberTest {

    // ─── redact() — SEC-05 ──────────────────────────────────────────────────

    @Test fun `redacts 08xx Indonesian phone number`() {
        val out = Scrubber.redact("Gagal mengirim OTP ke 08123456789")
        assertEquals("Gagal mengirim OTP ke [REDACTED]", out)
    }

    @Test fun `redacts plus62 Indonesian phone number`() {
        val out = Scrubber.redact("contact +6281234567890 for help")
        assertEquals("contact [REDACTED] for help", out)
    }

    @Test fun `redacts email address`() {
        val out = Scrubber.redact("user robert.maramis@example.com failed login")
        assertEquals("user [REDACTED] failed login", out)
    }

    @Test fun `redacts JWT-like token`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dQw4w9WgXcQrZ1a2b3c"
        val out = Scrubber.redact("token=$jwt")
        assertEquals("token=[REDACTED]", out)
    }

    @Test fun `redacts bare digit run of 10 or more`() {
        val out = Scrubber.redact("order id 9988776655 confirmed")
        assertEquals("order id [REDACTED] confirmed", out)
    }

    @Test fun `does not redact short digit runs`() {
        val out = Scrubber.redact("retry count 42 of 100")
        assertEquals("retry count 42 of 100", out)
    }

    @Test fun `leaves plain text untouched`() {
        val out = Scrubber.redact("network timeout while loading profile")
        assertEquals("network timeout while loading profile", out)
    }

    @Test fun `redacts multiple occurrences in one string`() {
        val out = Scrubber.redact("call 08123456789 or email a@b.com")
        assertEquals("call [REDACTED] or email [REDACTED]", out)
    }

    // ─── normalizePath() — SEC-03b ──────────────────────────────────────────

    @Test fun `normalizes long numeric path segment to id placeholder`() {
        assertEquals("/user/{id}/profile", Scrubber.normalizePath("/user/628123456789/profile"))
    }

    @Test fun `normalizes UUID path segment to id placeholder`() {
        assertEquals(
            "/orders/{id}",
            Scrubber.normalizePath("/orders/A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
        )
    }

    @Test fun `preserves short numeric and non-identity segments`() {
        assertEquals("/api/v2/items/1", Scrubber.normalizePath("/api/v2/items/1"))
    }

    @Test fun `normalizes segment containing an email to id placeholder`() {
        assertEquals("/users/{id}/settings", Scrubber.normalizePath("/users/a@b.com/settings"))
    }

    // ─── scrubQueryParams() — SEC-03 ────────────────────────────────────────

    @Test fun `redacts query values by default keeping only names`() {
        assertEquals("msisdn=[redacted]&otp=[redacted]", Scrubber.scrubQueryParams("msisdn=0812xxxx&otp=1234"))
    }

    @Test fun `keeps allowlisted parameter values`() {
        val out = Scrubber.scrubQueryParams("page=2&msisdn=0812xxxx", valueAllowlist = setOf("page"))
        assertEquals("page=2&msisdn=[redacted]", out)
    }

    @Test fun `passes through flag-only parameters without a value`() {
        assertEquals("debug", Scrubber.scrubQueryParams("debug"))
    }

    @Test fun `returns null and empty query unchanged`() {
        assertNull(Scrubber.scrubQueryParams(null))
        assertEquals("", Scrubber.scrubQueryParams(""))
    }

    // ─── scrubEvent() — SEC-01/05/05b ───────────────────────────────────────

    private fun makeEvent(attrs: kotlinx.serialization.json.JsonObject, ctx: EventContext = EventContext()) = Event(
        eventId  = "evt-1",
        type     = EventType.ERROR,
        tsClient = "2026-09-07T00:00:00.000Z",
        seq      = 1L,
        attrs    = attrs,
        ctx      = ctx
    )

    @Test fun `scrubs string attrs values but leaves numbers and booleans intact`() {
        val event = makeEvent(buildJsonObject {
            put("message", "Gagal mengirim OTP ke 08123456789")
            put("status_code", 500)
            put("retriable", true)
        })

        val scrubbed = Scrubber.scrubEvent(event)

        assertEquals("Gagal mengirim OTP ke [REDACTED]", scrubbed.attrs.getValue("message").jsonPrimitive.content)
        assertEquals("500", scrubbed.attrs.getValue("status_code").jsonPrimitive.content)
        assertEquals("true", scrubbed.attrs.getValue("retriable").jsonPrimitive.content)
    }

    @Test fun `scrubs strings inside nested objects and arrays`() {
        val event = makeEvent(buildJsonObject {
            put("nested", buildJsonObject { put("phone", "08123456789") })
            put("list", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("user a@b.com"))
            })
        })

        val scrubbed = Scrubber.scrubEvent(event)

        val nested = scrubbed.attrs.getValue("nested") as kotlinx.serialization.json.JsonObject
        assertEquals("[REDACTED]", nested.getValue("phone").jsonPrimitive.content)
        val list = scrubbed.attrs.getValue("list") as kotlinx.serialization.json.JsonArray
        assertEquals("user [REDACTED]", list[0].jsonPrimitive.content)
    }

    @Test fun `scrubs developer-set screen name (leak vector called out in spec)`() {
        val event = makeEvent(
            attrs = buildJsonObject {},
            ctx = EventContext(screen = "OTPVerification-08123456789")
        )

        val scrubbed = Scrubber.scrubEvent(event)

        assertEquals("OTPVerification-[REDACTED]", scrubbed.ctx.screen)
    }

    @Test fun `scrubEvent is a no-op on attrs without PII`() {
        val event = makeEvent(buildJsonObject { put("host", "api.example.com") })
        val scrubbed = Scrubber.scrubEvent(event)
        assertEquals("api.example.com", scrubbed.attrs.getValue("host").jsonPrimitive.content)
    }
}
