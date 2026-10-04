#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <memory>
#include <algorithm>
#include <mutex>

#include "vbanext/libretro-common/include/libretro.h"

#define TAG "GbaJniBridge"
#undef LOGI
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
static jobject g_nativeListener = nullptr;
static jmethodID g_onLinkSendMethod = nullptr;

extern "C" {
void retro_apply_save_data(const uint8_t* data, size_t size);
void retro_get_save_data(uint8_t** out_ptr, size_t* out_size);

// Serial link emulation in the core (vbanext/src/gba.c)
void gba_link_set_role(int role);
void gba_link_set_connected(int connected);
void gba_link_receive(int kind, uint32_t data, uint32_t seq, int is_reply, uint32_t cycles);
int gba_link_idle_wait(int timeout_ms);

// Called by the core (emulation thread) to send a link packet to the peer
void gba_link_send(int kind, uint32_t data, uint32_t seq, int is_reply, uint32_t cycles) {
    if (!g_jvm || !g_nativeListener || !g_onLinkSendMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    }
    env->CallVoidMethod(g_nativeListener, g_onLinkSendMethod,
                        static_cast<jint>(kind), static_cast<jint>(data),
                        static_cast<jint>(seq), static_cast<jboolean>(is_reply != 0),
                        static_cast<jint>(cycles));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_jvm->DetachCurrentThread();
}
}

static uint32_t s_framebuffer[240 * 160];
static constexpr size_t AUDIO_BUFFER_CAPACITY = 8192;
static int16_t s_audioBuffer[AUDIO_BUFFER_CAPACITY];
static size_t s_audioSamples = 0;
static uint16_t s_keypad = 0;
static std::vector<uint8_t> s_romData;
// Serializes every call into the (non thread-safe) core: the emulation thread runs
// frames while the UI thread saves/loads states and battery data.
static std::mutex s_coreMutex;

static void video_refresh_callback(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;
    const uint16_t* src = reinterpret_cast<const uint16_t*>(data);
    for (unsigned y = 0; y < height && y < 160; ++y) {
        const uint16_t* line = src + (y * (pitch / 2));
        for (unsigned x = 0; x < width && x < 240; ++x) {
            uint16_t pixel = line[x];
            uint32_t r = ((pixel >> 11) & 0x1F) * 255 / 31;
            uint32_t g = ((pixel >> 5) & 0x3F) * 255 / 63;
            uint32_t b = (pixel & 0x1F) * 255 / 31;
            // Little-endian RGBA memory: Byte 0=R, Byte 1=G, Byte 2=B, Byte 3=0xFF
            s_framebuffer[y * 240 + x] = 0xFF000000 | (b << 16) | (g << 8) | r;
        }
    }
}

static size_t audio_sample_batch_callback(const int16_t* data, size_t frames) {
    if (!data || frames == 0) return frames;
    size_t samples = frames * 2;
    if (s_audioSamples + samples > AUDIO_BUFFER_CAPACITY) {
        samples = (AUDIO_BUFFER_CAPACITY > s_audioSamples) ? (AUDIO_BUFFER_CAPACITY - s_audioSamples) : 0;
    }
    if (samples > 0) {
        std::memcpy(s_audioBuffer + s_audioSamples, data, samples * sizeof(int16_t));
        s_audioSamples += samples;
    }
    return frames;
}

static void input_poll_callback() {}

