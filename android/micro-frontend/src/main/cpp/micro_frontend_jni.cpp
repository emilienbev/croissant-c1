// JNI wrapper for the TFLite Micro audio frontend, configured exactly as pymicro-features 2.0.2
// (src/micro_features.cpp), which made the features microWakeWord models are trained on.
#include <jni.h>

#include <cstdlib>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

namespace {

constexpr int kSampleRate = 16000;
constexpr int kStepMs = 10;
constexpr int kWindowMs = 30;
constexpr int kChannels = 40;
// pymicro-features scales the frontend's uint16 output by this before handing it on.
constexpr float kScale = 0.0390625f;

struct Handle {
    FrontendConfig cfg;
    FrontendState st;
};

void initConfig(FrontendConfig *cfg) {
    cfg->window.size_ms = kWindowMs;
    cfg->window.step_size_ms = kStepMs;
    cfg->filterbank.num_channels = kChannels;
    cfg->filterbank.lower_band_limit = 125.0f;
    cfg->filterbank.upper_band_limit = 7500.0f;
    cfg->noise_reduction.smoothing_bits = 10;
    cfg->noise_reduction.even_smoothing = 0.025f;
    cfg->noise_reduction.odd_smoothing = 0.06f;
    cfg->noise_reduction.min_signal_remaining = 0.05f;
    cfg->pcan_gain_control.enable_pcan = 1;
    cfg->pcan_gain_control.strength = 0.95f;
    cfg->pcan_gain_control.offset = 80.0f;
    cfg->pcan_gain_control.gain_bits = 21;
    cfg->log_scale.enable_log = 1;
    cfg->log_scale.scale_shift = 6;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_bleural_croissant_microfrontend_MicroFrontend_nativeCreate(JNIEnv *, jclass) {
    auto *h = static_cast<Handle *>(calloc(1, sizeof(Handle)));
    if (h == nullptr) return 0;
    initConfig(&h->cfg);
    if (!FrontendPopulateState(&h->cfg, &h->st, kSampleRate)) {
        free(h);
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bleural_croissant_microfrontend_MicroFrontend_nativeFree(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr) return;
    FrontendFreeStateContents(&h->st);
    free(h);
}

/**
 * Feeds up to `count` samples from `samples[offset]`. Writes a feature frame to `features`
 * (kChannels floats) when one is ready. Returns samplesRead << 8 | featuresWritten.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_bleural_croissant_microfrontend_MicroFrontend_nativeProcess(
        JNIEnv *env, jclass, jlong ptr, jshortArray samples, jint offset, jint count, jfloatArray features) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    jshort *in = env->GetShortArrayElements(samples, nullptr);
    size_t read = 0;
    FrontendOutput out = FrontendProcessSamples(&h->st, reinterpret_cast<int16_t *>(in) + offset,
                                                static_cast<size_t>(count), &read);
    env->ReleaseShortArrayElements(samples, in, JNI_ABORT);
    jint written = 0;
    if (out.size > 0) {
        float frame[kChannels];
        const size_t n = out.size < static_cast<size_t>(kChannels) ? out.size : kChannels;
        for (size_t i = 0; i < n; ++i) frame[i] = static_cast<float>(out.values[i]) * kScale;
        env->SetFloatArrayRegion(features, 0, static_cast<jsize>(n), frame);
        written = static_cast<jint>(n);
    }
    return static_cast<jint>(read << 8) | written;
}
