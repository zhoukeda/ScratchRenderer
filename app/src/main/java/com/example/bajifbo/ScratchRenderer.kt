package com.example.bajifbo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

/**
 * @author dada
 * @date 2025/10/8
 * @desc
 */
class ScratchRenderer(val ctx: Context) : GLSurfaceView.Renderer {

    private val VERTEX_SHADER_SIMPLE = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
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
// mask stored in ALPHA channel texture
float m = texture2D(uMask, vTexCoord).a;
// m==1 -> show bg fully; m==0 -> show fg
vec3 color = mix(fg.rgb, bg.rgb, m);
// keep fg alpha as 1
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
// Use brush alpha * uAlpha as the output alpha
gl_FragColor = vec4(0.0, 0.0, 0.0, b.a * uAlpha);
}
"""

    // textures
    var texBg = 0
    var texFg = 0
    var texBrush = 0


    // mask FBO
    var maskFbo = IntArray(1)
    var maskTexture = IntArray(1)


    // screen size
    var viewW = 1
    var viewH = 1


    // program handles
    var quadProgram = 0
    var stampProgram = 0


    // preserve flag
    @Volatile
    var preserveErase = true


    // touch stamping
    @Volatile
    private var pendingStamps = mutableListOf<Stamp>()


    data class Stamp(val x: Float, val y: Float, val size: Float, val alpha: Float)

    // temporary path tracking
    private var lastX = 0f
    private var lastY = 0f
    private val stampSpacing = 16f // pixels between stamps

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)


// load textures
        texBg = loadTextureFromAsset(ctx, "bg.webp")
        texFg = loadTextureFromAsset(ctx, "fg.webp")
        texBrush = loadTextureFromAsset(ctx, "brush.png")


// programs
        quadProgram = createProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_COMPOSITE)
        stampProgram = createProgram(VERTEX_SHADER_SIMPLE, FRAGMENT_SHADER_STAMP)
    }


    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)


// recreate mask FBO texture sized to the view
        if (maskTexture[0] != 0) {
// delete old
            GLES20.glDeleteTextures(1, maskTexture, 0)
            GLES20.glDeleteFramebuffers(1, maskFbo, 0)
            maskTexture[0] = 0
            maskFbo[0] = 0
        }
        createMaskFbo(width, height)


// clear mask if not preserving
        clearMask(0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // If preserve is false, clear mask (so only live strokes will display)
        if (!preserveErase) {
            clearMask(0f)
        }


// render pending stamps into mask FBO
        synchronized(pendingStamps) {
            if (pendingStamps.isNotEmpty()) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0])
// draw stamps onto mask using stampProgram
                GLES20.glViewport(0, 0, viewW, viewH)


                GLES20.glEnable(GLES20.GL_BLEND)
// we want brush alpha to accumulate in mask (source alpha over dest)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)


                for (s in pendingStamps) {
                    drawStampToMask(s)
                }


                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                pendingStamps.clear()
            }
        }

// Now composite final result to screen with quadProgram
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(quadProgram)


// attributes & uniforms
        val aPos = GLES20.glGetAttribLocation(quadProgram, "aPosition")
        val aTex = GLES20.glGetAttribLocation(quadProgram, "aTexCoord")
        val uBg = GLES20.glGetUniformLocation(quadProgram, "uBg")
        val uFg = GLES20.glGetUniformLocation(quadProgram, "uFg")
        val uMask = GLES20.glGetUniformLocation(quadProgram, "uMask")


// full-screen quad verts
        val quadVerts = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )


        val vb = makeFloatBuffer(quadVerts)
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 4 * 4, vb)
        vb.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 4 * 4, vb)


// bind textures to units
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBg)
        GLES20.glUniform1i(uBg, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texFg)
        GLES20.glUniform1i(uFg, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture[0])
        GLES20.glUniform1i(uMask, 2)


        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)


        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)


    }

    fun makeFloatBuffer(arr: FloatArray): java.nio.FloatBuffer {
        val bb = java.nio.ByteBuffer.allocateDirect(arr.size * 4)
        bb.order(java.nio.ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(arr)
        fb.position(0)
        return fb
    }

    // touch helpers - convert view coords to clip space when stamping
    fun touchDown(x: Float, y: Float) {
        lastX = x
        lastY = y
        stampAt(x, y)
    }

    fun touchMove(x: Float, y: Float) {
// stamp along line from last to current
        var dx = x - lastX
        var dy = y - lastY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist >= stampSpacing) {
            val steps = (dist / stampSpacing).toInt()
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val sx = lastX + dx * t
                val sy = lastY + dy * t
                stampAt(sx, sy)
            }
            lastX = x
            lastY = y
        }
    }

    fun touchUp(x: Float, y: Float) {
// final small stamp
        stampAt(x, y)
    }

    private fun stampAt(x: Float, y: Float) {
// map to view coords (y inverted for GL texture coords later)
        val size =
            330f * (viewW.toFloat() / 1125f) // scale brush by screen width ratio to original asset
        synchronized(pendingStamps) {
            pendingStamps.add(Stamp(x, y, size, 1.0f))
        }
    }

    private fun drawStampToMask(s: Stamp) {
        GLES20.glUseProgram(stampProgram)
        val aPos = GLES20.glGetAttribLocation(stampProgram, "aPosition")
        val aTex = GLES20.glGetAttribLocation(stampProgram, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(stampProgram, "uTex")
        val uTransform = GLES20.glGetUniformLocation(stampProgram, "uTransform")
        val uAlpha = GLES20.glGetUniformLocation(stampProgram, "uAlpha")


// compute quad in NDC for the stamp's position/size
// convert pixel coords to NDC (-1..1), careful with y flip
        val cx = (s.x / viewW.toFloat()) * 2f - 1f
        val cy = -((s.y / viewH.toFloat()) * 2f - 1f)
        val halfW = (s.size / viewW.toFloat())
        val halfH = (s.size / viewH.toFloat())


        val left = cx - halfW
        val right = cx + halfW
        val bottom = cy - halfH
        val top = cy + halfH


        val verts = floatArrayOf(
            left, bottom, 0f, 1f,
            right, bottom, 1f, 1f,
            left, top, 0f, 0f,
            right, top, 1f, 0f
        )


        val vb = makeFloatBuffer(verts)
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 4 * 4, vb)
        vb.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 4 * 4, vb)


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBrush)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uAlpha, s.alpha)


        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)


        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    // utilities: create FBO + texture to store mask
    private fun createMaskFbo(w: Int, h: Int) {
        // 如果已有先删除（你原来在 onSurfaceChanged 已做，但为保险可再删）
        if (maskTexture[0] != 0) {
            GLES20.glDeleteTextures(1, maskTexture, 0)
            maskTexture[0] = 0
        }
        if (maskFbo[0] != 0) {
            GLES20.glDeleteFramebuffers(1, maskFbo, 0)
            maskFbo[0] = 0
        }

        GLES20.glGenTextures(1, maskTexture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture[0])

        // 使用 RGBA 而不是 GL_ALPHA（更兼容，能作为 COLOR_ATTACHMENT0）
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            w,
            h,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenFramebuffers(1, maskFbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            maskTexture[0],
            0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            // 帮助调试：把 status 打出来（或映射为字符串）
            throw RuntimeException("Framebuffer not complete: $status")
        }

        // 解绑
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }


    private fun clearMask(alpha: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFbo[0])
// clear alpha channel value to alpha (0..1)
// use glColorMask to write only alpha channel
        GLES20.glColorMask(false, false, false, true)
        GLES20.glClearColor(0f, 0f, 0f, alpha)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // GL helper: load texture from assets
    private fun loadTextureFromAsset(ctx: Context, name: String): Int {
        val ism = ctx.assets.open(name)
        var bmp = BitmapFactory.decodeStream(ism)
        ism.close()

        // ✅ 在这里翻转 bitmap（上下反转一次）
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

    // minimal shader utilities
    private fun createProgram(vsSource: String, fsSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Could not link program: $log")
        }
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader: $log")
        }
        return shader
    }

}