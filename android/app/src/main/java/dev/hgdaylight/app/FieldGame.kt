package dev.hgdaylight.app

import dev.hgdaylight.field.GameData
import dev.hgdaylight.field.HeroSprite
import dev.hgdaylight.field.MapEvents
import dev.hgdaylight.field.ObjectSprite
import dev.hgdaylight.field.World
import dev.hgdaylight.field.behavior
import dev.hgdaylight.field.blocked
import dev.hgdaylight.field.height
import dev.hgdaylight.g3d.Texture
import dev.hgdaylight.render.DaylightStyle
import dev.hgdaylight.rom.NdsRom
import kotlin.math.abs
import kotlin.math.sin

/** A billboard to draw: feet at (x, y, z), [width] x [height] world units. */
class Sprite(val texture: Texture, val x: Float, val y: Float, val z: Float, val width: Int, val height: Int, val flip: Boolean, val instance: Int)

/**
 * The walk-around demo, ticked at 60 Hz on the render thread: the player walks the field and goes through
 * doors (warp events), with the map's people and objects standing in place.
 *
 * The original's field logic runs at 30 Hz (`src/main.c`: logic, then two VBlanks), so every count from the
 * decomp is doubled here: a walk step is MOVEMENT_STEP_* (8 ticks, `src/map_object_movement_commands.c`)
 * = 16 frames, and the walk animation advances one billboard frame per tick = half a frame per frame.
 */
class FieldGame(private val rom: NdsRom) {
    private val data = GameData(rom)
    val style = DaylightStyle.Options()
    var input: Joystick? = null
    private val hero = HeroSprite(rom)
    /** The field touch menu (`src/field/touchscreen_menu.c`), drawn from the ROM. */
    val touchMenu = dev.hgdaylight.g2d.TouchMenu(rom)

    /** The map header the player is in, its world (matrix + area) and its events. */
    var header = MAP_NEW_BARK
        private set
    @Volatile var world: World = worldFor(MAP_NEW_BARK)
        private set
    private var events = MapEvents.load(rom, data.headers[MAP_NEW_BARK].eventsBank)
    /** Interiors (any matrix but the overworld's) sit in black, the overworld on paper. */
    val indoor: Boolean get() = data.headers[header].matrixId != 0

    private class Npc(val sprite: ObjectSprite, val x: Int, val z: Int, val facing: Int, val y: Float, val instance: Int)
    private var npcs: List<Npc> = emptyList()
    private val spriteCache = HashMap<Int, ObjectSprite?>()

    /** Tile coordinates in the whole matrix (32 tiles per chunk). */
    private var tileX = 0
    private var tileZ = 0
    /** DIR_NORTH 0, SOUTH 1, WEST 2, EAST 3. */
    private var facing = DIR_SOUTH
    private var y = 0f

    private enum class Motion { IDLE, WALK, BUMP, JUMP }
    private var motion = Motion.IDLE
    private var fromX = 0f; private var fromZ = 0f
    private var toX = 0f; private var toZ = 0f
    private var frame = 0
    private var frames = 0
    /** Billboard animation frame, in 30 Hz ticks (two per tile). */
    private var anim = 0f
    /** Frames the stick has pointed at a new facing; a held turn walks after TURN_FRAMES. */
    private var turnHeld = 0
    private var lastWasStep = false

    /** The warp tile the player arrived on: it does not fire again until left (exit mats fire on a blocked step). */
    private var arrivedX = -1
    private var arrivedZ = -1
    /** Screen fade, 0 = clear, 1 = black; a pending warp waits for the fade out. */
    var fade = 0f
        private set
    private var pendingWarp: MapEvents.Warp? = null
    private var fadingIn = false

    init {
        val (cx, cz) = world.cellOf(MAP_NEW_BARK) ?: (0 to 0)
        tileX = cx * 32 + 13; tileZ = cz * 32 + 15
        search@ for (r in 0..8) for (dz in -r..r) for (dx in -r..r) {
            if (!world.blocked(tileX + dx, tileZ + dz)) { tileX += dx; tileZ += dz; break@search }
        }
        place(tileX, tileZ, DIR_SOUTH)
        loadNpcs()
    }

    private fun worldFor(h: Int): World {
        val mh = data.headers[h]
        return World(rom, mh.matrixId, mh.areaDataBank)
    }

