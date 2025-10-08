package com.example.bajifbo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

/**
 * OpenGL 刮刮卡渲染器（修正版，非持久模式不会闪烁）
 */
class ScratchRenderer(val ctx: Context) : GLSurfaceView.Renderer {

    private val VERTEX_SHADER_SIMPLE = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    private val FRAGMENT_SHADER_COMPOSITE = """
precision mediump float;
uniform sampler2D uBg;
uniform sampler2D uFg;
uniform sampler2D uMask;
varying vec2 vTexCoord;
void main() {
    vec4 bg = texture2D(uBg, vTexCoord);
    vec4 fg = texture2D(uFg, vTexCoord);
    float m = texture2D(uMask, vTexCoord).a;
    vec3 color = mix(fg.rgb, bg.rgb, m);
    gl_FragColor = vec4(color, 1.0);
}
"""

    private val FRAGMENT_SHADER_STAMP = """
precision mediump float;
uniform sampler2D uTex;
uniform float uAlpha;
varying vec2 vTexCoord;
void main() {
    vec4 b = texture2D(uTex, vTexCoord);
    gl_FragColor = vec4(0.0, 0.0, 0.0, b.a * uAlpha);
}
"""

    private var texBg = 0
    private var texFg = 0
    private var texBrush = 0

    private var maskFbo = IntArray(1)
    private var maskTexture = IntArray(1)

    // 临时 FBO & 纹理用于非持久模式
    private var tempMaskFbo = IntArray(1)
    private var tempMaskTexture = IntArray(1)

    private var viewW = 1
    private var viewH = 1

    private var quadProgram = 0
    private var stampProgram = 0

    @Volatile
    var preserveErase = true

    private var maskWidth = 330f
    private var fgWidth = 1125f


    data class Stamp(val x: Float, val y: Float, val size: Float, val alpha: Float)

    private val pendingStamps = mutableListOf<Stamp>()

    private var lastX = 0f
    private var lastY = 0f
    private val stampSpacing = 16f

    @Volatile
    private var isTouching = false

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        texBg = loadTextureFromAsset(ctx, "bg.webp")
        texFg = loadTextureFromAsset(ctx, "fg.webp")
        texBrush = loadTextureFromAsset(ctx, "brush.png")

