package rovio.nokia.bouncetouch

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
    private val actions = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var holdSide = 0        // -1 left half, +1 right half, 0 none
    @Volatile private var holdSince = 0L
    private var levelNo = 1
    private var level: Level? = null
    private var physics: Physics? = null
    private val textures = HashMap<String, Int>()
    private var yaw = Math.toRadians(140.0).toFloat()
    private var lastNs = 0L
    private var acc = 0f
    private var w = 1; private var h = 1

    private var texProg = 0; private var ballProg = 0
    private lateinit var sphereV: FloatBuffer; private lateinit var sphereN: FloatBuffer; private lateinit var sphereI: ShortBuffer
    private var sphereCount = 0
    private lateinit var discV: FloatBuffer

    private val proj = FloatArray(16); private val view = FloatArray(16)
    private val vp = FloatArray(16); private val mvp = FloatArray(16); private val model = FloatArray(16)

    // ---- called from UI thread ----
    fun swipe(strength: Float) { actions.add { physics?.swipe(strength * Physics.MAX_SPEED) } }
    fun jump() { actions.add { physics?.jump() } }
    fun nextLevel() { actions.add { levelNo = levelNo % 8 + 1; loadLevel() } }
    fun holdStart(xFraction: Float) { holdSide = if (xFraction >= 0.5f) 1 else -1; holdSince = System.nanoTime() }
    fun holdCancel() { holdSide = 0 }

    // ---- GL thread ----
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.45f, 0.72f, 0.95f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        texProg = program(
            "attribute vec3 aPos;attribute vec2 aUV;uniform mat4 uMVP;varying vec2 vUV;void main(){gl_Position=uMVP*vec4(aPos,1.);vUV=aUV;}",
            "precision mediump float;uniform sampler2D uTex;uniform vec4 uColor;uniform float uUseTex;varying vec2 vUV;" +
                "void main(){vec4 k=uUseTex>0.5?texture2D(uTex,vUV)*uColor:vec4(uColor.rgb*uColor.a,uColor.a);if(k.a<0.05)discard;gl_FragColor=k;}")
        ballProg = program(
            "attribute vec3 aPos;attribute vec3 aNrm;uniform mat4 uMVP;varying vec3 vN;void main(){gl_Position=uMVP*vec4(aPos,1.);vN=aNrm;}",
            "precision mediump float;uniform vec4 uColor;varying vec3 vN;void main(){float l=0.55+0.45*max(dot(normalize(vN),normalize(vec3(-0.4,-0.3,0.85))),0.);" +
                "float s=pow(max(dot(normalize(vN),normalize(vec3(-0.3,-0.4,0.86))),0.),24.)*0.5;gl_FragColor=vec4(uColor.rgb*l+vec3(s),1.);}")
        buildSphere(16, 24)
        val d = FloatArray((24 + 2) * 3)
        d[2] = 0f
        for (i in 0..24) { val a = i * 2 * Math.PI.toFloat() / 24; d[(i + 1) * 3] = cos(a); d[(i + 1) * 3 + 1] = sin(a) }
        discV = floatBuf(d)
        textures.clear(); loadLevel()
        lastNs = System.nanoTime()
    }

    private fun loadLevel() {
        for (t in textures.values) GLES20.glDeleteTextures(1, intArrayOf(t), 0)
        textures.clear()
        val lv = LevelLoader.load(ctx, levelNo)
        level = lv
        val spawn = if (lv.spawn[0] == 0f && lv.spawn[1] == 0f && lv.spawn[2] == 0f) fallbackSpawn(lv.tris) else lv.spawn
        physics = Physics(lv.tris, spawn)
        for (g in lv.groups) if (g.tex.isNotEmpty() && !textures.containsKey(g.tex)) textures[g.tex] = loadTexture(g.tex)
        yaw = Math.toRadians(140.0).toFloat(); acc = 0f
    }

    /** Levels 2-8: spawn point not decoded yet - use the largest upward-facing collision triangle of the first meshes. */
    private fun fallbackSpawn(t: FloatArray): FloatArray {
        var best = 0f; var out = floatArrayOf(0f, 0f, 0f)
        for (i in 0 until minOf(t.size / 9, 400)) {
            val o = i * 9
            val ux = t[o + 3] - t[o]; val uy = t[o + 4] - t[o + 1]; val uz = t[o + 5] - t[o + 2]
            val vx = t[o + 6] - t[o]; val vy = t[o + 7] - t[o + 1]; val vz = t[o + 8] - t[o + 2]
            val nz = ux * vy - uy * vx
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (len > 0f && Math.abs(nz) / len > 0.8f && len > best) {
                best = len; out = floatArrayOf((t[o] + t[o + 3] + t[o + 6]) / 3, (t[o + 1] + t[o + 4] + t[o + 7]) / 3, (t[o + 2] + t[o + 5] + t[o + 8]) / 3)
            }
        }
        return out
    }

    private fun loadTexture(name: String): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        val bmp = ctx.assets.open("textures/$name").use { BitmapFactory.decodeStream(it) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val pot = (bmp.width and (bmp.width - 1)) == 0 && (bmp.height and (bmp.height - 1)) == 0
        val wrap = if (pot) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return ids[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height; GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(proj, 0, 55f, width.toFloat() / height, 20f, 40000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) { (actions.poll() ?: break).invoke() }
        val now = System.nanoTime(); val dt = ((now - lastNs) / 1e9f).coerceAtMost(0.05f); lastNs = now
        val ph = physics ?: return; val lv = level ?: return
        // long press (> 0.25 s) on right/left half turns the camera while held
        val side = holdSide
        if (side != 0 && now - holdSince > 250_000_000L) yaw -= side * 1.6f * dt   // right = clockwise
        acc += dt
        while (acc >= 1f / 120f) { ph.step(1f / 120f, yaw); acc -= 1f / 120f }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val hx = cos(yaw); val hy = sin(yaw)
        Matrix.setLookAtM(view, 0, ph.px - hx * 430f, ph.py - hy * 430f, ph.pz + 190f,
            ph.px + hx * 60f, ph.py + hy * 60f, ph.pz + 20f, 0f, 0f, 1f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(texProg)
        val uMvp = GLES20.glGetUniformLocation(texProg, "uMVP")
        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        for (pass in 0..1) {
            GLES20.glDepthMask(pass == 0)
            for (g in lv.groups) if (g.noZWrite == (pass == 1)) drawGroup(g)
        }
        GLES20.glDepthMask(true)
        drawShadow(ph)
        drawBall(ph)
    }

    private fun drawGroup(g: Group) {
        val aPos = GLES20.glGetAttribLocation(texProg, "aPos"); val aUV = GLES20.glGetAttribLocation(texProg, "aUV")
        val tex = if (g.tex.isNotEmpty()) textures[g.tex] else null
        GLES20.glUniform1f(GLES20.glGetUniformLocation(texProg, "uUseTex"), if (tex != null) 1f else 0f)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(texProg, "uColor"), 1, g.color, 0)
        if (tex != null) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex) }
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, g.pos)
        GLES20.glEnableVertexAttribArray(aUV); GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, g.uv)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, g.indexCount, GLES20.GL_UNSIGNED_SHORT, g.idx)
    }

    private fun drawShadow(ph: Physics) {
        val gz = ph.groundZ() ?: return
        GLES20.glUseProgram(texProg)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, ph.px, ph.py, gz + 1.5f)
        val k = Physics.RADIUS * 0.9f; Matrix.scaleM(model, 0, k, k, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(texProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(texProg, "uUseTex"), 0f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(texProg, "uColor"), 0f, 0.1f, 0f, 0.45f)
        val aPos = GLES20.glGetAttribLocation(texProg, "aPos")
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, discV)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 26)
        GLES20.glDepthMask(true)
    }

    private fun drawBall(ph: Physics) {
        GLES20.glUseProgram(ballProg)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, ph.px, ph.py, ph.pz)
        Matrix.scaleM(model, 0, Physics.RADIUS, Physics.RADIUS, Physics.RADIUS)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ballProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(ballProg, "uColor"), 0.88f, 0.08f, 0.06f, 1f)
        val aPos = GLES20.glGetAttribLocation(ballProg, "aPos"); val aN = GLES20.glGetAttribLocation(ballProg, "aNrm")
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, sphereV)
        GLES20.glEnableVertexAttribArray(aN); GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 0, sphereN)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereCount, GLES20.GL_UNSIGNED_SHORT, sphereI)
    }

    private fun buildSphere(rings: Int, sectors: Int) {
        val v = ArrayList<Float>(); val idx = ArrayList<Short>()
        for (r in 0..rings) {
            val phi = Math.PI * r / rings
            for (s in 0..sectors) {
                val th = 2 * Math.PI * s / sectors
                v.add((sin(phi) * cos(th)).toFloat()); v.add((sin(phi) * sin(th)).toFloat()); v.add(cos(phi).toFloat())
            }
        }
        for (r in 0 until rings) for (s in 0 until sectors) {
            val a = (r * (sectors + 1) + s).toShort(); val b = (a + sectors + 1).toShort()
            idx.add(a); idx.add(b); idx.add((a + 1).toShort()); idx.add((a + 1).toShort()); idx.add(b); idx.add((b + 1).toShort())
        }
        sphereV = floatBuf(v.toFloatArray()); sphereN = floatBuf(v.toFloatArray())
        sphereCount = idx.size
        sphereI = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { idx.forEach { put(it) }; position(0) }
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p); return p
    }
}
