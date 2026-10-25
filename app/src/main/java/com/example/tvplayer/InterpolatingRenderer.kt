package com.example.tvplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rendu OpenGL expérimental avec interpolation par fondu entre images (frame blending).
 *
 * Principe :
 *  - ExoPlayer décode dans la Surface fournie par ce renderer (SurfaceTexture OES).
 *  - Chaque image décodée est copiée dans une texture « slot » avec son horodatage d'affichage.
 *  - À chaque rafraîchissement de l'écran (50/60 Hz), on affiche un mélange des deux images
 *    qui encadrent l'instant (maintenant - 1 intervalle d'image) : la lecture est donc
 *    retardée d'environ une image (≈ 40 ms pour du 24 fps).
 *
 * Ce n'est PAS une interpolation compensée en mouvement : les déplacements rapides
 * peuvent produire un léger effet de fantôme.
 */
class InterpolatingRenderer(
    private val onSurfaceReady: (Surface?) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    // ---- Paramètres fournis par l'activité (thread principal) ----
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    /** Durée d'un rafraîchissement de l'écran cible (50 Hz = 20 ms, 60 Hz = 16,7 ms). */
    @Volatile var displayIntervalNs = 16_666_667L

    @Volatile private var sizeW = 1f
    @Volatile private var sizeH = 1f
    @Volatile private var rotCos = 1f
    @Volatile private var rotSin = 0f

    /** Taille d'affichage de l'image (px, dans son repère avant rotation) et rotation horaire. */
    fun setTransform(w: Float, h: Float, rotationDeg: Float) {
        sizeW = w
        sizeH = h
        val rad = Math.toRadians(rotationDeg.toDouble())
        rotCos = cos(rad).toFloat()
        rotSin = sin(rad).toFloat()
    }

    // ---- État GL (thread GL uniquement) ----
    private class Slot(val tex: Int, val fbo: Int) { var ts = 0L }

    private val frameAvailable = AtomicBoolean(false)
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var copyProgram = 0
    private var blendProgram = 0

    private val slots = ArrayList<Slot>()
    private val free = ArrayDeque<Slot>()
    private val queue = ArrayDeque<Slot>()
    private var fboW = 0
    private var fboH = 0

    private var screenW = 1
    private var screenH = 1
    private val texMatrix = FloatArray(16)
    private var intervalNs = 41_666_667L // intervalle moyen entre images (estimé)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }

    // ------------------------------------------------------------ Cycle GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseGl()

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        copyProgram = link(COPY_VS, COPY_FS)
        blendProgram = link(BLEND_VS, BLEND_FS)

        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        surface = Surface(st)
        frameAvailable.set(false)

        onSurfaceReady(surface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = max(1, width)
        screenH = max(1, height)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable.set(true)
    }

    /** À appeler via GLSurfaceView.queueEvent avant onPause(). */
    fun release() {
        onSurfaceReady(null)
        releaseGl()
    }

    private fun releaseGl() {
        destroyTargets()
        surface?.release(); surface = null
        surfaceTexture?.release(); surfaceTexture = null
        if (oesTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0); oesTex = 0 }
        if (copyProgram != 0) { GLES20.glDeleteProgram(copyProgram); copyProgram = 0 }
        if (blendProgram != 0) { GLES20.glDeleteProgram(blendProgram); blendProgram = 0 }
    }

    // ------------------------------------------------------------- Dessin

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        val now = System.nanoTime()

        ensureTargets()

        if (frameAvailable.getAndSet(false)) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            var ts = st.timestamp
            if (ts == 0L || abs(ts - now) > 1_000_000_000L) ts = now
            if (slots.isNotEmpty()) pushFrame(ts)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (queue.isEmpty()) return

        // Si la vidéo est déjà aussi fluide que l'écran, on n'interpole pas (pas de fantômes).
        val bypass = intervalNs < displayIntervalNs * 13 / 10
        val target = if (bypass) now else now - intervalNs

        while (queue.size >= 2 && queue[1].ts <= target) {
            free.addLast(queue.removeFirst())
        }
        val a = queue[0]
        val b = if (!bypass && queue.size >= 2) queue[1] else a
        var alpha = 0f
        if (b !== a) {
            val span = (b.ts - a.ts).toFloat()
            if (span > 0f) alpha = ((target - a.ts).toFloat() / span).coerceIn(0f, 1f)
        }

        GLES20.glUseProgram(blendProgram)
        val pos = GLES20.glGetAttribLocation(blendProgram, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blendProgram, "uSize"), sizeW, sizeH)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blendProgram, "uRot"), rotCos, rotSin)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blendProgram, "uScreen"), screenW.toFloat(), screenH.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uAlpha"), alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a.tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uA"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uB"), 1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Copie l'image décodée (OES) dans un slot et l'ajoute à la file. */
    private fun pushFrame(ts: Long) {
        val last = queue.lastOrNull()
        if (last != null) {
            val delta = ts - last.ts
            if (delta <= 0L || delta > 400_000_000L) {
                // Saut (seek, reprise après pause) : on repart de zéro
                while (queue.isNotEmpty()) free.addLast(queue.removeFirst())
            } else if (delta in 5_000_000L..250_000_000L) {
                intervalNs = (intervalNs * 7 + delta) / 8 // moyenne glissante
            }
        }

        val slot = free.removeFirstOrNull() ?: queue.removeFirst()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.fbo)
        GLES20.glViewport(0, 0, fboW, fboH)
        GLES20.glUseProgram(copyProgram)
        val pos = GLES20.glGetAttribLocation(copyProgram, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        slot.ts = ts
        queue.addLast(slot)
    }

    // ---------------------------------------------------- Textures de travail

    /** Crée (ou recrée) les textures de travail quand la taille de la vidéo est connue ou change. */
    private fun ensureTargets() {
        val vw = videoWidth
        val vh = videoHeight
        if (vw <= 0 || vh <= 0) return
        val scale = min(1f, min(MAX_WIDTH / vw.toFloat(), MAX_HEIGHT / vh.toFloat()))
        val w = max(2, (vw * scale).roundToInt())
        val h = max(2, (vh * scale).roundToInt())
        if (w == fboW && h == fboH && slots.isNotEmpty()) return

        destroyTargets()
        repeat(POOL_SIZE) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)

            val slot = Slot(tex[0], fb[0])
            slots += slot
            free.addLast(slot)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboW = w
        fboH = h
    }

    private fun destroyTargets() {
        for (s in slots) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(s.fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(s.tex), 0)
        }
        slots.clear()
        free.clear()
        queue.clear()
        fboW = 0
        fboH = 0
    }

    // ---------------------------------------------------------- Shaders

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Erreur de shader : $log")
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private companion object {
        const val POOL_SIZE = 4
        // Résolution maximale des textures de travail : au-delà (4K), l'image est réduite
        // pour que la TV tienne la charge. Augmentez si votre appareil est assez puissant.
        const val MAX_WIDTH = 1920f
        const val MAX_HEIGHT = 1080f

        const val COPY_VS = """
attribute vec2 aPos;
uniform mat4 uTexMatrix;
varying vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
}
"""
        const val COPY_FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES uTex;
void main() {
    gl_FragColor = texture2D(uTex, vTex);
}
"""
        const val BLEND_VS = """
attribute vec2 aPos;
uniform vec2 uSize;
uniform vec2 uRot;
uniform vec2 uScreen;
varying vec2 vTex;
void main() {
    vec2 p = aPos * uSize * 0.5;
    vec2 r = vec2(p.x * uRot.x + p.y * uRot.y, -p.x * uRot.y + p.y * uRot.x);
    gl_Position = vec4(r / (uScreen * 0.5), 0.0, 1.0);
    vTex = aPos * 0.5 + 0.5;
}
"""
        const val BLEND_FS = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uAlpha;
void main() {
    gl_FragColor = mix(texture2D(uA, vTex), texture2D(uB, vTex), uAlpha);
}
"""
    }
}
