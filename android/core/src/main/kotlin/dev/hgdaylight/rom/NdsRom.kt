package dev.hgdaylight.rom

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * The cartridge image and its NitroFS: the header's file name table (FNT) and file allocation table (FAT).
 * Paths are the ones the decomp uses, e.g. "a/0/6/5" (`include/filesystem_files_def.h`).
 */
class NdsRom(data: ByteBuffer) {
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

    /** The ARM9 binary as stored (compressed past its first 0x4000 bytes). */
    fun rawArm9(): ByteArray = b.copy(b.u32(0x20), b.u32(0x2C))

    /** ARM9 overlay [n], decompressed if its table entry says so (flag bit 24, size in the low 24 bits). */
    fun overlay(n: Int): ByteArray {
        val e = b.u32(0x50) + 32 * n
        val raw = file(b.u32(e + 24)).copy()
        val flags = b.u32(e + 28)
        return if (flags and (1 shl 24) != 0) Blz.decode(raw, flags and 0xFFFFFF) else raw
    }

    companion object {
        const val SOULSILVER_US_SHA1 = "f8dc38ea20c17541a43b58c5e6d18c1732c7e582"

        fun sha1(file: File): String {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Memory-mapped: the 128 MB image is never copied onto the heap. */
        fun load(file: File): NdsRom = RandomAccessFile(file, "r").use { f ->
            NdsRom(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()))
        }
    }
}
