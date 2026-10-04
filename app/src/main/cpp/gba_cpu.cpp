#include "gba_cpu.h"
#include "gba_core.h"
#include "gba_mmu.h"

#define FLAG_N (1 << 31)
#define FLAG_Z (1 << 30)
#define FLAG_C (1 << 29)
#define FLAG_V (1 << 28)
#define FLAG_I (1 << 7)
#define FLAG_F (1 << 6)
#define FLAG_T (1 << 5)

GbaCpu::GbaCpu(GbaCore* core)
    : m_core(core)
    , m_cpsr(0x0000001F) // System mode, ARM state
    , m_halted(false)
{
    reset();
}

void GbaCpu::reset() {
    for (int i = 0; i < 16; ++i) m_r[i] = 0;
    for (int i = 0; i < 6; ++i) m_spsr[i] = 0;

    // GBA Cartridge boot entry vector:
    // r13 (SP_sys) = 0x03007F00
    // r13 (SP_irq) = 0x03007FA0
    // r13 (SP_svc) = 0x03007FE0
    // r15 (PC) = 0x08000000 (Game Pak ROM start)
    m_r[13] = 0x03007F00;
    m_r_irq[0] = 0x03007FA0;
    m_r_svc[0] = 0x03007FE0;

    m_r[15] = 0x08000000;
    m_cpsr = 0x0000001F; // System mode, ARM state
    m_halted = false;
}

bool GbaCpu::checkCondition(u32 cond) const {
    bool n = (m_cpsr & FLAG_N) != 0;
    bool z = (m_cpsr & FLAG_Z) != 0;
    bool c = (m_cpsr & FLAG_C) != 0;
    bool v = (m_cpsr & FLAG_V) != 0;

    switch (cond) {
        case 0x0: return z;                   // EQ
        case 0x1: return !z;                  // NE
        case 0x2: return c;                   // CS / HS
        case 0x3: return !c;                  // CC / LO
        case 0x4: return n;                   // MI
        case 0x5: return !n;                  // PL
        case 0x6: return v;                   // VS
        case 0x7: return !v;                  // VC
        case 0x8: return c && !z;             // HI
        case 0x9: return !c || z;             // LS
        case 0xA: return n == v;              // GE
        case 0xB: return n != v;              // LT
        case 0xC: return !z && (n == v);      // GT
        case 0xD: return z || (n != v);       // LE
        case 0xE: return true;                // AL (Always)
        case 0xF: return false;               // NV (Never)
        default: return true;
    }
}

int GbaCpu::step() {
    if (m_halted) {
        return 2; // Low power sleep until IRQ
    }

    if (isThumb()) {
        u16 instr = m_core->getMmu()->read16(m_r[15]);
        m_r[15] += 2;
        return executeThumb(instr);
    } else {
        u32 instr = m_core->getMmu()->read32(m_r[15]);
        m_r[15] += 4;
        return executeArm(instr);
    }
}

int GbaCpu::executeArm(u32 instr) {
    u32 cond = (instr >> 28) & 0xF;
    if (cond != 0xE && !checkCondition(cond)) {
        return 1;
    }

    // Branch and Exchange (BX): 0001 0010 ... 0001 Rm
    if ((instr & 0x0FFFFFF0) == 0x012FFF10) {
        int rm = instr & 0xF;
        u32 target = m_r[rm];
        if (target & 1) {
            m_cpsr |= FLAG_T; // Switch to Thumb
            m_r[15] = target & ~1;
        } else {
            m_cpsr &= ~FLAG_T; // Stay in ARM
            m_r[15] = target & ~3;
        }
        return 3;
    }

    // Branch (B) / Branch with Link (BL): 101L offset
    if ((instr & 0x0E000000) == 0x0A000000) {
        return armBranch(instr);
    }

    // Software Interrupt (SWI): 1111 ...
    if ((instr & 0x0F000000) == 0x0F000000) {
        return armSwi(instr);
    }

    // Single Data Transfer (LDR/STR): 01...
    if ((instr & 0x0C000000) == 0x04000000) {
        return armLoadStore(instr);
    }

    // Block Data Transfer (LDM/STM): 100...
    if ((instr & 0x0E000000) == 0x08000000) {
        return armLoadStoreMultiple(instr);
    }

    // Data Processing: 00...
    if ((instr & 0x0C000000) == 0x00000000) {
        return armDataProcessing(instr);
    }

    return 2;
}

int GbaCpu::armBranch(u32 instr) {
    bool link = (instr & (1 << 24)) != 0;
    i32 offset = static_cast<i32>((instr & 0x00FFFFFF) << 8) >> 6; // Sign extend and * 4

    if (link) {
        m_r[14] = m_r[15] - 4; // LR = PC of next instruction
    }

    m_r[15] += offset + 4; // Pipeline offset
    return 3;
}

