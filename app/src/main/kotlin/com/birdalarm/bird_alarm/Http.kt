package com.birdalarm.bird_alarm

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 非 200 的应答。 */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** 几个联网请求用的最小封装（HttpURLConnection）。都是阻塞调用，放 Dispatchers.IO 里跑。 */
object Http {
    // Wikimedia 要求带可识别的 UA，默认的 Dalvik UA 会被限流
    private const val USER_AGENT = "BirdAlarm/${BuildConfig.VERSION_NAME} (Android; github.com/ErikaAlk/bird_alarm)"

    private fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
        }

    fun get(url: String, timeoutMs: Int = 12_000): String {
        val conn = open(url, timeoutMs)
        try {
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode)
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** POST 一段 JSON，返回状态码。 */
    fun postJson(url: String, body: String, timeoutMs: Int = 10_000): Int {
        val conn = open(url, timeoutMs).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** 边下边写进 [target]，[onProgress] 收到已下字节和总字节（总长未知时为 -1）。 */
    fun download(url: String, target: File, timeoutMs: Int = 25_000, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val conn = open(url, timeoutMs)
        try {
            if (conn.responseCode != 200) throw HttpStatusException(conn.responseCode)
            val total = conn.contentLengthLong
            target.parentFile?.mkdirs()
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (done == 0L) throw IllegalStateException("下载内容为空")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 给人看的失败原因（DESIGN §14：说发生了什么、怎么补救）。网络层异常的 message 是系统的英文原话，换成一句中文。
 */
fun Throwable.userMessage(): String = when (this) {
    is HttpStatusException -> "服务器返回 $code"
    is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
    is java.net.UnknownHostException -> "无网络连接，请检查网络设置"
    is javax.net.ssl.SSLException -> "安全连接失败，请稍后重试"
    is java.io.IOException -> "无法连接服务器，请检查网络"
    else -> message ?: "出错了，请稍后重试"
}
