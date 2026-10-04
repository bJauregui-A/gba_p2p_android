#include "gba_core.h"
#include "gba_cpu.h"
#include "gba_mmu.h"
#include "gba_ppu.h"
#include "gba_apu.h"
#include "gba_link.h"

GbaCore::GbaCore()
    : m_ie(0)
    , m_if(0)
    , m_ime(0)
{
    m_cpu  = std::make_unique<GbaCpu>(this);
    m_mmu  = std::make_unique<GbaMmu>(this);
    m_ppu  = std::make_unique<GbaPpu>(this);
    m_apu  = std::make_unique<GbaApu>(this);
    m_link = std::make_unique<GbaLink>(this);

    reset();
}

GbaCore::~GbaCore() = default;

void GbaCore::reset() {
    m_ie = 0;
    m_if = 0;
    m_ime = 0;

    for (int i = 0; i < 4; ++i) {
        m_timers[i] = { 0, 0, 0, 0 };
    }

    m_cpu->reset();
    m_mmu->reset();
    m_ppu->reset();
    m_apu->reset();
    m_link->reset();
}

bool GbaCore::loadRom(const u8* data, size_t size) {
    bool ok = m_mmu->loadRom(data, size);
    if (ok) {
        reset();
    }
    return ok;
}

bool GbaCore::loadBios(const u8* data, size_t size) {
    return m_mmu->loadBios(data, size);
}

void GbaCore::setKeypad(u16 keymask) {
    m_mmu->setKeypad(keymask);
}

void GbaCore::stepFrame() {
    for (int line = 0; line < GBA_TOTAL_LINES; ++line) {
        // Visible scanlines 0..159
        if (line < GBA_SCREEN_HEIGHT) {
            m_ppu->setHBlank(false);
            m_ppu->setVBlank(false);

            // Execute HDraw cycles (~960 cycles)
            int cyclesLeft = 960;
            while (cyclesLeft > 0) {
                int cycles = m_cpu->step();
                stepTimers(cycles);
                m_apu->step(cycles);
                m_link->step(cycles);
                cyclesLeft -= cycles;
            }

            // Render visible scanline
            m_ppu->renderScanline(line);

            // Enter HBlank (~272 cycles)
            m_ppu->setHBlank(true);
            cyclesLeft = 272;
            while (cyclesLeft > 0) {
                int cycles = m_cpu->step();
                stepTimers(cycles);
                m_apu->step(cycles);
                m_link->step(cycles);
                cyclesLeft -= cycles;
            }
        } else {
            // VBlank scanlines 160..227
            if (line == GBA_SCREEN_HEIGHT) {
                m_ppu->setVBlank(true);
            }

            int cyclesLeft = GBA_CYCLES_PER_LINE;
            while (cyclesLeft > 0) {
                int cycles = m_cpu->step();
                stepTimers(cycles);
                m_apu->step(cycles);
                m_link->step(cycles);
                cyclesLeft -= cycles;
            }
        }
    }
}

void GbaCore::stepTimers(u32 cycles) {
    static const int PRESCALER_CYCLES[] = { 1, 64, 256, 1024 };

    for (int i = 0; i < 4; ++i) {
        if (!(m_timers[i].control & (1 << 7))) continue; // Timer enable bit

        bool countUp = (m_timers[i].control & (1 << 2)) != 0;
        if (countUp && i > 0) {
            // Cascade mode: clocked when previous timer overflows
            continue;
        }

        int prescaler = PRESCALER_CYCLES[m_timers[i].control & 0x3];
        m_timers[i].prescalerCount += cycles;

        while (m_timers[i].prescalerCount >= static_cast<u32>(prescaler)) {
            m_timers[i].prescalerCount -= prescaler;
            m_timers[i].counter++;

            if (m_timers[i].counter == 0) { // Overflow (16-bit)
                m_timers[i].counter = m_timers[i].reload;

                // Check IRQ on overflow
                if (m_timers[i].control & (1 << 6)) {
                    triggerInterrupt(static_cast<InterruptFlag>(IRQ_TIMER0 << i));
                }

                // Check cascade timer i+1
                if (i < 3 && (m_timers[i + 1].control & (1 << 7)) && (m_timers[i + 1].control & (1 << 2))) {
                    m_timers[i + 1].counter++;
                    if (m_timers[i + 1].counter == 0) {
                        m_timers[i + 1].counter = m_timers[i + 1].reload;
                        if (m_timers[i + 1].control & (1 << 6)) {
                            triggerInterrupt(static_cast<InterruptFlag>(IRQ_TIMER0 << (i + 1)));
                        }
                    }
                }
            }
        }
    }
}