static int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    // Map GBA keymask (bits: 0=A, 1=B, 2=SELECT, 3=START, 4=RIGHT, 5=LEFT, 6=UP, 7=DOWN, 8=R, 9=L)
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A:      return (s_keypad & (1 << 0)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_B:      return (s_keypad & (1 << 1)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_SELECT: return (s_keypad & (1 << 2)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_START:  return (s_keypad & (1 << 3)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_RIGHT:  return (s_keypad & (1 << 4)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_LEFT:   return (s_keypad & (1 << 5)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_UP:     return (s_keypad & (1 << 6)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_DOWN:   return (s_keypad & (1 << 7)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_R:      return (s_keypad & (1 << 8)) ? 1 : 0;
        case RETRO_DEVICE_ID_JOYPAD_L:      return (s_keypad & (1 << 9)) ? 1 : 0;
        default: return 0;
    }
}

static bool environment_callback(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const auto* fmt = reinterpret_cast<const enum retro_pixel_format*>(data);
            return (*fmt == RETRO_PIXEL_FORMAT_RGB565);
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            if (data) *reinterpret_cast<bool*>(data) = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        case RETRO_ENVIRONMENT_GET_VARIABLE:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        default:
            return false;
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeInit(JNIEnv* env, jobject thiz) {
    if (g_nativeListener) {
        env->DeleteGlobalRef(g_nativeListener);
        g_nativeListener = nullptr;
    }
    g_nativeListener = env->NewGlobalRef(thiz);

    jclass cls = env->GetObjectClass(thiz);
    g_onLinkSendMethod = env->GetMethodID(cls, "onLinkSend", "(IIIZI)V");
    gba_link_set_role(0);
    gba_link_set_connected(0);

    retro_set_environment(environment_callback);
    retro_set_video_refresh(video_refresh_callback);
    retro_set_audio_sample_batch(audio_sample_batch_callback);
    retro_set_input_poll(input_poll_callback);
    retro_set_input_state(input_state_callback);
    retro_init();
    LOGI("GbaNative: Initialized VBA-Next with HLE BIOS");
}

JNIEXPORT jboolean JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeLoadRom(JNIEnv* env, jobject thiz, jbyteArray romBytes) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    if (!romBytes) return JNI_FALSE;
    jsize len = env->GetArrayLength(romBytes);
    s_romData.resize(len);
    env->GetByteArrayRegion(romBytes, 0, len, reinterpret_cast<jbyte*>(s_romData.data()));

    struct retro_game_info info;
    info.path = "game.gba";
    info.data = s_romData.data();
    info.size = s_romData.size();
    info.meta = nullptr;

    bool success = retro_load_game(&info);
    LOGI("GbaNative: ROM loaded (%d bytes), success=%s", len, success ? "true" : "false");
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeReset(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    retro_reset();
}

JNIEXPORT jint JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeRunFrame(JNIEnv* env, jobject thiz,
                                                          jobject bitmap, jshortArray audioOut, jint keyMask) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    s_keypad = static_cast<uint16_t>(keyMask);
    s_audioSamples = 0;

    retro_run();

    if (bitmap) {
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
            std::memcpy(pixels, s_framebuffer, 240 * 160 * sizeof(uint32_t));
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }

    if (audioOut && s_audioSamples > 0) {
        jsize maxSamples = env->GetArrayLength(audioOut);
        jsize toCopy = std::min(static_cast<jsize>(s_audioSamples), maxSamples);
        env->SetShortArrayRegion(audioOut, 0, toCopy, reinterpret_cast<const jshort*>(s_audioBuffer));
        return toCopy;
    }

    return 0;
}

JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeSetKeypad(JNIEnv* env, jobject thiz, jint keyMask) {
    s_keypad = static_cast<uint16_t>(keyMask);
}

// Link calls don't take s_coreMutex: the emulation thread may be blocked inside a link
// transfer (holding it) waiting precisely for these.
JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeSetLinkRole(JNIEnv* env, jobject thiz, jint role) {
    gba_link_set_role(role);
}

JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeSetLinkConnected(JNIEnv* env, jobject thiz, jboolean connected) {
    gba_link_set_connected(connected ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeLinkIdle(JNIEnv* env, jobject thiz, jint timeoutMs) {
    return gba_link_idle_wait(timeoutMs);
}

JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeLinkReceive(JNIEnv* env, jobject thiz,
                                                             jint kind, jint data, jint seq, jboolean isReply,
                                                             jint cycles) {
    gba_link_receive(kind, static_cast<uint32_t>(data), static_cast<uint32_t>(seq), isReply ? 1 : 0,
                     static_cast<uint32_t>(cycles));
}

JNIEXPORT jbyteArray JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeGetSaveData(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    uint8_t* savePtr = nullptr;
    size_t saveSize = 0;
    retro_get_save_data(&savePtr, &saveSize);
    if (!savePtr || saveSize == 0) return nullptr;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(saveSize));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(saveSize), reinterpret_cast<const jbyte*>(savePtr));
    return arr;
}

JNIEXPORT void JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeLoadSaveData(JNIEnv* env, jobject thiz, jbyteArray sramBytes) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    if (!sramBytes) return;
    jsize len = env->GetArrayLength(sramBytes);
    if (len <= 0) return;

    std::vector<uint8_t> buffer(len);
    env->GetByteArrayRegion(sramBytes, 0, len, reinterpret_cast<jbyte*>(buffer.data()));

    retro_apply_save_data(buffer.data(), static_cast<size_t>(len));
    LOGI("GbaNative: Loaded %d bytes into save memory", len);
}

JNIEXPORT jbyteArray JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeSaveState(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    size_t size = retro_serialize_size();
    if (size == 0) return nullptr;

    std::vector<uint8_t> buffer(size);
    if (!retro_serialize(buffer.data(), size)) {
        LOGE("GbaNative: retro_serialize failed");
        return nullptr;
    }

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(buffer.data()));
    LOGI("GbaNative: Saved state (%zu bytes)", size);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_multiplayer_gbalink_core_GbaNative_nativeLoadState(JNIEnv* env, jobject thiz, jbyteArray stateBytes) {
    std::lock_guard<std::mutex> lock(s_coreMutex);
    if (!stateBytes) return JNI_FALSE;
    jsize len = env->GetArrayLength(stateBytes);
    if (len <= 0) return JNI_FALSE;

    std::vector<uint8_t> buffer(len);
    env->GetByteArrayRegion(stateBytes, 0, len, reinterpret_cast<jbyte*>(buffer.data()));

    bool ok = retro_unserialize(buffer.data(), static_cast<size_t>(len));
    LOGI("GbaNative: retro_unserialize success=%s (%d bytes)", ok ? "true" : "false", len);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
