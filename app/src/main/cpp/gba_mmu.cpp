#include "gba_mmu.h"
#include "gba_core.h"
#include "gba_link.h"
#include "gba_ppu.h"

GbaMmu::GbaMmu(GbaCore* core)
    : m_core(core)
    , m_ewram(256 * 1024, 0)
    , m_iwram(32 * 1024, 0)
    , m_io(1024, 0)
    , m_palette(1024, 0)
    , m_vram(96 * 1024, 0)
    , m_oam(1024, 0)
    , m_sram(64 * 1024, 0xFF)
    , m_sramDirty(false)
    , m_keyinput(0x03FF) // All buttons released (active low)
{
    reset();
}

void GbaMmu::reset() {
    std::fill(m_ewram.begin(), m_ewram.end(), 0);
    std::fill(m_iwram.begin(), m_iwram.end(), 0);
    std::fill(m_io.begin(), m_io.end(), 0);
    std::fill(m_palette.begin(), m_palette.end(), 0);
    std::fill(m_vram.begin(), m_vram.end(), 0);
    std::fill(m_oam.begin(), m_oam.end(), 0);
    m_keyinput = 0x03FF;
}

bool GbaMmu::loadRom(const u8* data, size_t size) {
    if (!data || size == 0) return false;
    m_rom.assign(data, data + size);
    LOGI("GbaMmu: ROM loaded, size: %zu bytes", size);
    return true;
}

bool GbaMmu::loadBios(const u8* data, size_t size) {
    if (!data || size == 0) return false;
    m_bios.assign(data, data + size);
    LOGI("GbaMmu: BIOS loaded, size: %zu bytes", size);
    return true;
}

void GbaMmu::setSram(const u8* data, size_t size) {
    if (!data || size == 0) return;
    size_t copySize = std::min(size, m_sram.size());
    std::memcpy(m_sram.data(), data, copySize);
    m_sramDirty = false;
    LOGI("GbaMmu: SRAM restored (%zu bytes)", copySize);
}

void GbaMmu::setKeypad(u16 keymask) {
    // Hardware KEYINPUT: 0 = Pressed, 1 = Released
    m_keyinput = (~keymask) & 0x03FF;
}

u8 GbaMmu::read8(u32 addr) {
    u32 region = (addr >> 24) & 0x0F;
    switch (region) {
        case 0x00: // BIOS
            if (addr < m_bios.size()) {
                return m_bios[addr];
            }
            return 0;

        case 0x02: // EWRAM (256 KB mirrored)
            return m_ewram[addr & 0x3FFFF];

        case 0x03: // IWRAM (32 KB mirrored)
            return m_iwram[addr & 0x7FFF];

        case 0x04: // I/O
            return readIO(addr & 0x3FF);

        case 0x05: // Palette RAM (1 KB mirrored)
            return m_palette[addr & 0x3FF];

        case 0x06: // VRAM (96 KB mirrored up to 128 KB)
        {
            u32 offset = addr & 0x1FFFF;
            if (offset >= 0x18000) offset -= 0x8000;
            return m_vram[offset];
        }

        case 0x07: // OAM (1 KB mirrored)
            return m_oam[addr & 0x3FF];

        case 0x08: // Game Pak ROM Waitstate 0
        case 0x09:
        case 0x0A: // Waitstate 1
        case 0x0B:
        case 0x0C: // Waitstate 2
        case 0x0D:
        {
            u32 offset = addr & 0x01FFFFFF;
            if (offset < m_rom.size()) {
                return m_rom[offset];
            }
            return 0xFF;
        }

        case 0x0E: // Game Pak SRAM
        case 0x0F:
        {
            u32 offset = addr & 0xFFFF;
            if (offset < m_sram.size()) {
                return m_sram[offset];
            }
            return 0xFF;
        }

        default:
            return 0;
    }
}

u16 GbaMmu::read16(u32 addr) {
    addr &= ~1; // 16-bit alignment
    u8 b0 = read8(addr);
    u8 b1 = read8(addr + 1);
    return static_cast<u16>(b0 | (b1 << 8));
}

u32 GbaMmu::read32(u32 addr) {
    addr &= ~3; // 32-bit alignment
    u16 w0 = read16(addr);
    u16 w1 = read16(addr + 2);
    return static_cast<u32>(w0 | (w1 << 16));
}

void GbaMmu::write8(u32 addr, u8 val) {
    u32 region = (addr >> 24) & 0x0F;
    switch (region) {
        case 0x02: // EWRAM
            m_ewram[addr & 0x3FFFF] = val;
            break;

        case 0x03: // IWRAM
            m_iwram[addr & 0x7FFF] = val;
            break;

        case 0x04: // I/O
            writeIO(addr & 0x3FF, val);
            break;

        case 0x05: // Palette RAM (8-bit writes write 16-bit duplicated on real hardware)
            m_palette[addr & 0x3FF] = val;
            break;

        case 0x06: // VRAM
        {
            u32 offset = addr & 0x1FFFF;
            if (offset >= 0x18000) offset -= 0x8000;
            m_vram[offset] = val;
            break;
        }

        case 0x07: // OAM
            m_oam[addr & 0x3FF] = val;
            break;

        case 0x0E: // Game Pak SRAM write
        case 0x0F:
        {
            u32 offset = addr & 0xFFFF;
            if (offset < m_sram.size()) {
                m_sram[offset] = val;
                m_sramDirty = true;
            }
            break;
        }

        default:
            break;
    }
}