    private fun place(x: Int, z: Int, dir: Int) {
        tileX = x; tileZ = z; facing = dir
        fromX = x.toFloat(); fromZ = z.toFloat(); toX = fromX; toZ = fromZ
        motion = Motion.IDLE
        y = groundAt(x * 16f + 8f, z * 16f + 8f, 0f)
    }

    private fun loadNpcs() {
        npcs = events.objects.mapIndexedNotNull { i, o ->
            val member = data.mmodelOf(o.spriteId) ?: return@mapIndexedNotNull null
            val s = spriteCache.getOrPut(member) { runCatching { ObjectSprite(rom, member) }.getOrNull() } ?: return@mapIndexedNotNull null
            Npc(s, o.x, o.z, o.facing.coerceIn(0, 3), groundAt(o.x * 16f + 8f, o.z * 16f + 8f, 0f), NPC_ID_BASE + i)
        }
    }

    fun chunkOfPlayer(): Pair<Int, Int> = Math.floorDiv(tileX, 32) to Math.floorDiv(tileZ, 32)

    fun tick() {
        if (updateFade()) return
        if (motion != Motion.IDLE) {
            frame++
            anim += 0.5f
            if (frame >= frames) {
                val wasBump = motion == Motion.BUMP
                motion = Motion.IDLE
                fromX = toX; fromZ = toZ
                if (!wasBump) arrivedOrWarp()
            } else return
        }
        val dir = input?.dir
        if (dir == null) {
            turnHeld = 0
            lastWasStep = false
            // Standing: BillboardObject_AlignSequenceFrame snaps to frame 0 or 8.
            anim = (anim.toInt() / 8 * 8).toFloat()
            return
        }
        val d = when (dir) {
            Joystick.Dir.UP -> DIR_NORTH
            Joystick.Dir.DOWN -> DIR_SOUTH
            Joystick.Dir.LEFT -> DIR_WEST
            Joystick.Dir.RIGHT -> DIR_EAST
        }
        if (d != facing) {
            facing = d
            turnHeld = 0
            return
        }
        if (turnHeld < TURN_FRAMES && !lastWasStep) { turnHeld++; return }
        step(d)
    }

    /** Runs the fade; true while the field is frozen behind it. */
    private fun updateFade(): Boolean {
        val w = pendingWarp
        if (w != null) {
            fade = minOf(1f, fade + 1f / FADE_FRAMES)
            if (fade >= 1f) { doWarp(w); pendingWarp = null; fadingIn = true }
            return true
        }
        if (fadingIn) {
            fade = maxOf(0f, fade - 1f / FADE_FRAMES)
            if (fade <= 0f) fadingIn = false
            return fadingIn
        }
        return false
    }

    private fun warpAt(x: Int, z: Int) = events.warps.firstOrNull { it.x == x && it.z == z }

    /** A step ended on (tileX, tileZ): a warp there fires, unless the player just arrived on it. */
    private fun arrivedOrWarp() {
        if (tileX == arrivedX && tileZ == arrivedZ) return
        arrivedX = -1; arrivedZ = -1
        warpAt(tileX, tileZ)?.let { pendingWarp = it }
    }

    /** Warps to the target header's warp [MapEvents.Warp.anchor], facing into the new map. */
    private fun doWarp(w: MapEvents.Warp) {
        if (w.header !in data.headers.indices) return
        val target = data.headers[w.header]
        val ev = MapEvents.load(rom, target.eventsBank)
        val dest = ev.warps.getOrNull(w.anchor) ?: return
        header = w.header
        events = ev
        world = worldFor(w.header)
        // Into a building: you come in at the mat facing north; out of one: at the door facing south.
        place(dest.x, dest.z, if (target.matrixId != 0) DIR_NORTH else DIR_SOUTH)
        arrivedX = dest.x; arrivedZ = dest.z
        loadNpcs()
    }

