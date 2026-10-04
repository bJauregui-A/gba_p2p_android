#ifndef GBA_APU_H
#define GBA_APU_H

#include "gba_types.h"
#include <vector>

class GbaCore;

class GbaApu {
public:
    GbaApu(GbaCore* core);
    ~GbaApu() = default;

    void reset();
    void step(u32 cycles);

    // Audio buffer access (Stereo 16-bit PCM, 44.1 kHz)
    size_t getSamples(i16* output, size_t maxSamples);

    void writeReg(u32 offset, u8 val);
    u8 readReg(u32 offset) const;

    // FIFO pushes from DMA
    void pushFifoA(i8 sample);
    void pushFifoB(i8 sample);

private:
    GbaCore* m_core;

    std::vector<i16> m_audioBuffer;
    size_t m_readIndex;
    size_t m_writeIndex;

    u32 m_cycleAccumulator;
    static constexpr u32 CYCLES_PER_SAMPLE = 16777216 / 44100; // ~380 cycles per sample

    // Simple PSG + Direct sound state
    i8 m_fifoA[32];
    int m_fifoAHead, m_fifoATail, m_fifoACount;
    i8 m_fifoB[32];
    int m_fifoBHead, m_fifoBTail, m_fifoBCount;

    u16 m_soundcnt_l;
    u16 m_soundcnt_h;
    u16 m_soundcnt_x;
};

#endif // GBA_APU_H