void GbaCore::triggerInterrupt(InterruptFlag flag) {
    m_if |= flag;
    checkInterrupts();
}

void GbaCore::checkInterrupts() {
    if (m_ime && (m_ie & m_if)) {
        m_cpu->triggerInterrupt(static_cast<InterruptFlag>(m_ie & m_if));
    }
}

u8 GbaCore::readInterruptReg(u32 offset) const {
    switch (offset) {
        case 0x200: return static_cast<u8>(m_ie & 0xFF);
        case 0x201: return static_cast<u8>((m_ie >> 8) & 0xFF);
        case 0x202: return static_cast<u8>(m_if & 0xFF);
        case 0x203: return static_cast<u8>((m_if >> 8) & 0xFF);
        case 0x208: return static_cast<u8>(m_ime & 0x01);
        default: return 0;
    }
}

void GbaCore::writeInterruptReg(u32 offset, u8 val) {
    switch (offset) {
        case 0x200: m_ie = (m_ie & 0xFF00) | val; break;
        case 0x201: m_ie = (m_ie & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x202: m_if &= ~(val); break; // Write 1 to clear
        case 0x203: m_if &= ~(static_cast<u16>(val) << 8); break;
        case 0x208: m_ime = val & 0x01; break;
        default: break;
    }
    checkInterrupts();
}

u8 GbaCore::readTimerReg(u32 offset) const {
    int timer = (offset - 0x100) / 4;
    int reg   = (offset - 0x100) % 4;
    if (timer < 0 || timer >= 4) return 0;

    switch (reg) {
        case 0: return static_cast<u8>(m_timers[timer].counter & 0xFF);
        case 1: return static_cast<u8>((m_timers[timer].counter >> 8) & 0xFF);
        case 2: return static_cast<u8>(m_timers[timer].control & 0xFF);
        case 3: return static_cast<u8>((m_timers[timer].control >> 8) & 0xFF);
        default: return 0;
    }
}

void GbaCore::writeTimerReg(u32 offset, u8 val) {
    int timer = (offset - 0x100) / 4;
    int reg   = (offset - 0x100) % 4;
    if (timer < 0 || timer >= 4) return;

    switch (reg) {
        case 0: m_timers[timer].reload = (m_timers[timer].reload & 0xFF00) | val; break;
        case 1: m_timers[timer].reload = (m_timers[timer].reload & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 2:
        {
            u16 oldCtrl = m_timers[timer].control;
            m_timers[timer].control = (m_timers[timer].control & 0xFF00) | val;
            if (!(oldCtrl & (1 << 7)) && (val & (1 << 7))) {
                // Timer enabled: reload counter
                m_timers[timer].counter = m_timers[timer].reload;
                m_timers[timer].prescalerCount = 0;
            }
            break;
        }
        case 3:
            m_timers[timer].control = (m_timers[timer].control & 0x00FF) | (static_cast<u16>(val) << 8);
            break;
        default: break;
    }
}

void GbaCore::writeDmaReg(u32 offset, u8 val) {
    // Basic DMA registers acknowledgment
    int channel = (offset - 0x0B0) / 12;
    if (channel < 0 || channel >= 4) return;
}

void GbaCore::writeSoundReg(u32 offset, u8 val) {
    m_apu->writeReg(offset, val);
}

u8 GbaCore::readSoundReg(u32 offset) const {
    return m_apu->readReg(offset);
}