        quadProgram = createProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_COMPOSITE)
        stampProgram = createProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_STAMP)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)

        createMaskFbo(width, height)
        createTempMaskFbo(width, height)


        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(pendingStamps) {
                if (preserveErase) {
                    // 持久模式：累积擦除，绘制到 maskFbo
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0])
                    GLES20.glViewport(0, 0, viewW, viewH)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                    for (s in pendingStamps) drawStampToMask(s)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    pendingStamps.clear()
                } else if (!preserveErase) {
                    if (isTouching) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tempMaskFbo[0])
                        GLES20.glViewport(0, 0, viewW, viewH)
                        // ✅ 每帧清空，确保只显示当前触摸点
                        GLES20.glClearColor(0f, 0f, 0f, 0f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                        // 只绘制最后一个刮擦点
                        pendingStamps.lastOrNull()?.let { drawStampToMask(it) }
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    }else{
                        // 手指抬起：重置 tempMask，使其为“未擦除状态”
                        resetMask()
                    }
                }

            }

        // 绘制合成结果
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(quadProgram)

        val aPos = GLES20.glGetAttribLocation(quadProgram, "aPosition")
        val aTex = GLES20.glGetAttribLocation(quadProgram, "aTexCoord")
        val uBg = GLES20.glGetUniformLocation(quadProgram, "uBg")
        val uFg = GLES20.glGetUniformLocation(quadProgram, "uFg")
        val uMask = GLES20.glGetUniformLocation(quadProgram, "uMask")

        val quadVerts = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )

        val vb = makeFloatBuffer(quadVerts)
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
        vb.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vb)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBg)
        GLES20.glUniform1i(uBg, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texFg)
        GLES20.glUniform1i(uFg, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            if (preserveErase) {
                maskTexture[0]
            } else {
                tempMaskTexture[0]
            }
        )
        GLES20.glUniform1i(uMask, 2)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }


    private fun drawStampToMask(s: Stamp) {
        GLES20.glUseProgram(stampProgram)
        val aPos = GLES20.glGetAttribLocation(stampProgram, "aPosition")
        val aTex = GLES20.glGetAttribLocation(stampProgram, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(stampProgram, "uTex")
        val uAlpha = GLES20.glGetUniformLocation(stampProgram, "uAlpha")

        val cx = (s.x / viewW.toFloat()) * 2f - 1f
        val cy = ((s.y / viewH.toFloat()) * 2f - 1f) * -1f
        val halfW = s.size / viewW.toFloat()
        val halfH = s.size / viewH.toFloat()

        val left = cx - halfW
        val right = cx + halfW
        val bottom = cy - halfH
        val top = cy + halfH

        val verts = floatArrayOf(
            left, bottom, 0f, 0f,
            right, bottom, 1f, 0f,
            left, top, 0f, 1f,
            right, top, 1f, 1f
        )

        val vb = makeFloatBuffer(verts)
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
        vb.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vb)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBrush)
        GLES20.glUniform1i(uTex, 0)
        if (!isTouching && !preserveErase) {
            GLES20.glUniform1f(uAlpha, 0f)
        } else {
            GLES20.glUniform1f(uAlpha, s.alpha)
        }


        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    fun changePreserveErase(isPreserveErase: Boolean) {
        resetMask()
        preserveErase = isPreserveErase
    }

    private fun createMaskFbo(w: Int, h: Int) {
        if (maskTexture[0] != 0) GLES20.glDeleteTextures(1, maskTexture, 0)
        if (maskFbo[0] != 0) GLES20.glDeleteFramebuffers(1, maskFbo, 0)

        GLES20.glGenTextures(1, maskTexture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glGenFramebuffers(1, maskFbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, maskTexture[0], 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw RuntimeException("Framebuffer not complete")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createTempMaskFbo(w: Int, h: Int) {
        if (tempMaskTexture[0] != 0) GLES20.glDeleteTextures(1, tempMaskTexture, 0)
        if (tempMaskFbo[0] != 0) GLES20.glDeleteFramebuffers(1, tempMaskFbo, 0)

        GLES20.glGenTextures(1, tempMaskTexture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tempMaskTexture[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glGenFramebuffers(1, tempMaskFbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tempMaskFbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tempMaskTexture[0], 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw RuntimeException("Temp framebuffer not complete")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun resetMask() {
        synchronized(pendingStamps) { pendingStamps.clear() }

        // 重置持久化 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0])
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 重置临时 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tempMaskFbo[0])
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun makeFloatBuffer(arr: FloatArray) = java.nio.ByteBuffer.allocateDirect(arr.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(arr); position(0) }

    fun touchDown(x: Float, y: Float) {
        isTouching = true
        lastX = x
        lastY = y
        stampAt(x, y)
    }

    fun touchMove(x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (preserveErase) {
            // 持久模式：添加多个中间点形成连续路径
            if (dist >= stampSpacing) {
                val steps = (dist / stampSpacing).toInt()
                for (i in 1..steps) {
                    val t = i / steps.toFloat()
                    stampAt(lastX + dx * t, lastY + dy * t)
                }
                lastX = x
                lastY = y
            }
        } else {
            // 非持久模式：只添加最后一个点
            stampAt(x, y)
            lastX = x
            lastY = y
        }
    }

    fun touchUp(x: Float, y: Float) {
        Log.d("onDrawFrame", "touchUp: ---->抬起状态")
        // 非持久模式：手指抬起时清空临时 FBO
        isTouching = false
        if (!preserveErase) {
            resetMask()
        } else {
            stampAt(x, y)
        }
    }


    private fun stampAt(x: Float, y: Float) {

        synchronized(pendingStamps) {
            if (preserveErase){
                val size = maskWidth * (viewW / fgWidth)
                pendingStamps.add(Stamp(x, y, size, 1f))
            }else{
                val size = maskWidth * (viewW / fgWidth) * 1.5f
                pendingStamps.add(Stamp(x, y - maskWidth / 2, size, 1f))
            }

        }
    }

    private fun loadTextureFromAsset(ctx: Context, name: String): Int {
        val input = ctx.assets.open(name)
        var bmp = BitmapFactory.decodeStream(input)
        input.close()

        val matrix = android.graphics.Matrix()
        matrix.preScale(1f, -1f)
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, false)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return tex[0]
    }

    private fun createProgram(vsSource: String, fsSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException(
            "Program link error: ${
                GLES20.glGetProgramInfoLog(
                    program
                )
            }"
        )
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) throw RuntimeException(
            "Shader compile error: ${
                GLES20.glGetShaderInfoLog(
                    shader
                )
            }"
        )
        return shader
    }
}
