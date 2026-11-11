package com.worddrill.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.worddrill.app.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 里程碑分享图：用原生 Canvas 画一张 1080×1080 的打卡图，存到缓存目录再走 FileProvider 分享。
 * 不引任何图片库，也不需要读相册权限。
 */
object ShareCard {

    private const val SIZE = 1080

    /** 画一张打卡图：[title] 是大字，[subtitle] 是下面一行小字。 */
    fun render(context: Context, title: String, subtitle: String, lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.brand))

        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.surface)
        }
        val margin = 96f
        val rect = RectF(margin, margin, SIZE - margin, SIZE - margin)
        canvas.drawRoundRect(rect, 48f, 48f, card)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_primary)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_secondary)
        }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.brand)
        }

        ink.textSize = 96f
        ink.isFakeBoldText = true
        canvas.drawText(title, margin + 64f, margin + 200f, ink)

        sub.textSize = 40f
        canvas.drawText(subtitle, margin + 66f, margin + 268f, sub)

        var y = margin + 400f
        lines.forEach { line ->
            val paint = if (line.startsWith("#")) brand else sub
            paint.textSize = 42f
            paint.isFakeBoldText = line.startsWith("#")
            canvas.drawText(line.removePrefix("#"), margin + 66f, y, paint)
            y += 86f
        }

        sub.textSize = 32f
        sub.isFakeBoldText = false
        canvas.drawText("WordDrill · 每天一点", margin + 66f, SIZE - margin - 64f, sub)

        return bitmap
    }

    /** 先落到缓存目录，再用 FileProvider 交给系统分享面板。 */
    fun share(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "worddrill-$stamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享打卡图"))
    }
}