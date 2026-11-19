package com.lightdecoder.imessage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a picked file is, read from its ELF headers without loading it: the CPU type, its soname,
 * and whether it exports libheif's decoder or the OpenBubbles bridge. Only used to give a clear
 * answer for files that are not pinned in [ImessageLibrary.PINS]; it runs nothing from the file.
 */
data class ElfInfo(
    val abi: String,
    val soname: String?,
    val exportsHeifDecoder: Boolean,
    val exportsRustBridge: Boolean,
) {
    companion object {
        private const val MAX_TABLE = 64L shl 20

        /** Null when the file is not a little-endian ELF shared library. */
        fun read(file: File): ElfInfo? = try {
            RandomAccessFile(file, "r").use { parse(it) }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null // malformed headers: not a library we can use
        }

        fun parse(f: RandomAccessFile): ElfInfo? {
            val ident = ByteArray(16)
            if (f.length() < 64) return null
            f.readFully(ident)
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() ||
                ident[3] != 'F'.code.toByte() || ident[5].toInt() != 1
            ) {
                return null
            }
            val is64 = ident[4].toInt() == 2
            val head = read(f, 0, 64)
            val machine = head.getShort(18).toInt() and 0xffff
            val abi = when (machine) {
                183 -> "arm64-v8a"
                40 -> "armeabi-v7a"
                62 -> "x86_64"
                3 -> "x86"
                else -> return null
            }
            val shoff = if (is64) head.getLong(40) else (head.getInt(32).toLong() and 0xffffffffL)
            val shentsize = head.getShort(if (is64) 58 else 46).toInt() and 0xffff
            val shnum = head.getShort(if (is64) 60 else 48).toInt() and 0xffff
            if (shoff <= 0 || shnum == 0 || shentsize == 0) return ElfInfo(abi, null, false, false)

            data class Section(val type: Int, val offset: Long, val size: Long, val link: Int, val entsize: Long)
            val sh = read(f, shoff, shnum.toLong() * shentsize)
            val sections = (0 until shnum).map { i ->
                val o = i * shentsize
                if (is64) {
                    Section(sh.getInt(o + 4), sh.getLong(o + 24), sh.getLong(o + 32), sh.getInt(o + 40), sh.getLong(o + 56))
                } else {
                    Section(
                        sh.getInt(o + 4), sh.getInt(o + 16).toLong() and 0xffffffffL,
                        sh.getInt(o + 20).toLong() and 0xffffffffL, sh.getInt(o + 24),
                        sh.getInt(o + 36).toLong() and 0xffffffffL,
                    )
                }
            }
            var soname: String? = null
            var heif = false
            var bridge = false
            fun strAt(table: ByteBuffer, off: Int): String {
                var end = off
                while (end < table.limit() && table.get(end) != 0.toByte()) end++
                val bytes = ByteArray(end - off)
                table.position(off); table.get(bytes); table.position(0)
                return String(bytes, Charsets.ISO_8859_1)
            }
            for (s in sections) {
                if (s.link !in sections.indices || s.size > MAX_TABLE) continue
                val strSec = sections[s.link]
                if (strSec.size > MAX_TABLE) continue
                when (s.type) {
                    11 -> { // SHT_DYNSYM
                        val strs = read(f, strSec.offset, strSec.size)
                        val syms = read(f, s.offset, s.size)
                        val ent = if (s.entsize > 0) s.entsize.toInt() else if (is64) 24 else 16
                        for (i in 0 until (s.size / ent).toInt()) {
                            val o = i * ent
                            val nameOff = syms.getInt(o)
                            val shndx = syms.getShort(o + if (is64) 6 else 14).toInt() and 0xffff
                            if (nameOff == 0 || shndx == 0 || nameOff >= strs.limit()) continue
                            when (strAt(strs, nameOff)) {
                                "heif_decode_image" -> heif = true
                                "uniffi_rust_bridge_fn_func_send_message" -> bridge = true
                            }
                        }
                    }
                    6 -> { // SHT_DYNAMIC: DT_SONAME
                        val strs = read(f, strSec.offset, strSec.size)
                        val dyn = read(f, s.offset, s.size)
                        val ent = if (is64) 16 else 8
                        for (i in 0 until (s.size / ent).toInt()) {
                            val tag = if (is64) dyn.getLong(i * ent) else dyn.getInt(i * ent).toLong()
                            val v = if (is64) dyn.getLong(i * ent + 8) else (dyn.getInt(i * ent + 4).toLong() and 0xffffffffL)
                            if (tag == 0L) break
                            if (tag == 14L && v < strs.limit()) soname = strAt(strs, v.toInt())
                        }
                    }
                }
            }
            return ElfInfo(abi, soname, heif, bridge)
        }

        private fun read(f: RandomAccessFile, offset: Long, size: Long): ByteBuffer {
            require(offset >= 0 && size in 0..MAX_TABLE && offset + size <= f.length())
            val b = ByteArray(size.toInt())
            f.seek(offset)
            f.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        }
    }
}
