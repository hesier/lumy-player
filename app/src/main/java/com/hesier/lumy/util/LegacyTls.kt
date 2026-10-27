package com.hesier.lumy.util

import android.content.Context
import android.os.Build
import com.hesier.lumy.R
import com.hesier.lumy.net.TlsTrust
import okhttp3.OkHttpClient
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Android before 7.1.1 does not normally ship ISRG Root X1. Scope the extra CA to this client. */
internal fun OkHttpClient.withLegacyTls(context: Context): OkHttpClient {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) return this
    val root = context.resources.openRawResource(R.raw.isrg_root_x1).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    val trust = TlsTrust.withAdditionalRoots(TlsTrust.system(), listOf(root))
    return newBuilder()
        .sslSocketFactory(TlsTrust.socketFactory(trust), trust)
        // Keep OkHttp's default hostname verifier and TLS negotiation.
        .build()
}
