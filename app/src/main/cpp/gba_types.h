#ifndef GBA_TYPES_H
#define GBA_TYPES_H

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>
#include <string>
#include <memory>
#include <android/log.h>

#define LOG_TAG "GbaCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using u8  = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using u64 = uint64_t;

using i8  = int8_t;
using i16 = int16_t;
using i32 = int32_t;
using i64 = int64_t;

constexpr int GBA_SCREEN_WIDTH  = 240;
constexpr int GBA_SCREEN_HEIGHT = 160;
constexpr int GBA_TOTAL_LINES   = 228;
constexpr int GBA_CYCLES_PER_LINE = 1232;
constexpr int GBA_CYCLES_PER_FRAME = GBA_TOTAL_LINES * GBA_CYCLES_PER_LINE; // 280896 cycles (~59.73 Hz)

// Keypad bitmask (active low in hardware, standard high in our API)
enum GbaKey {
    KEY_A      = (1 << 0),
    KEY_B      = (1 << 1),
    KEY_SELECT = (1 << 2),
    KEY_START  = (1 << 3),
    KEY_RIGHT  = (1 << 4),
    KEY_LEFT   = (1 << 5),
    KEY_UP     = (1 << 6),
    KEY_DOWN   = (1 << 7),
    KEY_R      = (1 << 8),
    KEY_L      = (1 << 9)
};

// SIO Link Cable Roles
enum LinkRole {
    LINK_ROLE_STANDALONE = 0,
    LINK_ROLE_MASTER     = 1, // Player 1 (Console 0)
    LINK_ROLE_SLAVE      = 2  // Player 2 (Console 1)
};

// Interrupt flags
enum InterruptFlag {
    IRQ_VBLANK   = (1 << 0),
    IRQ_HBLANK   = (1 << 1),
    IRQ_VCOUNT   = (1 << 2),
    IRQ_TIMER0   = (1 << 3),
    IRQ_TIMER1   = (1 << 4),
    IRQ_TIMER2   = (1 << 5),
    IRQ_TIMER3   = (1 << 6),
    IRQ_SIO      = (1 << 7),
    IRQ_DMA0     = (1 << 8),
    IRQ_DMA1     = (1 << 9),
    IRQ_DMA2     = (1 << 10),
    IRQ_DMA3     = (1 << 11),
    IRQ_KEYPAD   = (1 << 12),
    IRQ_GAMEPAK  = (1 << 13)
};

#endif // GBA_TYPES_H
