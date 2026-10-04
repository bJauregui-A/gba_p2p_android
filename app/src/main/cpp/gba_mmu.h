#ifndef GBA_MMU_H
#define GBA_MMU_H

#include "gba_types.h"
#include <vector>
#include <array>

class GbaCore;

class GbaMmu {
public:
    GbaMmu(GbaCore* core);
    ~GbaMmu() = default;

    void reset();
    bool loadRom(const u8* data, size_t size);
    bool loadBios(const u8* data, size_t size);

    // Memory read/write methods
    u8  read8(u32 addr);
    u16 read16(u32 addr);
    u32 read32(u32 addr);

    void write8(u32 addr, u8 val);
    void write16(u32 addr, u16 val);
    void write32(u32 addr, u32 val);

    // Direct memory pointer access for PPU/DMA
    const u8* getVram() const { return m_vram.data(); }
    const u8* getPalette() const { return m_palette.data(); }
    const u8* getOam() const { return m_oam.data(); }

    // Save data access
    const std::vector<u8>& getSram() const { return m_sram; }
    void setSram(const u8* data, size_t size);
    bool isSramDirty() const { return m_sramDirty; }
    void clearSramDirty() { m_sramDirty = false; }

    // Keypad status
    void setKeypad(u16 keymask);
    u16 getKeypad() const { return m_keyinput; }

private:
    GbaCore* m_core;

    std::vector<u8> m_bios;
    std::vector<u8> m_ewram;  // 256 KB
    std::vector<u8> m_iwram;  // 32 KB
    std::vector<u8> m_io;     // 1 KB
    std::vector<u8> m_palette;// 1 KB
    std::vector<u8> m_vram;   // 96 KB
    std::vector<u8> m_oam;    // 1 KB
    std::vector<u8> m_rom;    // Up to 32 MB
    std::vector<u8> m_sram;   // 64 KB
    bool m_sramDirty;

    u16 m_keyinput;

    // I/O Register handlers
    u8  readIO(u32 offset);
    void writeIO(u32 offset, u8 val);
};

#endif // GBA_MMU_H
