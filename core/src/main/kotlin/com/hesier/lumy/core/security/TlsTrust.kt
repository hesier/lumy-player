package com.hesier.lumy.core.security

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Adds known CA roots to a local trust store; certificate path checks remain with the TLS provider. */
object TlsTrust {
    fun system(): X509TrustManager = fromStore(null)

    fun withAdditionalRoots(system: X509TrustManager, roots: Collection<X509Certificate>): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        system.acceptedIssuers.forEachIndexed { index, cert -> store.setCertificateEntry("system-$index", cert) }
        roots.forEachIndexed { index, cert ->
            require(cert.basicConstraints >= 0) { "Additional trust anchors must be CA certificates" }
            store.setCertificateEntry("additional-$index", cert)
        }
        return fromStore(store)
    }

    fun socketFactory(trust: X509TrustManager): SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trust), null)
    }.socketFactory

    private fun fromStore(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
}
