#include "gba_ppu.h"
#include "gba_core.h"
#include "gba_mmu.h"

GbaPpu::GbaPpu(GbaCore* core)
    : m_core(core)
    , m_framebuffer(GBA_SCREEN_WIDTH * GBA_SCREEN_HEIGHT, 0xFF000000)
    , m_dispcnt(0)
    , m_dispstat(0)
    , m_vcount(0)
{
    for (int i = 0; i < 4; ++i) {
        m_bgcnt[i] = 0;
        m_bg_hofs[i] = 0;
        m_bg_vofs[i] = 0;
    }
    reset();
}

void GbaPpu::reset() {
    std::fill(m_framebuffer.begin(), m_framebuffer.end(), 0xFF000000);
    m_dispcnt = 0;
    m_dispstat = 0;
    m_vcount = 0;
    for (int i = 0; i < 4; ++i) {
        m_bgcnt[i] = 0;
        m_bg_hofs[i] = 0;
        m_bg_vofs[i] = 0;
    }
}

void GbaPpu::setVBlank(bool vblank) {
    if (vblank) {
        m_dispstat |= (1 << 0);
        if (m_dispstat & (1 << 3)) { // VBlank IRQ Enable
            m_core->triggerInterrupt(IRQ_VBLANK);
        }
    } else {
        m_dispstat &= ~(1 << 0);
    }
}

void GbaPpu::setHBlank(bool hblank) {
    if (hblank) {
        m_dispstat |= (1 << 1);
        if (m_dispstat & (1 << 4)) { // HBlank IRQ Enable
            m_core->triggerInterrupt(IRQ_HBLANK);
        }
    } else {
        m_dispstat &= ~(1 << 1);
    }
}

void GbaPpu::renderScanline(int line) {
    if (line < 0 || line >= GBA_SCREEN_HEIGHT) return;

    m_vcount = static_cast<u16>(line);

    // V-Counter Match check
    u8 vcountMatch = (m_dispstat >> 8) & 0xFF;
    if (vcountMatch == line) {
        m_dispstat |= (1 << 2);
        if (m_dispstat & (1 << 5)) { // VCount IRQ Enable
            m_core->triggerInterrupt(IRQ_VCOUNT);
        }
    } else {
        m_dispstat &= ~(1 << 2);
    }

    // Forced blank
    if (m_dispcnt & (1 << 7)) {
        u32* scanline = &m_framebuffer[line * GBA_SCREEN_WIDTH];
        std::fill(scanline, scanline + GBA_SCREEN_WIDTH, 0xFFFFFFFF);
        return;
    }

    int mode = m_dispcnt & 0x07;
    switch (mode) {
        case 0:
            renderMode0(line);
            break;
        case 3:
            renderMode3(line);
            break;
        case 4:
            renderMode4(line);
            break;
        default:
            renderMode0(line);
            break;
    }

    // Render sprites if enabled (Bit 12 in DISPCNT)
    if (m_dispcnt & (1 << 12)) {
        renderSprites(line);
    }
}

