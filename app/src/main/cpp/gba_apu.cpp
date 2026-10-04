#include "gba_apu.h"
#include "gba_core.h"

GbaApu::GbaApu(GbaCore* core)
    : m_core(core)
    , m_audioBuffer(8192, 0)
    , m_readIndex(0)
    , m_writeIndex(0)
    , m_cycleAccumulator(0)
    , m_fifoAHead(0), m_fifoATail(0), m_fifoACount(0)
    , m_fifoBHead(0), m_fifoBTail(0), m_fifoBCount(0)
    , m_soundcnt_l(0)
    , m_soundcnt_h(0)
    , m_soundcnt_x(0)
{
    reset();
}

void GbaApu::reset() {
    std::fill(m_audioBuffer.begin(), m_audioBuffer.end(), 0);
    m_readIndex = 0;
    m_writeIndex = 0;
    m_cycleAccumulator = 0;
    m_fifoAHead = m_fifoATail = m_fifoACount = 0;
    m_fifoBHead = m_fifoBTail = m_fifoBCount = 0;
    m_soundcnt_l = 0;
    m_soundcnt_h = 0;
    m_soundcnt_x = 0x0080; // Master Sound Enable
}

void GbaApu::pushFifoA(i8 sample) {
    if (m_fifoACount < 32) {
        m_fifoA[m_fifoATail] = sample;
        m_fifoATail = (m_fifoATail + 1) % 32;
        m_fifoACount++;
    }
}

void GbaApu::pushFifoB(i8 sample) {
    if (m_fifoBCount < 32) {
        m_fifoB[m_fifoBTail] = sample;
        m_fifoBTail = (m_fifoBTail + 1) % 32;
        m_fifoBCount++;
    }
}

void GbaApu::step(u32 cycles) {
    m_cycleAccumulator += cycles;
    while (m_cycleAccumulator >= CYCLES_PER_SAMPLE) {
        m_cycleAccumulator -= CYCLES_PER_SAMPLE;

        i16 sampleA = 0;
        if (m_fifoACount > 0) {
            sampleA = static_cast<i16>(m_fifoA[m_fifoAHead]) * 128;
            m_fifoAHead = (m_fifoAHead + 1) % 32;
            m_fifoACount--;
        }

        i16 sampleB = 0;
        if (m_fifoBCount > 0) {
            sampleB = static_cast<i16>(m_fifoB[m_fifoBHead]) * 128;
            m_fifoBHead = (m_fifoBHead + 1) % 32;
            m_fifoBCount--;
        }

        // Stereo mixing (Left & Right)
        i16 left = sampleA / 2 + sampleB / 2;
        i16 right = sampleA / 2 + sampleB / 2;

        size_t nextWrite = (m_writeIndex + 2) % m_audioBuffer.size();
        if (nextWrite != m_readIndex) {
            m_audioBuffer[m_writeIndex] = left;
            m_audioBuffer[m_writeIndex + 1] = right;
            m_writeIndex = nextWrite;
        }
    }
}

size_t GbaApu::getSamples(i16* output, size_t maxSamples) {
    size_t samplesCopied = 0;
    while (m_readIndex != m_writeIndex && samplesCopied < maxSamples) {
        output[samplesCopied++] = m_audioBuffer[m_readIndex];
        m_readIndex = (m_readIndex + 1) % m_audioBuffer.size();
    }
    return samplesCopied;
}

void GbaApu::writeReg(u32 offset, u8 val) {
    switch (offset) {
        case 0x80: m_soundcnt_l = (m_soundcnt_l & 0xFF00) | val; break;
        case 0x81: m_soundcnt_l = (m_soundcnt_l & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x82: m_soundcnt_h = (m_soundcnt_h & 0xFF00) | val; break;
        case 0x83: m_soundcnt_h = (m_soundcnt_h & 0x00FF) | (static_cast<u16>(val) << 8); break;
        case 0x84: m_soundcnt_x = (m_soundcnt_x & 0xFF00) | (val & 0x80); break;
        default: break;
    }
}

u8 GbaApu::readReg(u32 offset) const {
    switch (offset) {
        case 0x80: return static_cast<u8>(m_soundcnt_l & 0xFF);
        case 0x81: return static_cast<u8>((m_soundcnt_l >> 8) & 0xFF);
        case 0x82: return static_cast<u8>(m_soundcnt_h & 0xFF);
        case 0x83: return static_cast<u8>((m_soundcnt_h >> 8) & 0xFF);
        case 0x84: return static_cast<u8>(m_soundcnt_x & 0xFF);
        default: return 0;
    }
}
