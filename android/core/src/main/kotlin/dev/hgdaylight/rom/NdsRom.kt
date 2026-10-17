package dev.hgdaylight.rom

import java.io.File
import java.security.MessageDigest

/**
 * The cartridge image and its NitroFS: the header's file name table (FNT) and file allocation table (FAT).
 * Paths are the ones the decomp uses, e.g. "a/0/6/5" (`include/filesystem_files_def.h`).
 */
class NdsRom(val data: ByteArray) {
    private val b = Bytes(data)
    val title: String = b.ascii(0, 12)
    val gameCode: String = b.ascii(12, 4)

    private val fntOff = b.u32(0x40)
    private val fatOff = b.u32(0x48)
    val fileCount = b.u32(0x4C) / 8

    private val paths = HashMap<String, Int>()

    init {
        walk(0xF000, "")
    }

    private fun walk(dirId: Int, prefix: String) {
        val entry = fntOff + 8 * (dirId and 0xFFF)
        var p = fntOff + b.u32(entry)
        var fileId = b.u16(entry + 4)
        while (true) {
            val len = b.u8(p++)
            if (len == 0) break
            val name = b.ascii(p, len and 0x7F)
            p += len and 0x7F
            if (len and 0x80 != 0) {
                walk(b.u16(p), "$prefix$name/")
                p += 2
            } else {
                paths["$prefix$name"] = fileId++
            }
        }
    }

    fun fileId(path: String): Int = paths[path] ?: error("No file $path in the ROM")

    fun file(id: Int): Bytes {
        val start = b.u32(fatOff + 8 * id)
        val end = b.u32(fatOff + 8 * id + 4)
        return b.at(start, end - start)
    }

    fun file(path: String): Bytes = file(fileId(path))

    fun narc(path: String): Narc = Narc(file(path))

    companion object {
        const val SOULSILVER_US_SHA1 = "f8dc38ea20c17541a43b58c5e6d18c1732c7e582"

        fun sha1(data: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

        fun load(file: File): NdsRom = NdsRom(file.readBytes())
    }
}
