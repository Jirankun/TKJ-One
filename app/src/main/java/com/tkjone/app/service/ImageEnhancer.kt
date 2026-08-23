package com.tkjone.app.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Gentle image enhancement — subtle saturation + contrast boost.
 * NO aggressive sharpening (causes noise/grain).
 * Goal: make photos look slightly more vivid and clean, not dramatically different.
 */
object ImageEnhancer {

    /**
     * Enhance bitmap: gentle saturation boost + very mild contrast.
     * Returns enhanced bitmap (caller recycles original if needed).
     */
    fun enhance(context: Context, source: Bitmap): Bitmap {
        return adjustColors(source, saturation = 1.08f, contrast = 1.04f, brightness = 0.01f)
    }

    /**
     * Gentle color adjustment using ColorMatrix.
     * - saturation: 1.0 = normal, 1.08 = slightly more vivid (skin tones stay natural)
     * - contrast: 1.0 = normal, 1.04 = barely perceptible lift
     * - brightness: 0.0 = normal, 0.01 = very subtle lift
     */
    private fun adjustColors(source: Bitmap, saturation: Float, contrast: Float, brightness: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Saturation matrix
        val sat = floatArrayOf(
            0.213f + 0.787f * saturation, 0.715f - 0.715f * saturation, 0.072f - 0.072f * saturation, 0f, 0f,
            0.213f - 0.213f * saturation, 0.715f + 0.285f * saturation, 0.072f - 0.072f * saturation, 0f, 0f,
            0.213f - 0.213f * saturation, 0.715f - 0.715f * saturation, 0.072f + 0.928f * saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        // Contrast matrix (centered at 0.5)
        val c = contrast
        val t = brightness * 255f
        val con = floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )

        // Combine: first saturation, then contrast
        val combined = ColorMatrix(sat)
        combined.postConcat(ColorMatrix(con))

        paint.colorFilter = ColorMatrixColorFilter(combined)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Save bitmap to DCIM/TKJ Photos.
     * Uses MediaStore on API 29+, direct file write on older.
     */
    fun saveToDcim(context: Context, bitmap: Bitmap, fileName: String): String? {
        val saveName = "${fileName}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, saveName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/TKJ Photos")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TKJ Photos")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, saveName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            file.absolutePath
        }
    }

    /**
     * Decode bitmap from file.
     */
    fun decodeForEnhance(filePath: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(filePath)
        } catch (_: OutOfMemoryError) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(filePath, opts)
        }
    }
}
