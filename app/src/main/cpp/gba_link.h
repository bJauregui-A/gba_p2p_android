#ifndef GBA_LINK_H
#define GBA_LINK_H

#include "gba_types.h"
#include <functional>
#include <mutex>
#include <queue>

class GbaCore;

class GbaLink {
public:
    GbaLink(GbaCore* core);
    ~GbaLink() = default;

    void reset();

    // SIO Register I/O
    u16 readSIOCNT() const;
    void writeSIOCNT(u16 val);

    u16 readSIOMLT_SEND() const;
    void writeSIOMLT_SEND(u16 val);

    u16 readSIOMULTI(int index) const;
    void writeSIOMULTI(int index, u16 val);

    u16 readRCNT() const;
    void writeRCNT(u16 val);

    u8 readSIODATA8() const;
    void writeSIODATA8(u8 val);

    u32 readSIODATA32() const;
    void writeSIODATA32(u32 val);

    // Network / P2P interface
    void setRole(LinkRole role);
    LinkRole getRole() const { return m_role; }

    // Callback when Master initiates a 16-bit Multi-Player transfer
    using SioTransferCallback = std::function<void(u16 masterData)>;
    void setTransferCallback(SioTransferCallback cb) { m_transferCallback = cb; }

    // Invoked when a P2P packet arrives from the remote console
    void onPacketReceived(u16 masterData, u16 slaveData);

    // Step cycle hook to handle timeouts or SIO clocking
    void step(u32 cycles);

private:
    GbaCore* m_core;
    LinkRole m_role;

    // Registers
    u16 m_siocnt;
    u16 m_siomlt_send;
    u16 m_siomulti[4];
    u16 m_rcnt;
    u8  m_siodata8;
    u32 m_siodata32;

    bool m_busy;
    u32  m_transferCyclesRemaining;

    std::mutex m_mutex;
    SioTransferCallback m_transferCallback;

    void finishTransfer(u16 masterData, u16 slaveData);
};

#endif // GBA_LINK_H