int GbaCpu::armLoadStore(u32 instr) {
    bool load = (instr & (1 << 20)) != 0;
    bool writeBack = (instr & (1 << 21)) != 0;
    bool byte = (instr & (1 << 22)) != 0;
    bool up = (instr & (1 << 23)) != 0;
    bool pre = (instr & (1 << 24)) != 0;
    bool regOffset = (instr & (1 << 25)) != 0;

    int rn = (instr >> 16) & 0xF;
    int rd = (instr >> 12) & 0xF;

    u32 offset = 0;
    if (regOffset) {
        int rm = instr & 0xF;
        offset = m_r[rm];
    } else {
        offset = instr & 0xFFF;
    }

    u32 base = m_r[rn];
    u32 addr = pre ? (up ? (base + offset) : (base - offset)) : base;

    if (load) {
        if (byte) {
            m_r[rd] = m_core->getMmu()->read8(addr);
        } else {
            m_r[rd] = m_core->getMmu()->read32(addr);
        }
    } else {
        if (byte) {
            m_core->getMmu()->write8(addr, static_cast<u8>(m_r[rd] & 0xFF));
        } else {
            m_core->getMmu()->write32(addr, m_r[rd]);
        }
    }

    if (!pre || writeBack) {
        m_r[rn] = up ? (base + offset) : (base - offset);
    }

    return 3;
}

int GbaCpu::armLoadStoreMultiple(u32 instr) {
    bool load = (instr & (1 << 20)) != 0;
    bool writeBack = (instr & (1 << 21)) != 0;
    bool up = (instr & (1 << 23)) != 0;
    bool pre = (instr & (1 << 24)) != 0;

    int rn = (instr >> 16) & 0xF;
    u16 regList = instr & 0xFFFF;
    u32 addr = m_r[rn];

    int count = 0;
    for (int i = 0; i < 16; ++i) {
        if (regList & (1 << i)) {
            count++;
            if (pre) addr += up ? 4 : -4;
            if (load) {
                m_r[i] = m_core->getMmu()->read32(addr);
            } else {
                m_core->getMmu()->write32(addr, m_r[i]);
            }
            if (!pre) addr += up ? 4 : -4;
        }
    }

    if (writeBack) {
        m_r[rn] = addr;
    }

    return 2 + count;
}

int GbaCpu::armDataProcessing(u32 instr) {
    bool immediate = (instr & (1 << 25)) != 0;
    int opcode = (instr >> 21) & 0xF;
    bool setFlags = (instr & (1 << 20)) != 0;
    int rn = (instr >> 16) & 0xF;
    int rd = (instr >> 12) & 0xF;

    u32 op2 = 0;
    if (immediate) {
        u32 imm = instr & 0xFF;
        u32 rot = ((instr >> 8) & 0xF) * 2;
        op2 = (rot == 0) ? imm : ((imm >> rot) | (imm << (32 - rot)));
    } else {
        int rm = instr & 0xF;
        op2 = m_r[rm];
    }

    u32 op1 = m_r[rn];
    u32 res = 0;

    switch (opcode) {
        case 0x0: res = op1 & op2; break;         // AND
        case 0x1: res = op1 ^ op2; break;         // EOR
        case 0x2: res = op1 - op2; break;         // SUB
        case 0x4: res = op1 + op2; break;         // ADD
        case 0x9: res = op1 ^ op2; break;         // TEQ
        case 0xA: res = op1 - op2; break;         // CMP
        case 0xC: res = op1 | op2; break;         // ORR
        case 0xD: res = op2; break;               // MOV
        case 0xE: res = op1 & ~op2; break;        // BIC
        case 0xF: res = ~op2; break;              // MVN
        default:  res = op1 + op2; break;
    }

    // Write result if not a comparison (CMP=10, CMN=11, TST=8, TEQ=9)
    if (opcode != 0x8 && opcode != 0x9 && opcode != 0xA && opcode != 0xB) {
        m_r[rd] = res;
    }

    if (setFlags) {
        if (res == 0) m_cpsr |= FLAG_Z; else m_cpsr &= ~FLAG_Z;
        if (res & (1 << 31)) m_cpsr |= FLAG_N; else m_cpsr &= ~FLAG_N;
    }

    return 1;
}

int GbaCpu::armSwi(u32 instr) {
    u32 comment = (instr >> 16) & 0xFF;
    // Enter Supervisor Mode
    m_r_svc[1] = m_r[15] - 4; // LR_svc
    m_r_svc[0] = m_r[13];     // Save SP
    m_spsr[2] = m_cpsr;       // SPSR_svc
    m_cpsr = (m_cpsr & ~0x3F) | 0x13 | FLAG_I; // SVC mode, IRQ disabled
    m_r[15] = 0x00000008; // SWI vector
    return 3;
}

