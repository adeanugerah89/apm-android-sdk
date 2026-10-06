package edts.apm.android.sdk.network

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A local HTTPS server using the throwaway fixture certificate `test/resources/tls/localhost-test.p12`
 * (a key that protects nothing), plus clients that do or do not trust it. Lets tests drive a **real**
 * TLS handshake through OkHttp instead of hand-building exceptions or attributes.
 */
internal class LocalTlsServer : AutoCloseable {

    val serverCert: X509Certificate
    val server: MockWebServer
    private val trustManager: X509TrustManager

    init {
        val store = KeyStore.getInstance("PKCS12").apply {
            checkNotNull(LocalTlsServer::class.java.classLoader!!.getResourceAsStream("tls/localhost-test.p12")) {
                "missing tls/localhost-test.p12 test fixture"
            }.use { load(it, PASSWORD) }
        }
        serverCert = store.getCertificate("apm-test") as X509Certificate

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, PASSWORD) }.keyManagers
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

        // A trust store holding only this certificate, so a handshake succeeds and the only thing that
        // can reject a connection is whatever the test adds (a pinner, for example).
        val trusted = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("t", serverCert) }
        trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trusted) }.trustManagers.filterIsInstance<X509TrustManager>().single()

        server = MockWebServer().apply {
            useHttps(serverContext.socketFactory, false)
            start()
        }
    }

    fun url(path: String = "/v1/thing"): HttpUrl = server.url(path).newBuilder().host("localhost").build()

    /** A client that trusts the fixture certificate. */
    fun trustingClient(): OkHttpClient.Builder = OkHttpClient.Builder().sslSocketFactory(
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory,
        trustManager
    )

    override fun close() = server.shutdown()

    private companion object {
        val PASSWORD = "changeit".toCharArray()
    }
}
