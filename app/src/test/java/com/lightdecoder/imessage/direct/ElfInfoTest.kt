package com.lightdecoder.imessage.direct

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ElfInfo] reads a library's CPU, soname and exports from a small ELF built here. */
class ElfInfoTest {

    /** A minimal 64-bit ELF with .dynstr, .dynsym (one export) and .dynamic (DT_SONAME). */
    private fun elf64(machine: Int, soname: String, export: String): File {
        val strtab = ("\u0000" + soname + "\u0000" + export + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val sonameOff = 1L
        val exportOff = 2 + soname.length
        val dynsym = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(24) // entry 0 is the null symbol
            putInt(exportOff); put(0x12); put(0); putShort(5); putLong(0x1000); putLong(4)
        }.array()
        val dynamic = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(14); putLong(sonameOff); putLong(0); putLong(0)
        }.array()
        val strOff = 64L
        val symOff = strOff + strtab.size
        val dynOff = symOff + dynsym.size
        val shOff = dynOff + dynamic.size
        val sections = 6
        val buf = ByteBuffer.allocate((shOff + sections * 64).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        buf.position(16); buf.putShort(3); buf.putShort(machine.toShort())
        buf.position(40); buf.putLong(shOff)
        buf.position(58); buf.putShort(64); buf.putShort(sections.toShort())
        buf.position(strOff.toInt()); buf.put(strtab); buf.put(dynsym); buf.put(dynamic)
        fun section(i: Int, type: Int, off: Long, size: Int, link: Int, entsize: Long) {
            val o = (shOff + i * 64).toInt()
            buf.putInt(o + 4, type); buf.putLong(o + 24, off); buf.putLong(o + 32, size.toLong())
            buf.putInt(o + 40, link); buf.putLong(o + 56, entsize)
        }
        section(1, 3, strOff, strtab.size, 0, 0)           // .dynstr
        section(2, 11, symOff, dynsym.size, 1, 24)          // .dynsym
        section(3, 6, dynOff, dynamic.size, 1, 16)          // .dynamic
        return File(Files.createTempDirectory("elf").toFile(), "lib.so").apply { writeBytes(buf.array()) }
    }

    @Test
    fun readsTheHeifDecoder() {
        val info = ElfInfo.read(elf64(183, "libheifdec.so", "heif_decode_image"))!!
        assertEquals(ElfInfo("arm64-v8a", "libheifdec.so", exportsHeifDecoder = true, exportsRustBridge = false), info)
    }

    @Test
    fun readsTheBridge() {
        val info = ElfInfo.read(elf64(62, "librust_bridge.so", "uniffi_rust_bridge_fn_func_send_message"))!!
        assertEquals("x86_64", info.abi)
        assertTrue(info.exportsRustBridge)
    }

    @Test
    fun notAnElf() {
        val f = File(Files.createTempDirectory("elf").toFile(), "x.so").apply { writeText("hello, this is not a library at all.......................................") }
        assertNull(ElfInfo.read(f))
    }
}
