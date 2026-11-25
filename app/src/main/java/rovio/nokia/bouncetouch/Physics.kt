package rovio.nokia.bouncetouch

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ball physics against a triangle soup. +Z is up (found by analysing the level collision meshes).
 * All constants are PLACEHOLDERS tuned by feel - the original values live in Bounce.exe.
 */
class Physics(private val tris: FloatArray, spawn: FloatArray) {
    companion object {
        const val RADIUS = 49f
        const val GRAVITY = 2600f
        const val MAX_SPEED = 750f
        const val JUMP_SPEED = 950f
        const val RESTITUTION = 0.15f
        private const val CELL = 300f
    }

    var px = spawn[0]; var py = spawn[1]; var pz = spawn[2] + RADIUS + 2f
    var vx = 0f; var vy = 0f; var vz = 0f
    var throttle = 0f          // signed forward speed set by swipes
    var grounded = false
    private val spawnX = px; private val spawnY = py; private val spawnZ = pz
    private var minZ = Float.MAX_VALUE
    private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE
    private var cols = 1; private var rows = 1
    private val cells = HashMap<Long, IntArray>()

    init {
        val n = tris.size / 9
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) for (k in 0 until 3) {
            val x = tris[i * 9 + k * 3]; val y = tris[i * 9 + k * 3 + 1]; val z = tris[i * 9 + k * 3 + 2]
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); minZ = min(minZ, z)
        }
        if (n == 0) { minX = 0f; minY = 0f; maxX = 1f; maxY = 1f; minZ = 0f }
        cols = ((maxX - minX) / CELL).toInt() + 2; rows = ((maxY - minY) / CELL).toInt() + 2
        val lists = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until n) {
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (k in 0 until 3) {
                val x = tris[i * 9 + k * 3]; val y = tris[i * 9 + k * 3 + 1]
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            val cx0 = cellX(x0 - RADIUS); val cx1 = cellX(x1 + RADIUS)
            val cy0 = cellY(y0 - RADIUS); val cy1 = cellY(y1 + RADIUS)
            for (cy in cy0..cy1) for (cx in cx0..cx1) lists.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
        }
        for ((k, v) in lists) cells[k] = v.toIntArray()
    }

    private fun cellX(x: Float) = floor((x - minX) / CELL).toInt().coerceIn(0, cols - 1)
    private fun cellY(y: Float) = floor((y - minY) / CELL).toInt().coerceIn(0, rows - 1)
    private fun key(cx: Int, cy: Int) = cx.toLong() * 100000L + cy

    fun respawn() { px = spawnX; py = spawnY; pz = spawnZ; vx = 0f; vy = 0f; vz = 0f; throttle = 0f }

    fun swipe(delta: Float) { throttle = (throttle + delta).coerceIn(-MAX_SPEED * 0.6f, MAX_SPEED) }

    fun jump() { if (grounded) { vz = JUMP_SPEED; grounded = false } }

    fun step(dt: Float, yaw: Float) {
        val tx = cos(yaw) * throttle; val ty = sin(yaw) * throttle
        val blend = min(1f, (if (grounded) 6f else 0.6f) * dt)
        vx += (tx - vx) * blend; vy += (ty - vy) * blend
        vz -= GRAVITY * dt
        px += vx * dt; py += vy * dt; pz += vz * dt
        grounded = false
        repeat(3) { collide() }
        if (pz < minZ - 4000f) respawn()
    }

    /** Height of the highest surface directly below the ball (for the shadow), or null. */
    fun groundZ(): Float? {
        val list = cells[key(cellX(px), cellY(py))] ?: return null
        var best = -Float.MAX_VALUE; var found = false
        for (id in list) {
            val o = id * 9
            val ax = tris[o]; val ay = tris[o + 1]; val az = tris[o + 2]
            val bx = tris[o + 3]; val by = tris[o + 4]; val bz = tris[o + 5]
            val cx = tris[o + 6]; val cy = tris[o + 7]; val cz = tris[o + 8]
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(det) < 1e-6f) continue
            val l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det
            val l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / det
            val l3 = 1f - l1 - l2
            if (l1 < 0f || l2 < 0f || l3 < 0f) continue
            val z = l1 * az + l2 * bz + l3 * cz
            if (z <= pz + RADIUS * 0.5f && z > best) { best = z; found = true }
        }
        return if (found) best else null
    }

    private fun collide() {
        val list = cells[key(cellX(px), cellY(py))] ?: return
        val out = FloatArray(3)
        for (id in list) {
            val o = id * 9
            closest(px, py, pz, tris[o], tris[o + 1], tris[o + 2], tris[o + 3], tris[o + 4], tris[o + 5],
                tris[o + 6], tris[o + 7], tris[o + 8], out)
            val dx = px - out[0]; val dy = py - out[1]; val dz = pz - out[2]
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= RADIUS * RADIUS || d2 < 1e-8f) continue
            val d = sqrt(d2); val nx = dx / d; val ny = dy / d; val nz = dz / d
            px += nx * (RADIUS - d); py += ny * (RADIUS - d); pz += nz * (RADIUS - d)
            val vn = vx * nx + vy * ny + vz * nz
            if (vn < 0f) {
                val k = if (abs(vn) > 300f) 1f + RESTITUTION else 1f
                vx -= nx * vn * k; vy -= ny * vn * k; vz -= nz * vn * k
            }
            if (nz > 0.55f) grounded = true
        }
    }

    // Closest point on triangle (Ericson, Real-Time Collision Detection).
    private fun closest(
        x: Float, y: Float, z: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, o: FloatArray
    ) {
        val abx = bx - ax; val aby = by - ay; val abz = bz - az
        val acx = cx - ax; val acy = cy - ay; val acz = cz - az
        val apx = x - ax; val apy = y - ay; val apz = z - az
        val d1 = abx * apx + aby * apy + abz * apz; val d2 = acx * apx + acy * apy + acz * apz
        if (d1 <= 0f && d2 <= 0f) { o[0] = ax; o[1] = ay; o[2] = az; return }
        val bpx = x - bx; val bpy = y - by; val bpz = z - bz
        val d3 = abx * bpx + aby * bpy + abz * bpz; val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0f && d4 <= d3) { o[0] = bx; o[1] = by; o[2] = bz; return }
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            val v = d1 / (d1 - d3); o[0] = ax + abx * v; o[1] = ay + aby * v; o[2] = az + abz * v; return
        }
        val cpx = x - cx; val cpy = y - cy; val cpz = z - cz
        val d5 = abx * cpx + aby * cpy + abz * cpz; val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0f && d5 <= d6) { o[0] = cx; o[1] = cy; o[2] = cz; return }
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            val w = d2 / (d2 - d6); o[0] = ax + acx * w; o[1] = ay + acy * w; o[2] = az + acz * w; return
        }
        val va = d3 * d6 - d5 * d4
        if (va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            o[0] = bx + (cx - bx) * w; o[1] = by + (cy - by) * w; o[2] = bz + (cz - bz) * w; return
        }
        val denom = 1f / (va + vb + vc); val v = vb * denom; val w = vc * denom
        o[0] = ax + abx * v + acx * w; o[1] = ay + aby * v + acy * w; o[2] = az + abz * v + acz * w
    }
}
