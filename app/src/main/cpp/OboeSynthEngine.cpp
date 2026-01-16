#include <jni.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>

#ifdef HAVE_FLUIDSYNTH
#include <fluidsynth.h>
#endif

namespace {

    class OboeSynthEngine final : public oboe::AudioStreamCallback {
    public:
        OboeSynthEngine() = default;

        ~OboeSynthEngine() override {
            close();
            shutdownFluidSynth();
        }

        bool start() {
            if (!stream_) {
                if (!openStream()) return false;
            }
            isPlaying_.store(true, std::memory_order_release);
            return stream_->requestStart() == oboe::Result::OK;
        }

        void stop() {
            isPlaying_.store(false, std::memory_order_release);
            if (stream_) stream_->requestStop();
        }

        void close() {
            stop();
            if (stream_) {
                stream_->close();
                stream_.reset();
            }
        }

#ifdef HAVE_FLUIDSYNTH
        // Keep these defaults close to what your current file used (but without any logcat).
        static constexpr double kFluidSynthMasterGain = 0.7;
        static constexpr int    kFluidSynthPolyphony  = 64;
        static constexpr int    kFluidSynthInterpolation = 1; // 1=linear

        static constexpr bool   kFluidSynthReverbActive = true;
        static constexpr double kFluidSynthReverbRoomSize = 0.45;
        static constexpr double kFluidSynthReverbDamp = 0.20;
        static constexpr double kFluidSynthReverbLevel = 0.35;
        static constexpr double kFluidSynthReverbWidth = 0.8;

        static constexpr bool   kFluidSynthChorusActive = true;
        static constexpr int    kFluidSynthChorusNr = 2;
        static constexpr double kFluidSynthChorusLevel = 0.30;
        static constexpr double kFluidSynthChorusDepth = 4.0;
        static constexpr double kFluidSynthChorusSpeed = 0.25;

        fluid_synth_t* getFluidSynth() { return fs_synth_; }
#else
        void* getFluidSynth() { return nullptr; }
#endif

        bool initFluidSynth() {
#ifndef HAVE_FLUIDSYNTH
            return false;
#else
            if (fs_initialized_) return true;

            if (!fs_settings_) {
                fs_settings_ = new_fluid_settings();
                if (!fs_settings_) return false;
            }

            // Match output sample rate.
            const double sr = sampleRate_.load(std::memory_order_relaxed);
            fluid_settings_setnum(fs_settings_, "synth.sample-rate", sr);

            // Core tuning.
            fluid_settings_setnum(fs_settings_, "synth.gain", kFluidSynthMasterGain);
            fluid_settings_setint(fs_settings_, "synth.polyphony", kFluidSynthPolyphony);
            fluid_settings_setint(fs_settings_, "synth.interpolation", kFluidSynthInterpolation);

            // Reverb.
            fluid_settings_setint(fs_settings_, "synth.reverb.active", kFluidSynthReverbActive ? 1 : 0);
            fluid_settings_setnum(fs_settings_, "synth.reverb.room-size", kFluidSynthReverbRoomSize);
            fluid_settings_setnum(fs_settings_, "synth.reverb.damp", kFluidSynthReverbDamp);
            fluid_settings_setnum(fs_settings_, "synth.reverb.level", kFluidSynthReverbLevel);
            fluid_settings_setnum(fs_settings_, "synth.reverb.width", kFluidSynthReverbWidth);

            // Chorus.
            fluid_settings_setint(fs_settings_, "synth.chorus.active", kFluidSynthChorusActive ? 1 : 0);
            fluid_settings_setint(fs_settings_, "synth.chorus.nr", kFluidSynthChorusNr);
            fluid_settings_setnum(fs_settings_, "synth.chorus.level", kFluidSynthChorusLevel);
            fluid_settings_setnum(fs_settings_, "synth.chorus.depth", kFluidSynthChorusDepth);
            fluid_settings_setnum(fs_settings_, "synth.chorus.speed", kFluidSynthChorusSpeed);

            fs_synth_ = new_fluid_synth(fs_settings_);
            if (!fs_synth_) {
                delete_fluid_settings(fs_settings_);
                fs_settings_ = nullptr;
                return false;
            }

            fs_initialized_ = true;
            loaded_soundfont_id_ = -1;
            return true;
#endif
        }

        void shutdownFluidSynth() {
#ifdef HAVE_FLUIDSYNTH
            if (fs_synth_) {
                if (loaded_soundfont_id_ >= 0) {
                    fluid_synth_sfunload(fs_synth_, loaded_soundfont_id_, 1);
                    loaded_soundfont_id_ = -1;
                }
                delete_fluid_synth(fs_synth_);
                fs_synth_ = nullptr;
            }
            if (fs_settings_) {
                delete_fluid_settings(fs_settings_);
                fs_settings_ = nullptr;
            }
            fs_initialized_ = false;
#endif
        }

        bool loadSoundFontFromPath(const std::string& path) {
#ifdef HAVE_FLUIDSYNTH
            if (!fs_initialized_ || !fs_synth_) return false;
            if (path.empty()) return false;

            if (loaded_soundfont_id_ >= 0) {
                fluid_synth_sfunload(fs_synth_, loaded_soundfont_id_, 1);
                loaded_soundfont_id_ = -1;
            }

            const int id = fluid_synth_sfload(fs_synth_, path.c_str(), 1);
            if (id < 0) return false;

            loaded_soundfont_id_ = id;
            // Safe default: program 0 on channel 0 (optional)
            fluid_synth_program_change(fs_synth_, 0, 0);
            return true;
#else
            (void)path;
        return false;
#endif
        }

