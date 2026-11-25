package rovio.nokia.bouncetouch

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class Group(
    val tex: String, val color: FloatArray, val noZWrite: Boolean,
    val pos: FloatBuffer, val uv: FloatBuffer, val idx: ShortBuffer, val indexCount: Int
)

class Level(val groups: List<Group>, val tris: FloatArray, val spawn: FloatArray)

object LevelLoader {
    private fun direct(n: Int) = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())

    fun load(ctx: Context, number: Int): Level {
        val bytes = ctx.assets.open("levels/level%02d.bin".format(number)).use { it.readBytes() }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); b.get(magic)
        require(String(magic) == "BTL1") { "bad level file" }
        val groups = ArrayList<Group>()
        repeat(b.int) {
            val hasTex = b.get().toInt() != 0
            val color = FloatArray(4) { b.float }
            val nameLen = b.short.toInt() and 0xffff
            val nameBytes = ByteArray(nameLen); b.get(nameBytes)
            val noZ = b.get().toInt() != 0
            val v = b.int; val i = b.int
            val pos = direct(v * 12).asFloatBuffer(); repeat(v * 3) { pos.put(b.float) }; pos.position(0)
            val uv = direct(v * 8).asFloatBuffer(); repeat(v * 2) { uv.put(b.float) }; uv.position(0)
            val idx = direct(i * 2).asShortBuffer(); repeat(i) { idx.put(b.short) }; idx.position(0)
            groups.add(Group(if (hasTex) String(nameBytes) else "", color, noZ, pos, uv, idx, i))
        }
        val triCount = b.int
        val tris = FloatArray(triCount * 9) { b.float }
        val spawn = FloatArray(3) { b.float }
        return Level(groups, tris, spawn)
    }
}
