package com.example.bajifbo

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import androidx.annotation.ColorInt


/**
 * Bitmap 工具类
 */
object BitmapNanGuaUtil {


    fun tintBitmap(source: Bitmap, @ColorInt color: Int): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    fun createInnerGlow(
        original: Bitmap,
        @ColorInt glowColor: Int,
        blurRadius: Float,blur:BlurMaskFilter.Blur
    ): Bitmap {
        if (original.isRecycled) return original

        val width = original.width
        val height = original.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Step 1: 提取 alpha
        val alphaBitmap = original.extractAlpha()

        // Step 2: 设置 Paint
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = glowColor
        // BlurMaskFilter.Blur.INNER 会让颜色向内部渐变透明
        paint.maskFilter = BlurMaskFilter(blurRadius, blur)

        // Step 3: 绘制 alpha + Paint
        canvas.drawBitmap(alphaBitmap, 0f, 0f, paint)

        alphaBitmap.recycle()
        return out
    }


    /**
     * 将 topBitmap 按指定 PorterDuff.Mode 叠加到底图 bottomBitmap 上
     *
     * @param bottomBitmap 底图
     * @param topBitmap 顶图
     * @param mode PorterDuff.Mode 混合模式
     * @return 新生成的叠加后的 Bitmap
     */
    fun blendBitmaps(bottomBitmap: Bitmap, topBitmap: Bitmap, mode: PorterDuff.Mode): Bitmap {
        // 创建一张和底图一样大小的输出 Bitmap
        val outputBitmap = Bitmap.createBitmap(
            bottomBitmap.width,
            bottomBitmap.height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 绘制底图
        canvas.drawBitmap(bottomBitmap, 0f, 0f, paint)

        // 绘制顶图，使用 PorterDuff.Mode
        paint.xfermode = PorterDuffXfermode(mode)
        canvas.drawBitmap(topBitmap, 0f, 0f, paint)
        paint.xfermode = null

        return outputBitmap
    }

    /**
     * 将两张 Bitmap 叠加在一起，返回新的 Bitmap
     *
     * @param base 底图
     * @param overlay 叠加在底图上的图
     * @return 叠加后的新 Bitmap
     */
    fun combineBitmaps(base: Bitmap, overlay: Bitmap): Bitmap {
        // 两张图尺寸一样
        require(base.width == overlay.width && base.height == overlay.height) {
            "Bitmaps must have the same size"
        }

        if (base.width == overlay.width && base.height == overlay.height){

        }

        val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 画底图
        canvas.drawBitmap(base, 0f, 0f, null)
        // 画上层图
        canvas.drawBitmap(overlay, 0f, 0f, null)

        return result
    }

    fun createOuterShadow(
        original: Bitmap,
        @ColorInt shadowColor: Int,
        shadowRadius: Float,
        dx: Float = 0f,
        dy: Float = 0f
    ): Bitmap {
        if (original.isRecycled) return original

        val out = Bitmap.createBitmap(
            original.width + (shadowRadius * 2).toInt(),
            original.height + (shadowRadius * 2).toInt(),
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = shadowColor
        paint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.INNER)

        // 提取 alpha 通道
        val alpha = original.extractAlpha()

        // 绘制阴影
        canvas.drawBitmap(alpha, shadowRadius + dx, shadowRadius + dy, paint)
        alpha.recycle()

        // 再绘制原图
        canvas.drawBitmap(original, shadowRadius, shadowRadius, null)

        return out
    }
}