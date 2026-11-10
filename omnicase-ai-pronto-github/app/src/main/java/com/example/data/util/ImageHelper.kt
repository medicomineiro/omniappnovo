package com.example.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object ImageHelper {

    private const val TAG = "ImageHelper"

    /**
     * 1568 px no maior eixo: teto útil documentado para visão do Claude e suficiente para
     * Gemini/GPT sem desperdiçar tokens. Acima disso os provedores reamostram de qualquer forma.
     */
    const val DEFAULT_MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 88

    /** Cache de base64 por URI (~24 MB de texto): evita recodificar a mesma imagem para cada IA da junta. */
    private val base64Cache = object : LruCache<String, Pair<String, String>>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<String, String>) = value.first.length
    }

    suspend fun copyUriToInternalStorage(context: Context, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val imagesDir = File(context.filesDir, "case_images").apply { mkdirs() }
            val mime = context.contentResolver.getType(sourceUri).orEmpty()
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val destFile = File(imagesDir, "case_img_${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext sourceUri.toString()
            Uri.fromFile(destFile).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao copiar imagem", e)
            sourceUri.toString()
        }
    }

    suspend fun getBase64FromUri(
        context: Context,
        uriString: String,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val cacheKey = "$uriString@$maxDimension"
        base64Cache.get(cacheKey)?.let { return@withContext it }

        try {
            val bytes = openBytes(context, uriString) ?: return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sampleSize = 1
            val maxOriginal = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxOriginal / (sampleSize * 2) >= maxDimension) sampleSize *= 2

            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return@withContext null

            // Corrige a rotação EXIF (fotos de celular de lâminas, lesões e telas de PACS)
            var bitmap = applyExifRotation(decoded, bytes)

            if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                val scaled = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()

            val result = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
            base64Cache.put(cacheKey, result)
            result
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Imagem grande demais", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao codificar imagem", e)
            null
        }
    }

    private fun openBytes(context: Context, uriString: String): ByteArray? {
        val stream = if (uriString.startsWith("drawable://")) {
            val resName = uriString.substringAfter("drawable://")
            @Suppress("DiscouragedApi")
            val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
            if (resId != 0) context.resources.openRawResource(resId) else null
        } else {
            context.contentResolver.openInputStream(Uri.parse(uriString))
        }
        return stream?.use { it.readBytes() }
    }

    private fun applyExifRotation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
