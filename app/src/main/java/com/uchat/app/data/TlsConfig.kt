package com.uchat.app.data

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 针对旧设备（Android 5 / Android 6，系统信任库缺少 ISRG Root X1 证书）HTTPS 连接失败的兼容方案：
 * 1. 在系统信任锚之外，追加内置的 ISRG Root X1 证书（assets/isrg_root_x1.pem）；
 * 2. 包装 SSLSocketFactory 确保启用 TLS 1.2（Android 5 上保险起见）。
 *
 * 说明：network_security_config 只在 Android 7.0 (API 24) 及以上生效，
 * 因此 API 21~23 必须通过代码层 TrustManager 追加信任，本类两者兼顾（API 24+ 双保险）。
 */
object TlsConfig {
    private const val TAG = "TlsConfig"

    /**
     * 创建已追加内置 ISRG Root X1 信任且启用 TLS 1.2 的 SSLSocketFactory。
     * 失败时返回 null，调用方回退到系统默认（不影响其他功能）。
     */
    fun createSocketFactory(context: Context): SSLSocketFactory? {
        val systemTm = buildSystemTrustManager() ?: return null
        val extraTm = buildExtraTrustManager(context) ?: return null
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(CombinedTrustManager(systemTm, extraTm)), SecureRandom())
            Tls12SocketFactory(sslContext.socketFactory)
        } catch (e: Exception) {
            Log.w(TAG, "SSLContext init failed", e)
            null
        }
    }

    private fun loadIsrgRoot(context: Context): X509Certificate? = try {
        context.assets.open("isrg_root_x1.pem").use { input ->
            CertificateFactory.getInstance("X.509")
                .generateCertificates(input)
                .filterIsInstance<X509Certificate>()
                .firstOrNull()
        }
    } catch (e: Exception) {
        Log.w(TAG, "load isrg root x1 failed", e)
        null
    }

    /** 用内置 ISRG Root X1 证书构建独立的 TrustManager（作为附加信任锚） */
    private fun buildExtraTrustManager(context: Context): X509TrustManager? {
        val root = loadIsrgRoot(context) ?: return null
        return try {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            keyStore.setCertificateEntry("isrg_root_x1", root)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "build extra trust manager failed", e)
            null
        }
    }

    /** 系统默认信任库 */
    private fun buildSystemTrustManager(): X509TrustManager? = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "build system trust manager failed", e)
        null
    }
}

/** 先走系统信任，失败后再用内置 ISRG Root X1 锚验证一次 */
private class CombinedTrustManager(
    private val systemTm: X509TrustManager,
    private val extraTm: X509TrustManager
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        systemTm.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            systemTm.checkServerTrusted(chain, authType)
        } catch (e: java.security.cert.CertificateException) {
            // 旧设备系统信任库可能缺少 ISRG Root X1（以及部分其他新根证书），
            // 用内置证书作为附加锚再验证一次。
            extraTm.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        systemTm.acceptedIssuers + extraTm.acceptedIssuers
}

/** 包装 SSLSocketFactory，确保启用 TLS 1.2（Android 5 默认可能未启用高版本 TLS） */
private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = enable(delegate.createSocket())

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        enable(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        enable(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        enable(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        enable(delegate.createSocket(host, port))

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        enable(delegate.createSocket(host, port, localHost, localPort))

    @Throws(IOException::class)
    private fun enable(socket: Socket): Socket {
        (socket as? SSLSocket)?.let { ssl ->
            val supported = ssl.supportedProtocols
            val enabled = supported.filter { it == "TLSv1.2" || it == "TLSv1.1" || it == "TLSv1" }
            if (enabled.isNotEmpty()) {
                try {
                    ssl.enabledProtocols = enabled.toTypedArray()
                } catch (_: Exception) {
                }
            }
        }
        return socket
    }
}
