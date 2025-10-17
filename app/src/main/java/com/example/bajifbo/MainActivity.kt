package com.example.bajifbo

import android.R.attr.duration
import android.R.attr.end
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.ColorUtils
import com.example.bajifbo.BitmapNanGuaUtil.tintBitmap
import androidx.core.view.isInvisible
import androidx.core.view.isVisible

/**
 * @author dada
 * @date 2025/10/8
 * @desc
 */
class MainActivity : AppCompatActivity() {
//    lateinit var glView: ScratchGLSurfaceView
    private var isOpen = true
    private var ivOpen: ImageView? = null
    private var valueAnimator:ValueAnimator? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


//        val root = FrameLayout(this)
//        glView = ScratchGLSurfaceView(this)
//        root.addView(glView)
//        val preserveSwitch = Switch(this).apply {
//            text = "保持擦除"
//            isChecked = true
//            setOnCheckedChangeListener { _, isChecked ->
//                glView.renderer.changePreserveErase(isChecked)
//            }
//// small layout params
//            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
//            lp.marginEnd = 24
//            lp.topMargin = 24
//            lp.gravity = android.view.Gravity.END or android.view.Gravity.TOP
//            layoutParams = lp
//        }
//        root.addView(preserveSwitch)


        setContentView(R.layout.main_activity)
        val ivClose = findViewById<ImageView>(R.id.ivClose)
         ivOpen = findViewById<ImageView>(R.id.ivOpen)
        val ivBg = findViewById<ImageView>(R.id.ivBg)

        val bitmap = BitmapFactory.decodeResource(resources, R.mipmap.diy_pumpkin_lantern_0)
        val bitmapWhite = tintBitmap(BitmapFactory.decodeResource(resources, R.mipmap.diy_pumpkin_lantern_mask_0), Color.WHITE)
        val bitmapBlack = tintBitmap(BitmapFactory.decodeResource(resources, R.mipmap.diy_pumpkin_lantern_mask_0), ColorUtils.getColor(R.color.black_tran))


        val miaobian = BitmapNanGuaUtil.createInnerGlow(
            bitmapWhite,
            ColorUtils.getColor(R.color.white),
            150f,BlurMaskFilter.Blur.INNER
        )

        val miaobian2 = BitmapNanGuaUtil.createInnerGlow(
            bitmapWhite,
            ColorUtils.getColor(R.color.color_FDFF83),
            1f,BlurMaskFilter.Blur.INNER
        )


       val bitmap3=  BitmapNanGuaUtil.blendBitmaps(miaobian,miaobian2, PorterDuff.Mode.ADD)

        val miaobian3 = BitmapNanGuaUtil.createInnerGlow(
            bitmap3,
            ColorUtils.getColor(R.color.color_FDFF83),
            25f,BlurMaskFilter.Blur.OUTER
        )

        val bitmapLight = BitmapNanGuaUtil.combineBitmaps(bitmap3,miaobian3)

        val bitmapClose = BitmapNanGuaUtil.createOuterShadow(bitmapBlack,ColorUtils.getColor(R.color.black_tran),1f,0f,5f)

        ivClose.visibility = View.INVISIBLE
        ivClose.setImageBitmap(bitmapClose)
        ivOpen?.setImageBitmap(bitmapLight)

        ivBg.setImageBitmap(bitmap)


        ivBg.setOnClickListener {
            if (valueAnimator!=null){
                return@setOnClickListener
            }
            isOpen = !isOpen
            changeLight(0f,1f, next = {
                if (isOpen){
                    ivOpen?.alpha =  it
                    ivClose?.alpha =  1 - it
                }else{
                    if (ivClose.isInvisible){
                        ivClose.visibility = View.VISIBLE
                        ivClose?.alpha =  1f
                    }
                    ivOpen?.alpha =  1 - it
                }
            }, animEnd = {
                if (isOpen){
                    ivClose.visibility = View.INVISIBLE
                }
            })
        }
    }

    private fun changeLight(start: Float,end: Float,next: (Float) -> Unit = {},animEnd: () -> Unit = {}) {
        valueAnimator=  ValueAnimator.ofFloat(start, end).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                // 动画更新逻辑
                val value = it.animatedValue as Float
                next(value)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    valueAnimator?.cancel()
                    valueAnimator = null
                    animEnd.invoke()
                    removeAllUpdateListeners()
                    removeAllListeners()
                }
            })
        }
        valueAnimator?.start()
    }


//    override fun onResume() {
//        super.onResume()
//        glView.onResume()
//    }
//
//
//    override fun onPause() {
//        super.onPause()
//        glView.onPause()
//    }
}