int GbaCpu::executeThumb(u16 instr) {
    // Thumb: Format 1 Move shifted register
    if ((instr & 0xE000) == 0x0000) {
        return thumbShift(instr);
    }
    // Thumb: Format 2 Add/subtract
    if ((instr & 0xF800) == 0x1800) {
        return thumbAddSub(instr);
    }
    // Thumb: Format 3 Move/compare/add/subtract immediate
    if ((instr & 0xE000) == 0x2000) {
        int op = (instr >> 11) & 0x3;
        int rd = (instr >> 8) & 0x7;
        u32 imm = instr & 0xFF;
        if (op == 0) m_r[rd] = imm; // MOV
        else if (op == 1) { /* CMP */ }
        else if (op == 2) m_r[rd] += imm; // ADD
        else if (op == 3) m_r[rd] -= imm; // SUB
        return 1;
    }
    // Thumb: Format 5 Hi register operations / BX
    if ((instr & 0xFC00) == 0x4400) {
        return thumbHiRegister(instr);
    }
    // Thumb: Format 9/10 Load/Store with immediate offset
    if ((instr & 0xE000) == 0x6000) {
        return thumbLoadStore(instr);
    }
    // Thumb: Format 16/17 Branch (B)
    if ((instr & 0xF000) == 0xD000 || (instr & 0xF800) == 0xE000) {
        return thumbBranch(instr);
    }
    // Thumb: Format 19 Long branch with link (BL)
    if ((instr & 0xF000) == 0xF000) {
        u32 offset = instr & 0x7FF;
        if (!(instr & (1 << 11))) {
            m_r[14] = m_r[15] + (static_cast<i32>(offset << 21) >> 9);
        } else {
            u32 target = m_r[14] + (offset << 1);
            m_r[14] = (m_r[15] - 2) | 1;
            m_r[15] = target;
        }
        return 3;
    }

    return 1;
}

int GbaCpu::thumbShift(u16 instr) {
    int op = (instr >> 11) & 0x3;
    int offset = (instr >> 6) & 0x1F;
    int rs = (instr >> 3) & 0x7;
    int rd = instr & 0x7;

    u32 val = m_r[rs];
    if (op == 0) m_r[rd] = val << offset;       // LSL
    else if (op == 1) m_r[rd] = val >> offset;  // LSR
    else if (op == 2) m_r[rd] = static_cast<i32>(val) >> offset; // ASR

    return 1;
}

int GbaCpu::thumbAddSub(u16 instr) {
    bool imm = (instr & (1 << 10)) != 0;
    bool sub = (instr & (1 << 9)) != 0;
    int rn = (instr >> 6) & 0x7;
    int rs = (instr >> 3) & 0x7;
    int rd = instr & 0x7;

    u32 op2 = imm ? rn : m_r[rn];
    m_r[rd] = sub ? (m_r[rs] - op2) : (m_r[rs] + op2);
    return 1;
}

int GbaCpu::thumbHiRegister(u16 instr) {
    int op = (instr >> 8) & 0x3;
    int rs = ((instr >> 3) & 0x7) | ((instr >> 3) & 0x8);
    int rd = (instr & 0x7) | ((instr >> 4) & 0x8);

    if (op == 3) { // BX
        u32 target = m_r[rs];
        if (target & 1) {
            m_cpsr |= FLAG_T;
            m_r[15] = target & ~1;
        } else {
            m_cpsr &= ~FLAG_T;
            m_r[15] = target & ~3;
        }
        return 3;
    }
    if (op == 0) m_r[rd] += m_r[rs]; // ADD
    if (op == 1) { /* CMP */ }
    if (op == 2) m_r[rd] = m_r[rs];  // MOV
    return 1;
}

int GbaCpu::thumbLoadStore(u16 instr) {
    bool load = (instr & (1 << 11)) != 0;
    bool byte = (instr & (1 << 12)) != 0;
    int offset = ((instr >> 6) & 0x1F) * (byte ? 1 : 4);
    int rb = (instr >> 3) & 0x7;
    int rd = instr & 0x7;

    u32 addr = m_r[rb] + offset;
    if (load) {
        m_r[rd] = byte ? m_core->getMmu()->read8(addr) : m_core->getMmu()->read32(addr);
    } else {
        if (byte) m_core->getMmu()->write8(addr, static_cast<u8>(m_r[rd]));
        else m_core->getMmu()->write32(addr, m_r[rd]);
    }
    return 2;
}

int GbaCpu::thumbBranch(u16 instr) {
    if ((instr & 0xF000) == 0xD000) {
        // Conditional branch
        u32 cond = (instr >> 8) & 0xF;
        if (cond == 0xF) return 1; // SWI in format 17
        if (checkCondition(cond)) {
            i32 offset = static_cast<i8>(instr & 0xFF) * 2;
            m_r[15] += offset + 2;
            return 3;
        }
        return 1;
    }

    // Unconditional branch
    i32 offset = static_cast<i32>((instr & 0x7FF) << 21) >> 20;
    m_r[15] += offset + 2;
    return 3;
}

void GbaCpu::triggerInterrupt(InterruptFlag flag) {
    m_halted = false; // Wake up from sleep
    if (m_cpsr & FLAG_I) {
        return; // IRQ disabled globally in CPSR
    }

    // Switch to IRQ mode
    m_r_irq[1] = m_r[15] + (isThumb() ? 2 : 0); // LR_irq
    m_spsr[4] = m_cpsr;                         // SPSR_irq
    m_cpsr = (m_cpsr & ~0x3F) | 0x12 | FLAG_I;  // IRQ mode, ARM state, IRQs disabled
    m_r[15] = 0x00000018;                       // IRQ vector
}
