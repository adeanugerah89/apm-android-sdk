package edts.apm.android.sdk.scrub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SEC-03b — the binding cross-platform vector table, `docs/02-Mobile-SDK.md` §6.1.1.
 *
 * Inputs and expected outputs are copied from the table, not re-derived from the prose; the
 * `#` in each name is the table's row number. Vector 23 (query string) is not here: it is a
 * statement about SEC-03, and Android never passes a query to [Scrubber.normalizePath]
 * (`HttpUrl.encodedPath` carries none — see A38). The path part of it is checked in [v23].
 */
class PathNormalizationVectorsTest {

    private fun assertNormalizes(expected: String, input: String) =
        assertEquals(expected, Scrubber.normalizePath(input))

    private fun assertUnchanged(input: String) = assertNormalizes(input, input)

    @Test fun v01() = assertNormalizes(
        "/customer/api/mobile/v2/address/food/{id}", "/customer/api/mobile/v2/address/food/482")
    @Test fun v02() = assertNormalizes(
        "/coverage-area/api/kelurahan-by-district/{id}", "/coverage-area/api/kelurahan-by-district/3173")
    @Test fun v03() = assertNormalizes(
        "/home/xpress/api/mobile/livestream/get-realtime-online/{id}",
        "/home/xpress/api/mobile/livestream/get-realtime-online/77")
    @Test fun v04() = assertNormalizes(
        "/home/xpress/api/mobile/livestream/get-product-count/{id}",
        "/home/xpress/api/mobile/livestream/get-product-count/9123")
    @Test fun v05() = assertNormalizes(
        "/home/view/general/api/mobile/livestream/get-detail-livestream/{id}",
        "/home/view/general/api/mobile/livestream/get-detail-livestream/5541")
    @Test fun v06() = assertNormalizes(
        "/home/xpress/api/mobile/product-page/{id}", "/home/xpress/api/mobile/product-page/PRC-XP-0042")
    @Test fun v07() = assertNormalizes(
        "/order-xpress-view/api/mobile/orders/{id}", "/order-xpress-view/api/mobile/orders/8821")
    @Test fun v08() = assertNormalizes(
        "/catalog-food-flagship/api/mobile/stores/slot/{id}",
        "/catalog-food-flagship/api/mobile/stores/slot/STR-JKT-01")
    @Test fun v09() = assertNormalizes(
        "/home/food/api/mobile/product-page/{id}", "/home/food/api/mobile/product-page/FD-0099")
    @Test fun v10() = assertNormalizes(
        "/order-xpress-view/api/mobile/orders/payment-info/{id}",
        "/order-xpress-view/api/mobile/orders/payment-info/TRX20250914XP01")
    @Test fun v11() = assertNormalizes(
        "/order-xpress-view/api/mobile/orders/{id}", "/order-xpress-view/api/mobile/orders/88212345")
    @Test fun v12() = assertNormalizes(
        "/customer/api/mobile/orders/{id}",
        "/customer/api/mobile/orders/3F2B1C8A-5E6F-4A7B-8C9D-0E1F2A3B4C5D")

    @Test fun v13() = assertUnchanged("/complain/api/mobile/food/category-by-order-status")
    @Test fun v14() = assertUnchanged("/home/xpress/api/mobile/livestream/get-product-count")
    @Test fun v15() = assertUnchanged("/customer/api/mobile/v2/address/food")
    @Test fun v16() = assertUnchanged("/api/v2.1/status")
    @Test fun v17() = assertUnchanged("/api/V3/status")
    @Test fun v18() = assertUnchanged("/share/link/aaaabbbbccccddddeeeeffff")
    @Test fun v19a() = assertUnchanged("/share/link/aAaAbBbBcCcCdDdDeEeEfFf")
    @Test fun v19b() = assertNormalizes("/share/link/{id}", "/share/link/aAaAbBbBcCcCdDdDeEeEfFfF")
    @Test fun v20() = assertUnchanged("/routes/get-customer-order-history-detail-report")
    @Test fun v21() = assertNormalizes("/share/link/{id}", "/share/link/shareTokenAbcDefGhiJkl-mnoPqr")
    @Test fun v22() = assertNormalizes("/share/link/{id}", "/share/link/shareTokenAbcDefGhiJkl_mnoPqr")

    /** The path half of vector 23; the `?token=…` half is SEC-03's job and never reaches here. */
    @Test fun v23() = assertUnchanged("/customer/api/mobile/profile")

    // ─── edges the table does not list, each pinned to one stated rule ───────

    @Test fun `rule 2 runs before rule 3 so a bare version segment keeps its digit`() {
        assertUnchanged("/v1")
        assertUnchanged("/v10.20.30/ping")
    }

    @Test fun `rule 2 is exact — a version-looking segment with a suffix has a digit and is an id`() {
        assertNormalizes("/api/{id}/status", "/api/v2beta/status")
    }

    @Test fun `rule 1 is what catches a UUID with no digit — rule 4 would call it a kebab word`() {
        assertNormalizes("/orders/{id}", "/orders/abcdefab-abcd-abcd-abcd-abcdefabcdef")
    }

    @Test fun `empty segments and the root path survive`() {
        assertUnchanged("/")
        assertUnchanged("")
        assertNormalizes("/a//{id}/", "/a//7/")
    }
}
