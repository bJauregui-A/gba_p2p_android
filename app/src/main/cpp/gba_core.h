#ifndef GBA_CORE_H
#define GBA_CORE_H

#include "gba_types.h"
#include <memory>
#include <string>

class GbaCpu;
class GbaMmu;
class GbaPpu;
class GbaApu;
class GbaLink;

class GbaCore {
public:
    GbaCore();
    ~GbaCore();

    void reset();
    bool loadRom(const u8* data, size_t size);
    bool loadBios(const u8* data, size_t size);

    // Frame execution (runs one full video frame: 228 scanlines ~280896 cycles)
    void stepFrame();

    // Keypad status
    void setKeypad(u16 keymask);

    // Interrupt handling
    void triggerInterrupt(InterruptFlag flag);
    u8 readInterruptReg(u32 offset) const;
    void writeInterruptReg(u32 offset, u8 val);

    // Timers
    u8 readTimerReg(u32 offset) const;
    void writeTimerReg(u32 offset, u8 val);

    // DMA
    void writeDmaReg(u32 offset, u8 val);

    // Sound
    void writeSoundReg(u32 offset, u8 val);
    u8 readSoundReg(u32 offset) const;

    // Subsystems
    GbaCpu*  getCpu()  { return m_cpu.get(); }
    GbaMmu*  getMmu()  { return m_mmu.get(); }
    GbaPpu*  getPpu()  { return m_ppu.get(); }
    GbaApu*  getApu()  { return m_apu.get(); }
    GbaLink* getLink() { return m_link.get(); }

private:
    std::unique_ptr<GbaCpu>  m_cpu;
    std::unique_ptr<GbaMmu>  m_mmu;
    std::unique_ptr<GbaPpu>  m_ppu;
    std::unique_ptr<GbaApu>  m_apu;
    std::unique_ptr<GbaLink> m_link;

    // Interrupt registers
    u16 m_ie;   // 0x200 Interrupt Enable
    u16 m_if;   // 0x202 Interrupt Request / Acknowledge
    u16 m_ime;  // 0x208 Interrupt Master Enable

    // Timers 0..3
    struct Timer {
        u16 reload;
        u16 counter;
        u16 control;
        u32 prescalerCount;
    } m_timers[4];

    void stepTimers(u32 cycles);
    void checkInterrupts();
};

#endif // GBA_CORE_H
