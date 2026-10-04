#ifndef GBA_CPU_H
#define GBA_CPU_H

#include "gba_types.h"
#include <array>

class GbaCore;

class GbaCpu {
public:
    GbaCpu(GbaCore* core);
    ~GbaCpu() = default;

    void reset();
    int step(); // Executes one instruction, returns CPU cycles consumed

    void triggerInterrupt(InterruptFlag flag);

    // Register inspection
    u32 getReg(int reg) const { return m_r[reg]; }
    void setReg(int reg, u32 val) { m_r[reg] = val; }
    u32 getCpsr() const { return m_cpsr; }
    bool isThumb() const { return (m_cpsr & (1 << 5)) != 0; }

private:
    GbaCore* m_core;

    u32 m_r[16];   // r0..r15 (r13=SP, r14=LR, r15=PC)
    u32 m_cpsr;    // Current Program Status Register (N, Z, C, V, I, F, T, Mode)
    u32 m_spsr[6]; // Saved Program Status Registers for privileged modes

    // Banked registers
    u32 m_r_user[7]; // r8..r14
    u32 m_r_fiq[7];
    u32 m_r_svc[2];
    u32 m_r_abt[2];
    u32 m_r_irq[2];
    u32 m_r_und[2];

    bool m_halted;

    // Execution methods
    int executeArm(u32 instr);
    int executeThumb(u16 instr);

    // Condition evaluation
    bool checkCondition(u32 cond) const;

    // ARM instruction decoders
    int armDataProcessing(u32 instr);
    int armBranch(u32 instr);
    int armLoadStore(u32 instr);
    int armLoadStoreMultiple(u32 instr);
    int armMultiply(u32 instr);
    int armSwi(u32 instr);

    // Thumb instruction decoders
    int thumbShift(u16 instr);
    int thumbAddSub(u16 instr);
    int thumbALU(u16 instr);
    int thumbHiRegister(u16 instr);
    int thumbLoadStore(u16 instr);
    int thumbBranch(u16 instr);
    int thumbSwi(u16 instr);

    // Flag helpers
    void setZNC(u32 result, bool carry, bool overflow);
};

#endif // GBA_CPU_H
