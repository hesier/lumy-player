package com.hesier.lumy.core.security

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

class TlsTrustTest {
    private val originalRoot = HeldCertificate.Builder().commonName("Existing system CA").certificateAuthority(1).build()
    private val extraRoot = HeldCertificate.Builder().commonName("Supplemental CA").certificateAuthority(1).build()
    private val intermediate = HeldCertificate.Builder().commonName("Intermediate CA")
        .certificateAuthority(0).signedBy(extraRoot).build()
    private val originalTrust = HandshakeCertificates.Builder().addTrustedCertificate(originalRoot.certificate).build().trustManager
    private val augmented = TlsTrust.withAdditionalRoots(originalTrust, listOf(extraRoot.certificate))

    private fun leaf(signer: HeldCertificate, hostname: String = "localhost", expired: Boolean = false): HeldCertificate {
        val builder = HeldCertificate.Builder().commonName(hostname).addSubjectAlternativeName(hostname).signedBy(signer)
        if (expired) {
            val now = System.currentTimeMillis()
            builder.validityInterval(now - TimeUnit.DAYS.toMillis(2), now - TimeUnit.DAYS.toMillis(1))
        }
        return builder.build()
    }

    private fun fetch(trust: X509TrustManager, leaf: HeldCertificate, vararg chain: HeldCertificate): String {
        val certs = HandshakeCertificates.Builder().heldCertificate(leaf, *chain.map { it.certificate }.toTypedArray()).build()
        return MockWebServer().use { server ->
            server.useHttps(certs.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val client = OkHttpClient.Builder()
                .sslSocketFactory(TlsTrust.socketFactory(trust), trust)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun `additional root enables a previously untrusted full chain`() {
        val leaf = leaf(intermediate)
        assertThrows(IOException::class.java) { fetch(originalTrust, leaf, intermediate) }
        assertEquals("ok", fetch(augmented, leaf, intermediate))
    }

    @Test fun `existing system roots are retained`() {
        assertEquals("ok", fetch(augmented, leaf(originalRoot)))
        assertTrue(augmented.acceptedIssuers.any { it == originalRoot.certificate })
        assertTrue(augmented.acceptedIssuers.any { it == extraRoot.certificate })
    }

    @Test fun `untrusted self signed certificate is still rejected`() {
        val selfSigned = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        assertThrows(IOException::class.java) { fetch(augmented, selfSigned) }
    }

    @Test fun `hostname verification is still enforced`() {
        assertThrows(IOException::class.java) { fetch(augmented, leaf(intermediate, "wrong.example"), intermediate) }
    }

    @Test fun `expired certificates and missing intermediates are still rejected`() {
        assertThrows(IOException::class.java) { fetch(augmented, leaf(intermediate, expired = true), intermediate) }
        assertThrows(IOException::class.java) { fetch(augmented, leaf(intermediate)) }
    }

    @Test fun `leaf certificate cannot be installed as a root`() {
        assertThrows(IllegalArgumentException::class.java) {
            TlsTrust.withAdditionalRoots(originalTrust, listOf(leaf(intermediate).certificate))
        }
    }
}
