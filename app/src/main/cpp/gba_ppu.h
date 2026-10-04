#ifndef GBA_PPU_H
#define GBA_PPU_H

#include "gba_types.h"
#include <vector>

class GbaCore;

class GbaPpu {
public:
    GbaPpu(GbaCore* core);
    ~GbaPpu() = default;

    void reset();
    void renderScanline(int line);

    // Framebuffer access (ARGB8888 for Android Bitmap / SurfaceView)
    const u32* getFramebuffer() const { return m_framebuffer.data(); }

    u8 readReg(u32 offset) const;
    void writeReg(u32 offset, u8 val);

    u16 getDispstat() const { return m_dispstat; }
    void setVBlank(bool vblank);
    void setHBlank(bool hblank);

private:
    GbaCore* m_core;

    std::vector<u32> m_framebuffer; // 240 * 160 pixels (ARGB8888)

    // Registers
    u16 m_dispcnt;   // 0x00
    u16 m_dispstat;  // 0x04
    u16 m_vcount;    // 0x06
    u16 m_bgcnt[4];  // 0x08, 0x0A, 0x0C, 0x0E
    u16 m_bg_hofs[4];// 0x10, 0x14, 0x18, 0x1C
    u16 m_bg_vofs[4];// 0x12, 0x16, 0x1A, 0x1E

    // Internal line renderers
    void renderMode0(int line);
    void renderMode3(int line); // 240x160 16-bit direct color
    void renderMode4(int line); // 240x160 8-bit paletted
    void renderSprites(int line);

    // Color conversion GBA BGR555 -> 32-bit ARGB8888
    static inline u32 bgr555ToArgb8888(u16 color) {
        u32 r = (color & 0x001F);
        u32 g = (color & 0x03E0) >> 5;
        u32 b = (color & 0x7C00) >> 10;

        r = (r << 3) | (r >> 2);
        g = (g << 3) | (g >> 2);
        b = (b << 3) | (b >> 2);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
};

#endif // GBA_PPU_H
