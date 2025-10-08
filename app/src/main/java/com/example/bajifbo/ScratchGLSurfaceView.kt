package com.example.bajifbo

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
import android.view.MotionEvent

/**
 * @author dada
 * @date 2025/10/8
 * @desc
 */
class ScratchGLSurfaceView(context: Context) : GLSurfaceView(context) {
    val renderer: ScratchRenderer


    init {
        setEGLContextClientVersion(2)
        renderer = ScratchRenderer(context)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY


// Forward touch events to renderer
        setOnTouchListener { _, ev ->
            val x = ev.x
            val y = ev.y
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    renderer.touchDown(x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    renderer.touchMove(x, y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    renderer.touchUp(x, y)
                }
            }
            true
        }
    }
}