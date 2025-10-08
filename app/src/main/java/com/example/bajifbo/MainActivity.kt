package com.example.bajifbo

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

/**
 * @author dada
 * @date 2025/10/8
 * @desc
 */
class MainActivity : AppCompatActivity() {
    lateinit var glView: ScratchGLSurfaceView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


// programmatic layout: FrameLayout with GLSurfaceView and a Switch on top-right
        val root = FrameLayout(this)
        glView = ScratchGLSurfaceView(this)
        root.addView(glView)


        val preserveSwitch = Switch(this).apply {
            text = "保持擦除"
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                glView.renderer.preserveErase = isChecked
            }
// small layout params
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 24
            lp.topMargin = 24
            lp.gravity = android.view.Gravity.END or android.view.Gravity.TOP
            layoutParams = lp
        }
        root.addView(preserveSwitch)


        setContentView(root)
    }


    override fun onResume() {
        super.onResume()
        glView.onResume()
    }


    override fun onPause() {
        super.onPause()
        glView.onPause()
    }
}