void GbaMmu::write16(u32 addr, u16 val) {
    addr &= ~1;
    write8(addr, static_cast<u8>(val & 0xFF));
    write8(addr + 1, static_cast<u8>((val >> 8) & 0xFF));
}

void GbaMmu::write32(u32 addr, u32 val) {
    addr &= ~3;
    write16(addr, static_cast<u16>(val & 0xFFFF));
    write16(addr + 2, static_cast<u16>((val >> 16) & 0xFFFF));
}

u8 GbaMmu::readIO(u32 offset) {
    // SIO Link Cable Registers
    if (offset >= 0x120 && offset <= 0x134) {
        GbaLink* link = m_core->getLink();
        if (offset == 0x120) return static_cast<u8>(link->readSIOMULTI(0) & 0xFF);
        if (offset == 0x121) return static_cast<u8>((link->readSIOMULTI(0) >> 8) & 0xFF);
        if (offset == 0x122) return static_cast<u8>(link->readSIOMULTI(1) & 0xFF);
        if (offset == 0x123) return static_cast<u8>((link->readSIOMULTI(1) >> 8) & 0xFF);
        if (offset == 0x124) return static_cast<u8>(link->readSIOMULTI(2) & 0xFF);
        if (offset == 0x125) return static_cast<u8>((link->readSIOMULTI(2) >> 8) & 0xFF);
        if (offset == 0x126) return static_cast<u8>(link->readSIOMULTI(3) & 0xFF);
        if (offset == 0x127) return static_cast<u8>((link->readSIOMULTI(3) >> 8) & 0xFF);

        if (offset == 0x128) return static_cast<u8>(link->readSIOCNT() & 0xFF);
        if (offset == 0x129) return static_cast<u8>((link->readSIOCNT() >> 8) & 0xFF);

        if (offset == 0x12A) return static_cast<u8>(link->readSIOMLT_SEND() & 0xFF);
        if (offset == 0x12B) return static_cast<u8>((link->readSIOMLT_SEND() >> 8) & 0xFF);

        if (offset == 0x134) return static_cast<u8>(link->readRCNT() & 0xFF);
        if (offset == 0x135) return static_cast<u8>((link->readRCNT() >> 8) & 0xFF);
    }

    // Keypad register (KEYINPUT: 0x130)
    if (offset == 0x130) return static_cast<u8>(m_keyinput & 0xFF);
    if (offset == 0x131) return static_cast<u8>((m_keyinput >> 8) & 0xFF);

    // PPU Registers
    if (offset < 0x60) {
        return m_core->getPpu()->readReg(offset);
    }

    // Interrupt & Timers
    if (offset >= 0x100 && offset <= 0x110) {
        return m_core->readTimerReg(offset);
    }
    if (offset >= 0x200 && offset <= 0x208) {
        return m_core->readInterruptReg(offset);
    }

    if (offset < m_io.size()) {
        return m_io[offset];
    }
    return 0;
}

void GbaMmu::writeIO(u32 offset, u8 val) {
    if (offset < m_io.size()) {
        m_io[offset] = val;
    }

    // SIO Link Cable Registers
    if (offset >= 0x120 && offset <= 0x135) {
        GbaLink* link = m_core->getLink();
        if (offset == 0x128) {
            u16 cur = link->readSIOCNT();
            link->writeSIOCNT((cur & 0xFF00) | val);
        } else if (offset == 0x129) {
            u16 cur = link->readSIOCNT();
            link->writeSIOCNT((cur & 0x00FF) | (static_cast<u16>(val) << 8));
        } else if (offset == 0x12A) {
            u16 cur = link->readSIOMLT_SEND();
            link->writeSIOMLT_SEND((cur & 0xFF00) | val);
        } else if (offset == 0x12B) {
            u16 cur = link->readSIOMLT_SEND();
            link->writeSIOMLT_SEND((cur & 0x00FF) | (static_cast<u16>(val) << 8));
        } else if (offset == 0x134) {
            u16 cur = link->readRCNT();
            link->writeRCNT((cur & 0xFF00) | val);
        } else if (offset == 0x135) {
            u16 cur = link->readRCNT();
            link->writeRCNT((cur & 0x00FF) | (static_cast<u16>(val) << 8));
        }
        return;
    }

    // PPU Registers
    if (offset < 0x60) {
        m_core->getPpu()->writeReg(offset, val);
        return;
    }

    // DMA
    if (offset >= 0x0B0 && offset <= 0x0DF) {
        m_core->writeDmaReg(offset, val);
        return;
    }

    // Timers
    if (offset >= 0x100 && offset <= 0x10F) {
        m_core->writeTimerReg(offset, val);
        return;
    }

    // Sound
    if (offset >= 0x060 && offset <= 0x0A8) {
        m_core->writeSoundReg(offset, val);
        return;
    }

    // Interrupts (IME, IE, IF)
    if (offset >= 0x200 && offset <= 0x208) {
        m_core->writeInterruptReg(offset, val);
        return;
    }
}
