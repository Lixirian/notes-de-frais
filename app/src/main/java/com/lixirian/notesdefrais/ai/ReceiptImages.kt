package com.lixirian.notesdefrais.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Import et normalisation des justificatifs : copie dans le stockage interne de l'app,
 * réduction à [MAX_DIMENSION] px et correction de l'orientation EXIF.
 * Le fichier résultant sert à la fois d'archive locale et de charge utile pour le LLM.
 */
object ReceiptImages {

    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    fun receiptsDir(context: Context): File = File(context.filesDir, "receipts").apply { mkdirs() }

    /** Crée l'URI (FileProvider) où l'app Caméra écrira la photo. */
    fun newCameraTarget(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Copie et normalise l'image pointée par [source] ; renvoie le fichier JPEG final. */
    suspend fun import(context: Context, source: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // decodeStream renvoie null en mode inJustDecodeBounds : seule l'ouverture du flux est testée ici.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = resolver.openInputStream(source) ?: throw IOException("Impossible d'ouvrir l'image")
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Image illisible")

        val sample = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, sample) }
            ?: throw IOException("Impossible de décoder l'image")

        val rotation = resolver.openInputStream(source)?.use { stream ->
            runCatching { ExifInterface(stream).rotationDegrees }.getOrDefault(0)
        } ?: 0

        val oriented = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded

        val scaled = scaleDown(oriented)
        val target = File(receiptsDir(context), "${UUID.randomUUID()}.jpg")
        FileOutputStream(target).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        if (scaled !== oriented) scaled.recycle()
        oriented.recycle()
        // La capture brute (EXIF, GPS éventuel) écrite par l'app Caméra dans notre cache est effacée :
        // seule la version ré-encodée, sans métadonnées, est conservée.
        if (source.authority == "${context.packageName}.fileprovider") runCatching { resolver.delete(source, null, null) }
        target
    }

    /** Démarrage à froid : captures caméra et exports (ZIP/CSV avec justificatifs) du cache sont effacés. */
    fun purgeCaches(context: Context) {
        for (name in listOf("camera", "exports", "updates")) {
            runCatching { File(context.cacheDir, name).listFiles()?.forEach { it.delete() } }
        }
    }

    private fun computeSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        // Sur le côté le plus long : une image très allongée (65 535 × 1 000) ne doit pas être
        // décodée en pleine résolution (262 Mo) sous prétexte que sa hauteur est petite.
        while (maxOf(w, h) / 2 >= MAX_DIMENSION) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