        // ---------------------- Audio callback ----------------------
        oboe::DataCallbackResult onAudioReady(
                oboe::AudioStream* audioStream,
                void* audioData,
                int32_t numFrames
        ) override {
            float* out = static_cast<float*>(audioData);
            const int channels = audioStream->getChannelCount();

            if (!isPlaying_.load(std::memory_order_relaxed)) {
                std::memset(out, 0, sizeof(float) * static_cast<size_t>(numFrames) * static_cast<size_t>(channels));
                return oboe::DataCallbackResult::Continue;
            }

#ifdef HAVE_FLUIDSYNTH
            if (fs_synth_) {
                // Always render stereo float (LR interleaved)
                fluid_synth_write_float(fs_synth_, numFrames, out, 0, 2, out, 1, 2);
                return oboe::DataCallbackResult::Continue;
            }
#endif

            std::memset(out, 0, sizeof(float) * static_cast<size_t>(numFrames) * static_cast<size_t>(channels));
            return oboe::DataCallbackResult::Continue;
        }

        void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override {
            stream_.reset();
        }

    private:
        bool openStream() {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output);
            builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
            builder.setSharingMode(oboe::SharingMode::Exclusive);
            builder.setFormat(oboe::AudioFormat::Float);
            builder.setChannelCount(2);
            builder.setCallback(this);

            const oboe::Result r = builder.openStream(stream_);
            if (r != oboe::Result::OK) {
                stream_.reset();
                return false;
            }

            sampleRate_.store(stream_->getSampleRate(), std::memory_order_relaxed);
            return true;
        }

        std::shared_ptr<oboe::AudioStream> stream_;
        std::atomic<bool> isPlaying_{false};
        std::atomic<double> sampleRate_{48000.0};

#ifdef HAVE_FLUIDSYNTH
        bool fs_initialized_ = false;
        int loaded_soundfont_id_ = -1;
        fluid_settings_t* fs_settings_ = nullptr;
        fluid_synth_t* fs_synth_ = nullptr;
#endif
    };

// -------- JNI handle helper --------
    static inline OboeSynthEngine* fromHandle(jlong handle) {
        return reinterpret_cast<OboeSynthEngine*>(handle);
    }

} // namespace

// ============================================================================
// JNI exports (MUST match Kotlin calls)
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeCreate(JNIEnv*, jobject) {
    auto* engine = new OboeSynthEngine();
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeDelete(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->close();
    delete engine;
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeStart(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeStop(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeNoteOn(JNIEnv*, jobject, jlong handle, jint channel, jint note, jint velocity) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t* s = engine->getFluidSynth();
    if (!s) return;
    const int chan = std::clamp(static_cast<int>(channel), 0, 15);
    const int key  = static_cast<int>(note);
    const int vel  = std::clamp(static_cast<int>(velocity), 0, 127);
    fluid_synth_noteon(s, chan, key, vel);
#else
    (void)channel; (void)note; (void)velocity;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeNoteOff(JNIEnv*, jobject, jlong handle, jint channel, jint note) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t* s = engine->getFluidSynth();
    if (!s) return;
    const int chan = std::clamp(static_cast<int>(channel), 0, 15);
    const int key  = static_cast<int>(note);
    fluid_synth_noteoff(s, chan, key);
#else
    (void)channel; (void)note;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativePitchBend(JNIEnv*, jobject, jlong handle, jint channel, jint bend14) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t* s = engine->getFluidSynth();
    if (!s) return;
    const int chan = std::clamp(static_cast<int>(channel), 0, 15);
    // Preserve your current behavior: convert 0..16383 to -8192..8191
    int b = std::clamp(static_cast<int>(bend14), 0, 16383);
    int pb = b - 8192;
    if (pb < -8192) pb = -8192;
    if (pb > 8191) pb = 8191;
    fluid_synth_pitch_bend(s, chan, pb);
#else
    (void)channel; (void)bend14;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeChannelPressure(JNIEnv*, jobject, jlong handle, jint channel, jint pressure) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t* s = engine->getFluidSynth();
    if (!s) return;
    const int chan = std::clamp(static_cast<int>(channel), 0, 15);
    const int p    = std::clamp(static_cast<int>(pressure), 0, 127);
    fluid_synth_channel_pressure(s, chan, p);
#else
    (void)channel; (void)pressure;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeControlChange(JNIEnv*, jobject, jlong handle, jint channel, jint cc, jint value) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t* s = engine->getFluidSynth();
    if (!s) return;
    const int chan = std::clamp(static_cast<int>(channel), 0, 15);
    const int c    = std::clamp(static_cast<int>(cc), 0, 127);
    const int v    = std::clamp(static_cast<int>(value), 0, 127);
    fluid_synth_cc(s, chan, c, v);
#else
    (void)channel; (void)cc; (void)value;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeLoadSoundFont(JNIEnv* env, jobject, jlong handle, jstring path) {
    auto* engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    if (path == nullptr) return JNI_FALSE;

    const char* pathC = env->GetStringUTFChars(path, nullptr);
    if (!pathC) return JNI_FALSE;

    const bool ok = engine->loadSoundFontFromPath(std::string(pathC));
    env->ReleaseStringUTFChars(path, pathC);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeInitFluidSynth(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    return engine->initFluidSynth() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeShutdownFluidSynth(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    engine->shutdownFluidSynth();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeIsFluidSynthCompiled(JNIEnv*, jobject) {
#ifdef HAVE_FLUIDSYNTH
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

