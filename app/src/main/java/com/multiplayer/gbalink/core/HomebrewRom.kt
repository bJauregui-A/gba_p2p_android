package com.multiplayer.gbalink.core

object HomebrewRom {
    /**
     * Generates a lightweight standalone GBA test ROM with Mode 3 display and SIO test pattern
     */
    fun createTestRom(): ByteArray {
        val rom = ByteArray(32768) // 32 KB test ROM

        // 1. ARM Branch instruction to entry point (0x080000C0): EA00002E (b 0x080000C0)
        rom[0] = 0x2E.toByte()
        rom[1] = 0x00.toByte()
        rom[2] = 0x00.toByte()
        rom[3] = 0xEA.toByte()

        // 2. Cartridge Header
        val title = "GBALINK P2P"
        for (i in title.indices) {
            rom[0xA0 + i] = title[i].code.toByte()
        }
        rom[0xAC] = 'A'.code.toByte()
        rom[0xAD] = 'G'.code.toByte()
        rom[0xAE] = 'B'.code.toByte()
        rom[0xAF] = 'E'.code.toByte()
        rom[0xB2] = 0x96.toByte() // 96h fixed value

        // 3. Entry code at 0xC0:
        // Set DISPCNT = 0x0403 (Mode 3, BG2 enabled)
        // Store 0x0403 to 0x04000000
        val code = intArrayOf(
            // ldr r0, =0x04000000
            0xE59F0050.toInt(),
            // ldr r1, =0x0403
            0xE59F1050.toInt(),
            // strh r1, [r0]
            0xE1C010B0.toInt(),

            // ldr r2, =0x06000000 (VRAM)
            0xE59F204C.toInt(),
            // ldr r3, =38400 (pixels)
            0xE59F304C.toInt(),
            // ldr r4, =0x001F (Red)
            0xE59F404C.toInt(),

            // loop: strh r4, [r2], #2
            0xE0C240B2.toInt(),
            // subs r3, r3, #1
            0xE2533001.toInt(),
            // bne loop
            0x1AFFFFFC.toInt(),

            // infinite loop: b .
            0xEAFFFFFE.toInt()
        )

        var pc = 0xC0
        for (instr in code) {
            rom[pc] = (instr and 0xFF).toByte()
            rom[pc + 1] = ((instr shr 8) and 0xFF).toByte()
            rom[pc + 2] = ((instr shr 16) and 0xFF).toByte()
            rom[pc + 3] = ((instr shr 24) and 0xFF).toByte()
            pc += 4
        }

        // Literals at the end of code
        fun write32(offset: Int, value: Int) {
            rom[offset] = (value and 0xFF).toByte()
            rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
            rom[offset + 2] = ((value shr 16) and 0xFF).toByte()
            rom[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        write32(0x118, 0x04000000) // DISPCNT address
        write32(0x11C, 0x00000403) // Mode 3 + BG2
        write32(0x120, 0x06000000) // VRAM
        write32(0x124, 38400)      // Pixel count
        write32(0x128, 0x00007C00) // Blue color in BGR555

        return rom
    }
}