    private fun step(d: Int) {
        val dx = DX[d]; val dz = DZ[d]
        val nx = tileX + dx; val nz = tileZ + dz
        val beh = world.behavior(nx, nz)
        frame = 0
        if (LEDGE_JUMP_DIR[beh] == d) {
            tileX += 2 * dx; tileZ += 2 * dz
            toX = tileX.toFloat(); toZ = tileZ.toFloat()
            motion = Motion.JUMP; frames = 2 * STEP_FRAMES
        } else if (!canEnter(nx, nz)) {
            // An exit mat: stepping off it toward the outside (blocked) leaves the building.
            val here = warpAt(tileX, tileZ)
            if (here != null && indoor) { pendingWarp = here; return }
            motion = Motion.BUMP; frames = STEP_FRAMES * 2
            toX = fromX; toZ = fromZ
        } else {
            tileX = nx; tileZ = nz
            toX = nx.toFloat(); toZ = nz.toFloat()
            motion = Motion.WALK; frames = STEP_FRAMES
        }
        lastWasStep = motion != Motion.BUMP
        if (LOG_STEPS) android.util.Log.i("SSDaylight", "step dir=$d $motion -> tile ($tileX, $tileZ) beh=${"%02x".format(beh)} y=$y header=$header")
    }

    private fun canEnter(nx: Int, nz: Int): Boolean {
        if (warpAt(nx, nz) != null) return true // doors are blocked tiles that warp
        if (world.blocked(nx, nz)) return false
        if (npcs.any { it.x == nx && it.z == nz }) return false
        val beh = world.behavior(nx, nz)
        if (beh == 0xFF || LEDGE_JUMP_DIR[beh] >= 0) return false
        if (beh in WATER) return false
        // FieldSystem_GetHeightStep: a height difference of 20 or more blocks the move.
        val ny = groundAt(nx * 16f + 8f, nz * 16f + 8f, y)
        return abs(ny - y) < 20f
    }

    private fun groundAt(x: Float, z: Float, current: Float): Float = world.height(x, z, current) ?: current

    /** The player's feet, between tiles while a step runs. */
    fun position(): FloatArray {
        val t = if (motion == Motion.IDLE || motion == Motion.BUMP) 1f else frame.toFloat() / frames
        val fx = fromX + (toX - fromX) * t
        val fz = fromZ + (toZ - fromZ) * t
        val wx = fx * 16f + 8f; val wz = fz * 16f + 8f
        y = groundAt(wx, wz, y)
        val hop = if (motion == Motion.JUMP) (sin(Math.PI * t) * 12.0).toFloat() else 0f
        return floatArrayOf(wx, y + hop, wz)
    }

    fun cameraTarget(): FloatArray {
        val p = position()
        return floatArrayOf(p[0], y, p[2])
    }

    fun sprites(): List<Sprite> {
        val p = position()
        val out = ArrayList<Sprite>(npcs.size + 1)
        val tex = hero.frame(facing, running = false, animFrame = anim.toInt())
        out += Sprite(tex, p[0], p[1], p[2] + HeroSprite.Z_BIAS, HeroSprite.SIZE, HeroSprite.SIZE, false, PLAYER_ID)
        for (n in npcs) {
            val t = n.sprite.frame(n.facing)
            out += Sprite(t, n.x * 16f + 8f, n.y, n.z * 16f + 8f + 6f, t.width, t.height, false, n.instance)
        }
        return out
    }

    companion object {
        const val MAP_NEW_BARK = 60
        const val DIR_NORTH = 0
        const val DIR_SOUTH = 1
        const val DIR_WEST = 2
        const val DIR_EAST = 3
        /** gDirectionDeltaX / gDirectionDeltaY (`src/map_object_update.c`). */
        val DX = intArrayOf(0, 0, -1, 1)
        val DZ = intArrayOf(-1, 1, 0, 0)
        const val STEP_FRAMES = 16
        const val TURN_FRAMES = 6
        const val FADE_FRAMES = 16f
        const val PLAYER_ID = 0xFFFFF0
        const val NPC_ID_BASE = 0xFFF000
        @JvmField var LOG_STEPS = false

        /** Ledge behaviours 0x38-0x3B jump east, west, north, south (`include/constants/metatile_behavior.h`). */
        val LEDGE_JUMP_DIR = IntArray(256) { -1 }.also {
            it[0x38] = DIR_EAST; it[0x39] = DIR_WEST; it[0x3A] = DIR_NORTH; it[0x3B] = DIR_SOUTH
        }
        /** River, whirlpool, waterfall, sea: not walkable without Surf. */
        val WATER = setOf(0x10, 0x11, 0x13, 0x15)
    }
}
