package dev.hgdaylight.field

import dev.hgdaylight.rom.Blz
import dev.hgdaylight.rom.Bytes
import dev.hgdaylight.rom.NdsRom

/** One map header (MapHeader, 24 bytes, `include/map_header.h`). */
class MapHeader(b: Bytes, o: Int) {
    val areaDataBank = b.u8(o + 1)
    val matrixId = b.u16(o + 4)
    val eventsBank = b.u16(o + 16)
    val mapType = (b.u32(o + 20) ushr 8) and 15
    val cameraType = (b.u32(o + 20) ushr 12) and 63
}

/**
 * Tables that live in code, not in the file system, read from the player's ROM at fixed offsets (the app only
 * accepts the SoulSilver (US) image, SHA-1 checked, so the offsets cannot move):
 *  - sMapHeaders (`src/data/map_headers.h`, 540 entries) in the ARM9 binary, BLZ-compressed;
 *  - gObjectEventGraphicsInfos (`asm/overlay_01_sprite_data.s`, rows {spriteId, mmodel member, bits},
 *    0xFFFF-terminated) in overlay 1 (the field), BLZ-compressed.
 */
class GameData(rom: NdsRom) {
    val headers: List<MapHeader>
    private val spriteRows = HashMap<Int, Pair<Int, Int>>()

    init {
        val raw = rom.rawArm9()
        val arm9 = Bytes(Blz.decode(raw, ARM9_COMPRESSED_END))
        headers = List(MAP_COUNT) { MapHeader(arm9, MAP_HEADERS + 24 * it) }
        val ov = Bytes(rom.overlay(1))
        var p = SPRITE_TABLE
        while (ov.u16(p) != 0xFFFF) {
            spriteRows[ov.u16(p)] = ov.u16(p + 2) to ov.u16(p + 4)
            p += 6
        }
    }

    /** The mmodel member (texture file) for an object's sprite id, or null. */
    fun mmodelOf(spriteId: Int): Int? = spriteRows[spriteId]?.first

    companion object {
        const val ARM9_COMPRESSED_END = 0xBA318
        const val MAP_HEADERS = 0xF6BE0
        const val MAP_COUNT = 540
        const val SPRITE_TABLE = 0x21BA8
    }
}

/** A zone_event member (`src/map_events.c`, `include/map_events_internal.h`): bg, object, warp, coord events. */
class MapEvents(b: Bytes) {
    class Obj(val id: Int, val spriteId: Int, val movement: Int, val eventFlag: Int, val script: Int, val facing: Int, val x: Int, val z: Int, val y: Int)
    class Warp(val x: Int, val z: Int, val header: Int, val anchor: Int)
    class Bg(val script: Int, val type: Int, val x: Int, val z: Int)

    val bgs: List<Bg>
    val objects: List<Obj>
    val warps: List<Warp>

    init {
        var p = 0
        val nb = b.u32(p); p += 4
        bgs = List(nb) { val q = p + 20 * it; Bg(b.u16(q), b.u16(q + 2), b.s32(q + 4), b.s32(q + 8)) }
        p += 20 * nb
        val no = b.u32(p); p += 4
        objects = List(no) {
            val q = p + 32 * it
            Obj(b.u16(q), b.u16(q + 2), b.u16(q + 4), b.u16(q + 8), b.u16(q + 10), b.s16(q + 12), b.u16(q + 24), b.u16(q + 26), b.s32(q + 28))
        }
        p += 32 * no
        val nw = b.u32(p); p += 4
        warps = List(nw) { val q = p + 12 * it; Warp(b.u16(q), b.u16(q + 2), b.u16(q + 4), b.u16(q + 6)) }
    }

    companion object {
        const val ZONE_EVENT = "a/0/3/2"
        fun load(rom: NdsRom, bank: Int) = MapEvents(rom.narc(ZONE_EVENT)[bank])
    }
}
