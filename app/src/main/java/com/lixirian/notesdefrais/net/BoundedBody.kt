package com.lixirian.notesdefrais.net

import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Lecture bornée d'un corps de réponse HTTP : un serveur (relais compromis, réponse inattendue)
 * qui renverrait des centaines de Mo ne doit pas faire planter l'app par manque de mémoire.
 */
object BoundedBody {
    const val JSON_LIMIT = 10L * 1024 * 1024
    const val IMAGE_LIMIT = 20L * 1024 * 1024
    const val MANIFEST_LIMIT = 64L * 1024

    fun readBytes(response: Response, limit: Long): ByteArray {
        val declared = response.body.contentLength()
        if (declared > limit) throw IOException("réponse trop volumineuse ($declared octets)")
        val out = ByteArrayOutputStream(if (declared > 0) declared.toInt() else 8 * 1024)
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        response.body.byteStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("réponse trop volumineuse (> $limit octets)")
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    fun readText(response: Response, limit: Long = JSON_LIMIT): String = String(readBytes(response, limit), Charsets.UTF_8)
}