void GbaPpu::renderMode0(int line) {
    u32* scanline = &m_framebuffer[line * GBA_SCREEN_WIDTH];
    const u8* palette = m_core->getMmu()->getPalette();
    const u8* vram = m_core->getMmu()->getVram();

    // Backdrop color (Palette index 0)
    u16 backdrop = *reinterpret_cast<const u16*>(palette);
    u32 backdropArgb = bgr555ToArgb8888(backdrop);
    std::fill(scanline, scanline + GBA_SCREEN_WIDTH, backdropArgb);

    // Render BGs from lowest priority (3) to highest (0)
    for (int bg = 3; bg >= 0; --bg) {
        if (!(m_dispcnt & (1 << (8 + bg)))) continue; // Check BG enable bit

        u16 bgcnt = m_bgcnt[bg];
        u32 charBaseBlock = ((bgcnt >> 2) & 0x3) * 0x4000;
        u32 screenBaseBlock = ((bgcnt >> 8) & 0x1F) * 0x800;
        bool is256Color = (bgcnt & (1 << 7)) != 0;

        int hofs = m_bg_hofs[bg] & 0x1FF;
        int vofs = (m_bg_vofs[bg] + line) & 0x1FF;

        int tileY = (vofs / 8) % 32;
        int pixelY = vofs % 8;

        for (int x = 0; x < GBA_SCREEN_WIDTH; ++x) {
            int px = (hofs + x) & 0x1FF;
            int tileX = (px / 8) % 32;
            int pixelX = px % 8;

            u32 mapOffset = screenBaseBlock + (tileY * 32 + tileX) * 2;
            if (mapOffset + 1 >= 96 * 1024) continue;

            u16 tileEntry = *reinterpret_cast<const u16*>(&vram[mapOffset]);
            u16 tileIndex = tileEntry & 0x3FF;
            bool hFlip = (tileEntry & (1 << 10)) != 0;
            bool vFlip = (tileEntry & (1 << 11)) != 0;
            u8 palBank = (tileEntry >> 12) & 0xF;

            int drawPixelX = hFlip ? (7 - pixelX) : pixelX;
            int drawPixelY = vFlip ? (7 - pixelY) : pixelY;

            u8 colorIndex = 0;
            if (is256Color) {
                u32 tileDataOffset = charBaseBlock + tileIndex * 64 + drawPixelY * 8 + drawPixelX;
                if (tileDataOffset < 96 * 1024) {
                    colorIndex = vram[tileDataOffset];
                }
                if (colorIndex != 0) {
                    u16 c = *reinterpret_cast<const u16*>(&palette[colorIndex * 2]);
                    scanline[x] = bgr555ToArgb8888(c);
                }
            } else {
                u32 tileDataOffset = charBaseBlock + tileIndex * 32 + drawPixelY * 4 + (drawPixelX / 2);
                if (tileDataOffset < 96 * 1024) {
                    u8 byte = vram[tileDataOffset];
                    colorIndex = (drawPixelX & 1) ? (byte >> 4) : (byte & 0xF);
                }
                if (colorIndex != 0) {
                    u16 c = *reinterpret_cast<const u16*>(&palette[(palBank * 16 + colorIndex) * 2]);
                    scanline[x] = bgr555ToArgb8888(c);
                }
            }
        }
    }
}

void GbaPpu::renderMode3(int line) {
    u32* scanline = &m_framebuffer[line * GBA_SCREEN_WIDTH];
    const u8* vram = m_core->getMmu()->getVram();
    const u16* lineVram = reinterpret_cast<const u16*>(&vram[line * GBA_SCREEN_WIDTH * 2]);

    for (int x = 0; x < GBA_SCREEN_WIDTH; ++x) {
        scanline[x] = bgr555ToArgb8888(lineVram[x]);
    }
}

void GbaPpu::renderMode4(int line) {
    u32* scanline = &m_framebuffer[line * GBA_SCREEN_WIDTH];
    const u8* vram = m_core->getMmu()->getVram();
    const u8* palette = m_core->getMmu()->getPalette();

    u32 frameOffset = (m_dispcnt & (1 << 4)) ? 0xA000 : 0x0000;
    const u8* lineVram = &vram[frameOffset + line * GBA_SCREEN_WIDTH];

    for (int x = 0; x < GBA_SCREEN_WIDTH; ++x) {
        u8 colorIndex = lineVram[x];
        u16 c = *reinterpret_cast<const u16*>(&palette[colorIndex * 2]);
        scanline[x] = bgr555ToArgb8888(c);
    }
}

