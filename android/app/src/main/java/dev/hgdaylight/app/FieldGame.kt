package dev.hgdaylight.app

import dev.hgdaylight.field.HeroSprite
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
 * The walk-around demo, ticked at 60 Hz on the render thread: the player on the tile grid of the Johto matrix
 * (area 2: New Bark, Route 29, Cherrygrove, Route 27).
 *
 * The original's field logic runs at 30 Hz (`src/main.c`: logic, then two VBlanks), so every count from the
 * decomp is doubled here: a walk step is MOVEMENT_STEP_* (8 ticks, `src/map_object_movement_commands.c`)
 * = 16 frames, and the walk animation advances one billboard frame per tick = half a frame per frame.
 */
class FieldGame(rom: NdsRom) {
    val world = World(rom, matrixId = 0, areaId = 2)
    val style = DaylightStyle.Options()
    var input: Joystick? = null
    private val hero = HeroSprite(rom)

    /** Tile coordinates in the whole matrix (32 tiles per chunk). */
    private var tileX: Int
    private var tileZ: Int
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

    init {
        val (cx, cz) = world.cellOf(60) ?: (0 to 0) // MAP_NEW_BARK
        tileX = cx * 32 + 13; tileZ = cz * 32 + 15
        // Start on a free tile near the middle of the town.
        search@ for (r in 0..8) for (dz in -r..r) for (dx in -r..r) {
            if (!world.blocked(tileX + dx, tileZ + dz)) { tileX += dx; tileZ += dz; break@search }
        }
        fromX = tileX.toFloat(); fromZ = tileZ.toFloat(); toX = fromX; toZ = fromZ
        y = groundAt(tileX * 16f + 8f, tileZ * 16f + 8f, 0f)
    }

    fun chunkOfPlayer(): Pair<Int, Int> = Math.floorDiv(tileX, 32) to Math.floorDiv(tileZ, 32)

    fun tick() {
        if (motion != Motion.IDLE) {
            frame++
            anim += 0.5f
            if (frame >= frames) {
                motion = Motion.IDLE
                fromX = toX; fromZ = toZ
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
        if (turnHeld < TURN_FRAMES && turnHeldFromRest()) { turnHeld++; return }
        step(d)
    }

    /** A turn from standing waits briefly so a tap only turns; walking on keeps going. */
    private var lastWasStep = false
    private fun turnHeldFromRest(): Boolean = !lastWasStep

    private fun step(d: Int) {
        val dx = DX[d]; val dz = DZ[d]
        val nx = tileX + dx; val nz = tileZ + dz
        val beh = world.behavior(nx, nz)
        frame = 0
        if (LEDGE_JUMP_DIR[beh] == d) {
            // Ledge: hop over it, two tiles.
            tileX += 2 * dx; tileZ += 2 * dz
            toX = tileX.toFloat(); toZ = tileZ.toFloat()
            motion = Motion.JUMP; frames = 2 * STEP_FRAMES
        } else if (!canEnter(nx, nz, d)) {
            motion = Motion.BUMP; frames = STEP_FRAMES * 2
            toX = fromX; toZ = fromZ
        } else {
            tileX = nx; tileZ = nz
            toX = nx.toFloat(); toZ = nz.toFloat()
            motion = Motion.WALK; frames = STEP_FRAMES
        }
        lastWasStep = motion != Motion.BUMP
    }

    private fun canEnter(nx: Int, nz: Int, d: Int): Boolean {
        if (world.blocked(nx, nz)) return false
        val beh = world.behavior(nx, nz)
        if (beh == 0xFF || beh in LEDGE_JUMP_DIR.indices && LEDGE_JUMP_DIR[beh] >= 0) return false
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
        // Walking (and bumping in place) animates; standing shows the snapped frame.
        val tex = hero.frame(facing, running = false, animFrame = anim.toInt())
        return listOf(Sprite(tex, p[0], p[1], p[2] + HeroSprite.Z_BIAS, HeroSprite.SIZE, HeroSprite.SIZE, false, PLAYER_ID))
    }

    companion object {
        const val DIR_NORTH = 0
        const val DIR_SOUTH = 1
        const val DIR_WEST = 2
        const val DIR_EAST = 3
        /** gDirectionDeltaX / gDirectionDeltaY (`src/map_object_update.c`). */
        val DX = intArrayOf(0, 0, -1, 1)
        val DZ = intArrayOf(-1, 1, 0, 0)
        const val STEP_FRAMES = 16
        const val TURN_FRAMES = 6
        const val PLAYER_ID = 0xFFFFF0

        /** Ledge behaviours 0x38-0x3B jump east, west, north, south (`include/constants/metatile_behavior.h`). */
        val LEDGE_JUMP_DIR = IntArray(256) { -1 }.also {
            it[0x38] = DIR_EAST; it[0x39] = DIR_WEST; it[0x3A] = DIR_NORTH; it[0x3B] = DIR_SOUTH
        }
        /** River, whirlpool, waterfall, sea, shallow water's deep variants: not walkable without Surf. */
        val WATER = setOf(0x10, 0x11, 0x13, 0x15)
    }
}
