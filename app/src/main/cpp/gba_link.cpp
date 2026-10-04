#include "gba_link.h"

GbaLink::GbaLink(GbaCore* core)
    : m_core(core)
    , m_role(LINK_ROLE_STANDALONE)
    , m_siocnt(0)
    , m_siomlt_send(0)
    , m_rcnt(0)
    , m_siodata8(0)
    , m_siodata32(0)
    , m_busy(false)
    , m_transferCyclesRemaining(0)
{
    reset();
}

void GbaLink::reset() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_siocnt = 0;
    m_siomlt_send = 0xFFFF;
    for (int i = 0; i < 4; ++i) {
        m_siomulti[i] = 0xFFFF;
    }
    m_rcnt = 0;
    m_siodata8 = 0;
    m_siodata32 = 0;
    m_busy = false;
    m_transferCyclesRemaining = 0;
}

void GbaLink::setRole(LinkRole role) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_role = role;
    LOGI("GbaLink: Role set to %d (%s)", role,
         role == LINK_ROLE_MASTER ? "Master (J1)" :
         role == LINK_ROLE_SLAVE ? "Slave (J2)" : "Standalone");
}

u16 GbaLink::readSIOCNT() const {
    u16 val = m_siocnt & 0x7083; // Preserved control bits

    // Bits 4-5: ID (0 = Master, 1 = Slave)
    if (m_role == LINK_ROLE_SLAVE) {
        val |= (1 << 4); // ID = 1
        val |= (1 << 2); // SI terminal = 1 (Slave connected)
    } else if (m_role == LINK_ROLE_MASTER) {
        val |= (0 << 4); // ID = 0 (Master)
        val |= (1 << 3); // SD terminal
    }

    if (m_busy) {
        val |= (1 << 7); // Busy / Start bit active
    }

    return val;
}

void GbaLink::writeSIOCNT(u16 val) {
    std::lock_guard<std::mutex> lock(m_mutex);

    u16 oldVal = m_siocnt;
    m_siocnt = val;

    // Check Start / Busy Bit (Bit 7)
    bool startTransfer = (val & (1 << 7)) != 0;
    int mode = (val >> 12) & 0x3; // Bits 12-13: 0=Normal, 1=Multi-Player, 2=UART, 3=JOYBUS

    if (startTransfer && !m_busy) {
        m_busy = true;

        if (m_role == LINK_ROLE_STANDALONE) {
            // In standalone mode, complete transfer quickly with loopback
            // (Takes ~288 cycles for 115200 baud)
            m_transferCyclesRemaining = 288;
        } else if (m_role == LINK_ROLE_MASTER) {
            // Master initiates SIO Multi-player transfer:
            // Send local 16-bit word (from SIOMLT_SEND or SIODATA32) to peer via P2P callback
            u16 localData = (mode == 1) ? m_siomlt_send : static_cast<u16>(m_siodata32 & 0xFFFF);
            LOGD("GbaLink: Master initiating SIO transfer, data=0x%04X", localData);

            if (m_transferCallback) {
                m_transferCallback(localData);
            }
            // Keep busy = true until slave responds via onPacketReceived()
        } else if (m_role == LINK_ROLE_SLAVE) {
            // Slaves do not clock the transfer themselves; they wait for Master's clock
            LOGD("GbaLink: Slave ready for transfer, data=0x%04X", m_siomlt_send);
        }
    }
}

u16 GbaLink::readSIOMLT_SEND() const {
    return m_siomlt_send;
}

void GbaLink::writeSIOMLT_SEND(u16 val) {
    m_siomlt_send = val;
}

u16 GbaLink::readSIOMULTI(int index) const {
    if (index >= 0 && index < 4) {
        return m_siomulti[index];
    }
    return 0xFFFF;
}

void GbaLink::writeSIOMULTI(int index, u16 val) {
    if (index >= 0 && index < 4) {
        m_siomulti[index] = val;
    }
}

u16 GbaLink::readRCNT() const {
    return m_rcnt;
}

void GbaLink::writeRCNT(u16 val) {
    m_rcnt = val;
}

u8 GbaLink::readSIODATA8() const {
    return m_siodata8;
}

void GbaLink::writeSIODATA8(u8 val) {
    m_siodata8 = val;
}

u32 GbaLink::readSIODATA32() const {
    return m_siodata32;
}

void GbaLink::writeSIODATA32(u32 val) {
    m_siodata32 = val;
}

void GbaLink::onPacketReceived(u16 masterData, u16 slaveData) {
    std::lock_guard<std::mutex> lock(m_mutex);
    LOGD("GbaLink: SIO packet received - Master=0x%04X, Slave=0x%04X", masterData, slaveData);
    finishTransfer(masterData, slaveData);
}

void GbaLink::finishTransfer(u16 masterData, u16 slaveData) {
    // Populate multi-player registers
    m_siomulti[0] = masterData; // Player 1 (Master)
    m_siomulti[1] = slaveData;  // Player 2 (Slave 1)
    m_siomulti[2] = 0xFFFF;     // Player 3 (Not connected)
    m_siomulti[3] = 0xFFFF;     // Player 4 (Not connected)

    // Clear Busy Bit (Bit 7 = 0)
    m_siocnt &= ~(1 << 7);
    m_busy = false;

    // Check IRQ Enable (Bit 14)
    if (m_siocnt & (1 << 14)) {
        LOGD("GbaLink: SIO transfer complete");
    }
}

void GbaLink::step(u32 cycles) {
    if (m_transferCyclesRemaining > 0) {
        if (cycles >= m_transferCyclesRemaining) {
            m_transferCyclesRemaining = 0;
            // Standalone loopback completion
            finishTransfer(m_siomlt_send, 0xFFFF);
        } else {
            m_transferCyclesRemaining -= cycles;
        }
    }
}