void GbaPpu::renderSprites(int line) {
    const u8* oam = m_core->getMmu()->getOam();
    const u8* vram = m_core->getMmu()->getVram();
    const u8* palette = m_core->getMmu()->getPalette() + 512; // OBJ Palette starts at +512 bytes
    u32* scanline = &m_framebuffer[line * GBA_SCREEN_WIDTH];

    for (int i = 127; i >= 0; --i) {
        const u16* obj = reinterpret_cast<const u16*>(&oam[i * 8]);
        u16 attr0 = obj[0];
        u16 attr1 = obj[1];
        u16 attr2 = obj[2];

        if ((attr0 & (1 << 9)) != 0 && (attr0 & (1 << 8)) == 0) {
            continue; // Disabled sprite
        }

        int y = attr0 & 0xFF;
        if (y >= 160) y -= 256;

        int sizeIndex = (attr1 >> 14) & 0x3;
        int shape = (attr0 >> 14) & 0x3;
        int width = 8, height = 8;
        if (shape == 0) { // Square
            int dims[] = { 8, 16, 32, 64 };
            width = height = dims[sizeIndex];
        }

        if (line < y || line >= y + height) continue;

        int x = attr1 & 0x1FF;
        if (x >= 240) x -= 512;

        int tileIndex = attr2 & 0x3FF;
        u8 palBank = (attr2 >> 12) & 0xF;
        bool is256 = (attr0 & (1 << 13)) != 0;

        int spriteY = line - y;
        for (int sx = 0; sx < width; ++sx) {
            int px = x + sx;
            if (px < 0 || px >= GBA_SCREEN_WIDTH) continue;

            u32 tileOffset = 0x10000 + (tileIndex * 32) + (spriteY * 4) + (sx / 2);
            if (tileOffset >= 96 * 1024) continue;

            u8 byte = vram[tileOffset];
            u8 colorIndex = (sx & 1) ? (byte >> 4) : (byte & 0xF);
            if (colorIndex != 0) {
                u16 c = *reinterpret_cast<const u16*>(&palette[(palBank * 16 + colorIndex) * 2]);
                scanline[px] = bgr555ToArgb8888(c);
            }
        }
    }
}

u8 GbaPpu::readReg(u32 offset) const {
    switch (offset) {
        case 0x00: return static_cast<u8>(m_dispcnt & 0xFF);
        case 0x01: return static_cast<u8>((m_dispcnt >> 8) & 0xFF);
        case 0x04: return static_cast<u8>(m_dispstat & 0xFF);
        case 0x05: return static_cast<u8>((m_dispstat >> 8) & 0xFF);
        case 0x06: return static_cast<u8>(m_vcount & 0xFF);
        case 0x07: return static_cast<u8>((m_vcount >> 8) & 0xFF);
        default: return 0;
    }
}

void GbaPpu::writeReg(u32 offset, u8 val) {
    switch (offset) {
        case 0x00: m_dispcnt = (m_dispcnt & 0xFF00) | val; break;
        case 0x01: m_dispcnt = (m_dispcnt & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x04: m_dispstat = (m_dispstat & 0xFF00) | (val & 0xFFF8); break;
        case 0x05: m_dispstat = (m_dispstat & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x08: m_bgcnt[0] = (m_bgcnt[0] & 0xFF00) | val; break;
        case 0x09: m_bgcnt[0] = (m_bgcnt[0] & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x0A: m_bgcnt[1] = (m_bgcnt[1] & 0xFF00) | val; break;
        case 0x0B: m_bgcnt[1] = (m_bgcnt[1] & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x0C: m_bgcnt[2] = (m_bgcnt[2] & 0xFF00) | val; break;
        case 0x0D: m_bgcnt[2] = (m_bgcnt[2] & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x0E: m_bgcnt[3] = (m_bgcnt[3] & 0xFF00) | val; break;
        case 0x0F: m_bgcnt[3] = (m_bgcnt[3] & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x10: m_bg_hofs[0] = (m_bg_hofs[0] & 0xFF00) | val; break;
        case 0x11: m_bg_hofs[0] = (m_bg_hofs[0] & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x12: m_bg_vofs[0] = (m_bg_vofs[0] & 0xFF00) | val; break;
        case 0x13: m_bg_vofs[0] = (m_bg_vofs[0] & 0x00FF) | (static_cast<u16>(val) << 8); break;
        default: break;
    }
}
