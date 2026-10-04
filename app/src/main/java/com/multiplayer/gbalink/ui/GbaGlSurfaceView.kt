package com.multiplayer.gbalink.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class GbaShader(val displayName: String) {
    ORIGINAL("Original (Sin filtro)"),
    LCD_3X("LCD 3x (MyBoy Grid)"),
    HQ2X("HQ2x (Suavizado)"),
    SCANLINES("Scanlines (Lineas CRT)"),
    GBA_COLOR("Corrección de Color GBA"),
    GRAYSCALE("Game Boy Clásico (DMG)"),
    CUSTOM("Shader Personalizado")
}

class GbaGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "GbaGlSurfaceView"
        const val GBA_WIDTH = 240
        const val GBA_HEIGHT = 160

        private const val VERTEX_SHADER_SRC = """
            attribute vec4 a_position;
            attribute vec2 a_texCoord;
            varying vec2 v_texCoord;
            varying vec2 TexCoord;

            void main() {
                gl_Position = a_position;
                v_texCoord = a_texCoord;
                TexCoord = a_texCoord;
            }
        """

        private const val FSH_ORIGINAL = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;

            void main() {
                gl_FragColor = texture2D(s_texture, v_texCoord);
            }
        """

        // Authentic MyBoy LCD 3x shader: RGB sub-pixel simulation and grid border
        private const val FSH_LCD_3X = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;
            uniform vec2 u_texture_size;

            void main() {
                vec4 color = texture2D(s_texture, v_texCoord);
                vec2 pos = fract(v_texCoord * u_texture_size);

                // Subpixel column mask: Red, Green, Blue
                float subpixel = fract(v_texCoord.x * u_texture_size.x * 3.0);
                vec3 mask = vec3(0.5);
                if (subpixel < 0.333) {
                    mask = vec3(1.25, 0.75, 0.75);
                } else if (subpixel < 0.666) {
                    mask = vec3(0.75, 1.25, 0.75);
                } else {
                    mask = vec3(0.75, 0.75, 1.25);
                }

                // LCD pixel boundary grid
                float grid = 1.0;
                if (pos.x < 0.08 || pos.x > 0.92 || pos.y < 0.08 || pos.y > 0.92) {
                    grid = 0.65;
                }

                gl_FragColor = vec4(clamp(color.rgb * mask * grid, 0.0, 1.0), color.a);
            }
        """

        // HQ2x anti-aliased edge smoothing
        private const val FSH_HQ2X = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;
            uniform vec2 u_texture_size;

            void main() {
                vec2 ps = 1.0 / u_texture_size;
                vec2 coord = v_texCoord;
                vec4 c = texture2D(s_texture, coord);
                vec4 cU = texture2D(s_texture, coord + vec2(0.0, -ps.y));
                vec4 cD = texture2D(s_texture, coord + vec2(0.0, ps.y));
                vec4 cL = texture2D(s_texture, coord + vec2(-ps.x, 0.0));
                vec4 cR = texture2D(s_texture, coord + vec2(ps.x, 0.0));

                vec4 blend = (cU + cD + cL + cR) * 0.125 + c * 0.5;
                gl_FragColor = mix(c, blend, 0.55);
            }
        """

        // CRT Scanline emulation
        private const val FSH_SCANLINES = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;
            uniform vec2 u_texture_size;

            void main() {
                vec4 color = texture2D(s_texture, v_texCoord);
                float line = sin(v_texCoord.y * u_texture_size.y * 3.14159265);
                float factor = 0.82 + 0.18 * line * line;
                gl_FragColor = vec4(color.rgb * factor, color.a);
            }
        """

        // GBA original LCD color & gamma correction for modern IPS / AMOLED screens
        private const val FSH_GBA_COLOR = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;

            void main() {
                vec4 color = texture2D(s_texture, v_texCoord);
                vec3 c = color.rgb;
                c = pow(c, vec3(1.15));
                mat3 m = mat3(
                    0.82, 0.09, 0.09,
                    0.09, 0.82, 0.09,
                    0.09, 0.09, 0.82
                );
                gl_FragColor = vec4(clamp(m * c, 0.0, 1.0), color.a);
            }
        """

        // Classic Game Boy DMG greenish palette
        private const val FSH_GRAYSCALE = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;

            void main() {
                vec4 color = texture2D(s_texture, v_texCoord);
                float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                vec3 dmg = mix(vec3(0.06, 0.22, 0.06), vec3(0.61, 0.73, 0.06), lum);
                gl_FragColor = vec4(dmg, color.a);
            }
        """
    }

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private val frameLock = Any()
    private val frameByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(GBA_WIDTH * GBA_HEIGHT * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private var hasNewFrame = false

    private var textureId: Int = 0
    private var currentProgramId: Int = 0
    private var activeShader: GbaShader = GbaShader.ORIGINAL
    private var customShaderSource: String? = null
    private var pendingShaderChange = true

    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    init {
        // Quad vertices: full viewport (-1 to 1)
        val vertices = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
        }

        // Texture coordinates (Y inverted for OpenGL ES)
        val texCoords = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(texCoords)
                position(0)
            }
        }

        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateFrame(bitmap: Bitmap) {
        synchronized(frameLock) {
            frameByteBuffer.position(0)
            bitmap.copyPixelsToBuffer(frameByteBuffer)
            hasNewFrame = true
        }
        requestRender()
    }

    fun setShader(shader: GbaShader) {
        activeShader = shader
        pendingShaderChange = true
        requestRender()
    }

    fun loadCustomShader(source: String): Boolean {
        customShaderSource = source
        activeShader = GbaShader.CUSTOM
        pendingShaderChange = true
        requestRender()
        return true
    }

    fun getCurrentShader(): GbaShader = activeShader

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Initialize GBA frame texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Preallocate empty 240x160 RGBA texture
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            GBA_WIDTH, GBA_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )

        compileActiveShader()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (pendingShaderChange) {
            compileActiveShader()
            pendingShaderChange = false
        }

        if (currentProgramId == 0) return

        // Upload latest GBA frame to GPU
        synchronized(frameLock) {
            if (hasNewFrame) {
                frameByteBuffer.position(0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    GBA_WIDTH, GBA_HEIGHT,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    frameByteBuffer
                )
                hasNewFrame = false
            }
        }

        GLES20.glUseProgram(currentProgramId)

        // Bind attributes
        val posLoc = GLES20.glGetAttribLocation(currentProgramId, "a_position")
        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        }

        val texLoc = GLES20.glGetAttribLocation(currentProgramId, "a_texCoord")
        if (texLoc >= 0) {
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        }

        // Bind texture unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // Set uniform samplers (supporting s_texture, Texture, and u_texture)
        setSamplerUniform("s_texture", 0)
        setSamplerUniform("Texture", 0)
        setSamplerUniform("u_texture", 0)

        // Set dimensions (supporting both standard and MyBoy custom shaders)
        setVec2Uniform("u_texture_size", GBA_WIDTH.toFloat(), GBA_HEIGHT.toFloat())
        setVec2Uniform("TextureSize", GBA_WIDTH.toFloat(), GBA_HEIGHT.toFloat())
        setVec2Uniform("InputSize", GBA_WIDTH.toFloat(), GBA_HEIGHT.toFloat())
        setVec2Uniform("u_output_size", viewWidth.toFloat(), viewHeight.toFloat())
        setVec2Uniform("OutputSize", viewWidth.toFloat(), viewHeight.toFloat())

        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (texLoc >= 0) GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun setSamplerUniform(name: String, unit: Int) {
        val loc = GLES20.glGetUniformLocation(currentProgramId, name)
        if (loc >= 0) {
            GLES20.glUniform1i(loc, unit)
        }
    }

    private fun setVec2Uniform(name: String, x: Float, y: Float) {
        val loc = GLES20.glGetUniformLocation(currentProgramId, name)
        if (loc >= 0) {
            GLES20.glUniform2f(loc, x, y)
        }
    }

    private fun compileActiveShader() {
        val fshSource = when (activeShader) {
            GbaShader.ORIGINAL -> FSH_ORIGINAL
            GbaShader.LCD_3X -> FSH_LCD_3X
            GbaShader.HQ2X -> FSH_HQ2X
            GbaShader.SCANLINES -> FSH_SCANLINES
            GbaShader.GBA_COLOR -> FSH_GBA_COLOR
            GbaShader.GRAYSCALE -> FSH_GRAYSCALE
            GbaShader.CUSTOM -> customShaderSource ?: FSH_ORIGINAL
        }

        val prog = createProgram(VERTEX_SHADER_SRC, fshSource)
        if (prog != 0) {
            if (currentProgramId != 0) {
                GLES20.glDeleteProgram(currentProgramId)
            }
            currentProgramId = prog
            Log.i(TAG, "Active shader compiled successfully: ${activeShader.displayName}")
        } else {
            Log.e(TAG, "Shader compilation failed for ${activeShader.displayName}, falling back to Original")
            if (currentProgramId == 0) {
                currentProgramId = createProgram(VERTEX_SHADER_SRC, FSH_ORIGINAL)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Could not compile shader $type: $log")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        val program = GLES20.glCreateProgram()
        if (program == 0) return 0

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Could not link program: $log")
            GLES20.glDeleteProgram(program)
            return 0
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }
}
