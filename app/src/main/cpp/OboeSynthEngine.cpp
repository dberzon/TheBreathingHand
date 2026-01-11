#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#ifdef HAVE_FLUIDSYNTH
#include <fluidsynth.h>
#endif
#include <atomic>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>
#include <string>
#include "Wavetable.h"

namespace {

constexpr int kMaxChannels = 8;
constexpr float kTwoPi = 6.283185307179586f;
constexpr float kMaxGain = 0.2f;
constexpr float kReleaseEpsilon = 0.0005f;

struct ChannelState {
    std::atomic<float> bendRatio{1.0f};
    std::atomic<float> aftertouch{0.0f};
    std::atomic<float> brightness{0.0f};

    // ADSR parameters (ms / linear): attack, decay, sustain level, release
    std::atomic<float> attackMs{5.0f};
    std::atomic<float> decayMs{50.0f};
    std::atomic<float> sustainLevel{0.8f};
    std::atomic<float> releaseMs{100.0f};

    // Filter cutoff in Hz (one-pole)
    std::atomic<float> filterCutoffHz{8000.0f};
};

struct Voice {
    std::atomic<float> baseFreq{0.0f};
    std::atomic<int> noteNumber{-1};
    std::atomic<float> targetAmplitude{0.0f};
    std::atomic<bool> active{false};
    float phase = 0.0f;
    float amplitude = 0.0f;
    // Voice-specific wavetable pointer (atomic for safe swap)
    std::atomic<const float*> wavetablePtr{nullptr};

    // Envelope state
    enum EnvStage { Idle = 0, Attack, Decay, Sustain, Release };
    EnvStage envStage = Idle;
    float envLevel = 0.0f; // [0..1]

    // Per-voice filter state
    float filtState = 0.0f;
    float cutoffSmoothed = 8000.0f;
};

class OboeSynthEngine : public oboe::AudioStreamCallback {
public:
    static constexpr int kWavetableSize = 2048;
    static constexpr int kNumNotes = 128;

    enum WaveformId { WAVE_SINE = 0, WAVE_TRIANGLE = 1, WAVE_SAW = 2, WAVE_SQUARE = 3 };
    static constexpr int kNumWavetables = 4;
    static constexpr int WAVE_CUSTOM_ID = 100;
    // Sample mapping mode (per-sample per-octave band tables)
    static constexpr int kSampleBandCount = 11; // e.g. -5..+5 octaves
    static constexpr int kSampleBandMid = (kSampleBandCount / 2);

    OboeSynthEngine() {
        generateWavetables();
        // Default waveform index
        activeWaveformId_.store(WAVE_SINE);
    }

    bool start() {
        if (!openStream()) {
            return false;
        }
        isPlaying_.store(true);
        if (stream_) {
            stream_->requestStart();
        }
        return true;
    }

    void stop() {
        isPlaying_.store(false);
        if (stream_) {
            stream_->requestStop();
        }
    }

    void close() {
        stop();
        if (stream_) {
            stream_->close();
            stream_.reset();
        }
    }

    void noteOn(int channel, int note, int velocity) {
#ifdef HAVE_FLUIDSYNTH
        if (!fs_synth_) return;
        int chan = std::clamp(channel, 0, 15);
        int vel = std::clamp(velocity, 0, 127);
        fluid_synth_noteon(fs_synth_, chan, note, vel);
#else
        (void)channel; (void)note; (void)velocity;
#endif
    }

    void noteOff(int channel, int note) {
#ifdef HAVE_FLUIDSYNTH
        if (!fs_synth_) return;
        int chan = std::clamp(channel, 0, 15);
        fluid_synth_noteoff(fs_synth_, chan, note);
#else
        (void)channel; (void)note;
#endif
    }

    // Expose synth pointer for JNI helpers (non-allocating accessor)
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t *getFluidSynth() { return fs_synth_; }
#else
    void *getFluidSynth() { return nullptr; }
#endif

    // Set currently active wavetable by index
    void setWaveform(int index) {
        if (index < 0 || index >= kNumWavetables) return;
        activeWaveformId_.store(index);
    }

    // Public wrapper for loading wavetable data (calls private loader)
    void loadWavetableFromBufferPublic(const uint8_t *data, size_t size) {
        loadWavetableFromBuffer(data, size);
    }

    // Load a SoundFont (SF2) from path. Must be called from a non-audio thread. Returns true on success.
    bool loadSoundFontFromPath(const std::string &path) {
#ifdef HAVE_FLUIDSYNTH
        if (!fs_initialized_ || !fs_synth_) return false;
        // Unload previous SF2 if one is loaded
        if (loaded_soundfont_id_ >= 0) {
            // unload and purge presets
            fluid_synth_sfunload(fs_synth_, loaded_soundfont_id_, 1);
            loaded_soundfont_id_ = -1;
        }
        int id = fluid_synth_sfload(fs_synth_, path.c_str(), 1);
        if (id < 0) return false;
        loaded_soundfont_id_ = id;
        // Optionally set default GM program 0 on channel 0
        // Note: This is optional and safe to call from non-audio thread
        fluid_synth_program_change(fs_synth_, 0, 0, 0);
        return true;
#else
        (void)path;
        return false;
#endif
    }

    void pitchBend(int channel, int bend14) {
#ifdef HAVE_FLUIDSYNTH
        if (!fs_synth_) return;
        int chan = channel;
        if (chan < 0) chan = 0;
        if (chan > 15) chan = 15;
        int pb = bend14 - 8192; // map 0..16383 to -8192..8191
        if (pb < -8192) pb = -8192;
        if (pb > 8191) pb = 8191;
        fluid_synth_pitch_bend(fs_synth_, chan, pb);
#else
        (void)channel; (void)bend14;
#endif
    }

    // Public wrapper for registering a mapped sample from a raw buffer
    void registerSampleFromBuffer(const uint8_t *data, size_t size, int rootNote, int loKey, int hiKey, const char *name) {
        std::string sname = name ? std::string(name) : std::string();
        registerSampleFromBufferPublic(data, size, rootNote, loKey, hiKey, sname);
    }

    // List names of loaded samples (thread-safe snapshot)
    std::vector<std::string> getLoadedSampleNames() {
        std::vector<std::string> names;
        auto list = std::atomic_load_explicit(&samplesList_, std::memory_order_acquire);
        if (!list) return names;
        for (size_t i = 0; i < list->size(); ++i) {
            auto &r = (*list)[i];
            if (r) {
                if (!r->name.empty()) names.push_back(r->name);
                else names.push_back(std::string("sample_") + std::to_string(i));
            }
        }
        return names;
    }

    void unloadSampleByIndex(int index) {
        auto oldList = std::atomic_load_explicit(&samplesList_, std::memory_order_acquire);
        if (!oldList) return;
        if (index < 0 || index >= static_cast<int>(oldList->size())) return;
        auto newList = std::make_shared<SampleList>(*oldList);
        newList->erase(newList->begin() + index);
        std::atomic_store_explicit(&samplesList_, newList, std::memory_order_release);
    }

    void channelPressure(int channel, int pressure) {
#ifdef HAVE_FLUIDSYNTH
        if (!fs_synth_) return;
        int chan = channel;
        if (chan < 0) chan = 0;
        if (chan > 15) chan = 15;
        fluid_synth_channel_pressure(fs_synth_, chan, pressure);
#else
        (void)channel; (void)pressure;
#endif
    }

    void controlChange(int channel, int cc, int value) {
#ifdef HAVE_FLUIDSYNTH
        if (!fs_synth_) return;
        int chan = channel;
        if (chan < 0) chan = 0;
        if (chan > 15) chan = 15;
        fluid_synth_cc(fs_synth_, chan, cc, value);
#else
        (void)channel; (void)cc; (void)value;
#endif
    }

    // Set filter cutoff (Hz) for a channel
    void setFilterCutoff(int channel, float cutoffHz) {
        if (channel < 0 || channel >= kMaxChannels) return;
        channels_[channel].filterCutoffHz.store(cutoffHz);
    }

    // Set ADSR envelope parameters (ms / sustain 0..1)
    void setEnvelopeParameters(int channel, float attackMs, float decayMs, float sustainLevel, float releaseMs) {
        if (channel < 0 || channel >= kMaxChannels) return;
        channels_[channel].attackMs.store(attackMs);
        channels_[channel].decayMs.store(decayMs);
        channels_[channel].sustainLevel.store(sustainLevel);
        channels_[channel].releaseMs.store(releaseMs);
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *oboeStream,
        void *audioData,
        int32_t numFrames
    ) override {
        auto *output = static_cast<float *>(audioData);
        const int channels = 2; // stereo interleaved
        if (!isPlaying_.load()) {
            // Zero interleaved buffer: numFrames * channels samples
            std::fill(output, output + static_cast<size_t>(numFrames) * channels, 0.0f);
            return oboe::DataCallbackResult::Continue;
        }

#ifdef HAVE_FLUIDSYNTH
        if (fs_synth_) {
            // Render directly into the interleaved buffer by using offsets and stride=2
            // left channel starts at offset 0 with increment 2, right starts at offset 1 with increment 2
            fluid_synth_write_float(fs_synth_, static_cast<int>(numFrames), output, 0, 2, output, 1, 2);
        } else {
            // No synth: silence
            std::fill(output, output + static_cast<size_t>(numFrames) * channels, 0.0f);
        }
#else
        // FluidSynth not available: silence
        std::fill(output, output + static_cast<size_t>(numFrames) * channels, 0.0f);
#endif
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result /*error*/) override {
        isPlaying_.store(false);
    }

private:
    void generateWavetables() {
        const float sr = static_cast<float>(sampleRate_.load());
        const float nyquist = sr * 0.5f;

        for (int w = 0; w < kNumWavetables; ++w) {
            for (int note = 0; note < kNumNotes; ++note) {
                auto &buf = wavetables_[w][note];
                buf.assign(kWavetableSize, 0.0f);
                const float baseFreq = midiNoteToHz(note);
                int maxHarm = static_cast<int>(std::floor(nyquist / baseFreq));
                if (maxHarm < 1) maxHarm = 1;

                if (w == WAVE_SINE) {
                    for (int i = 0; i < kWavetableSize; ++i) {
                        const float phase = static_cast<float>(i) / static_cast<float>(kWavetableSize);
                        buf[i] = std::sinf(kTwoPi * phase);
                    }
                } else if (w == WAVE_SAW) {
                    for (int n = 1; n <= maxHarm; ++n) {
                        const float amp = 1.0f / n;
                        for (int i = 0; i < kWavetableSize; ++i) {
                            const float phi = kTwoPi * (static_cast<float>(i) / kWavetableSize) * n;
                            buf[i] += amp * std::sinf(phi);
                        }
                    }
                } else if (w == WAVE_SQUARE) {
                    for (int k2 = 1; k2 <= maxHarm; k2 += 2) {
                        const float amp = 1.0f / k2;
                        for (int i = 0; i < kWavetableSize; ++i) {
                            const float phi = kTwoPi * (static_cast<float>(i) / kWavetableSize) * k2;
                            buf[i] += amp * std::sinf(phi);
                        }
                    }
                } else if (w == WAVE_TRIANGLE) {
                    for (int k2 = 1; k2 <= maxHarm; k2 += 2) {
                        const float amp = 1.0f / (k2 * k2);
                        const float sign = (((k2 - 1) / 2) % 2 == 0) ? 1.0f : -1.0f;
                        for (int i = 0; i < kWavetableSize; ++i) {
                            const float phi = kTwoPi * (static_cast<float>(i) / kWavetableSize) * k2;
                            buf[i] += sign * amp * std::sinf(phi);
                        }
                    }
                }

                // Normalize
                float maxv = 0.0f;
                for (int i = 0; i < kWavetableSize; ++i) {
                    if (std::fabs(buf[i]) > maxv) maxv = std::fabs(buf[i]);
                }
                if (maxv < 1e-6f) maxv = 1.0f;
                for (int i = 0; i < kWavetableSize; ++i) buf[i] /= maxv;

                tablePtrs_[w][note] = buf.data();
            }
        }
    }

    bool openStream() {
        if (stream_) return true;

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2) // stereo
            ->setCallback(this);

        auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK || !stream_) {
            return false;
        }

        sampleRate_.store(stream_->getSampleRate());
        // If FluidSynth is initialized, ensure its sample rate matches the stream
#ifdef HAVE_FLUIDSYNTH
        if (fs_synth_) {
            fluid_synth_set_sample_rate(fs_synth_, static_cast<int>(sampleRate_.load()));
        }
#endif
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst());
        return true;
    }

    static float midiNoteToHz(int note) {
        const int clamped = std::max(0, std::min(127, note));
        return 440.0f * std::pow(2.0f, (clamped - 69) / 12.0f);
    }

    std::shared_ptr<oboe::AudioStream> stream_;
    std::array<Voice, kMaxChannels> voices_{};
    std::array<ChannelState, kMaxChannels> channels_{};

    // Wavetable storage (owned): per waveform, per MIDI note (0..127)
    std::array<std::array<std::vector<float>, kNumNotes>, kNumWavetables> wavetables_{};
    std::array<std::array<const float*, kNumNotes>, kNumWavetables> tablePtrs_{};
    std::atomic<int> activeWaveformId_{0};

    // Custom wavetable (loaded from user-supplied .wav)
    std::shared_ptr<Wavetable> customWavetable_ = nullptr;

    // Sample mapping storage (atomic shared_ptr to vector of regions)
    struct SampleRegion {
        int rootNote = 60;
        int loKey = 0;
        int hiKey = 127;
        std::string name;
        // Per-band tables [band][kWavetableSize]
        std::array<std::vector<float>, kSampleBandCount> bandTables;
    };

    using SampleList = std::vector<std::shared_ptr<SampleRegion>>;
    std::shared_ptr<SampleList> samplesList_ = std::make_shared<SampleList>();

    std::atomic<double> sampleRate_{48000.0};
    std::atomic<bool> isPlaying_{false};

#ifdef HAVE_FLUIDSYNTH
    // FluidSynth state (initialized off the audio thread)
    fluid_settings_t *fs_settings_ = nullptr;
    fluid_synth_t *fs_synth_ = nullptr;
    bool fs_initialized_ = false;
    int loaded_soundfont_id_ = -1;

    // Initialize FluidSynth (must be called from non-audio thread). Pass empty path to skip loading a soundfont.
    bool initFluidSynth(const std::string &sf2Path) {
        if (fs_initialized_) return true;
        fs_settings_ = new_fluid_settings();
        if (!fs_settings_) return false;
        fluid_settings_setnum(fs_settings_, "synth.sample-rate", static_cast<double>(sampleRate_.load()));
        fs_synth_ = new_fluid_synth(fs_settings_);
        if (!fs_synth_) {
            delete_fluid_settings(fs_settings_);
            fs_settings_ = nullptr;
            return false;
        }
        if (!sf2Path.empty()) {
            loaded_soundfont_id_ = fluid_synth_sfload(fs_synth_, sf2Path.c_str(), 1);
            // loaded_soundfont_id_ < 0 indicates failure to load the SF2
        }
        fs_initialized_ = true;
        return true;
    }

    void shutdownFluidSynth() {
        if (fs_synth_) {
            delete_fluid_synth(fs_synth_);
            fs_synth_ = nullptr;
        }
        if (fs_settings_) {
            delete_fluid_settings(fs_settings_);
            fs_settings_ = nullptr;
        }
        fs_initialized_ = false;
        loaded_soundfont_id_ = -1;
    }
#else
    // When FluidSynth isn't available, provide no-op implementations and keep pointer accessor returning nullptr.
    bool initFluidSynth(const std::string & /*sf2Path*/) { return false; }
    void shutdownFluidSynth() {}
#endif

    // Helper: apply simple zero-phase lowpass to a table (non-realtime, called at load time)
    void lowpassTable(const float *in, float *out, int n, float cutoffHz) {
        if (cutoffHz <= 0.0f) {
            // copy
            for (int i = 0; i < n; ++i) out[i] = in[i];
            return;
        }
        const float sr = static_cast<float>(sampleRate_.load());
        const float x = std::exp(-2.0f * static_cast<float>(M_PI) * cutoffHz / sr);
        const float a = 1.0f - x;
        // forward pass
        float s = in[0];
        for (int i = 0; i < n; ++i) {
            s += a * (in[i] - s);
            out[i] = s;
        }
        // backward pass for approximate zero-phase
        s = out[n-1];
        for (int i = n-1; i >= 0; --i) {
            s += a * (out[i] - s);
            out[i] = s;
        }
    }

    // Load a user-supplied wavetable and switch engine to use it safely
    void setCustomWavetable(const std::shared_ptr<Wavetable> &wav) {
        std::atomic_store_explicit(&customWavetable_, wav, std::memory_order_release);
        activeWaveformId_.store(WAVE_CUSTOM_ID);
    }

    void loadWavetableFromBuffer(const uint8_t *data, size_t size) {
        if (!data || size == 0) return;
        __android_log_print(ANDROID_LOG_INFO, "OBoeEngine", "loadWavetableFromBuffer: size=%zu", size);
        auto wav = std::make_shared<Wavetable>(data, size);
        setCustomWavetable(wav);
        __android_log_print(ANDROID_LOG_INFO, "OBoeEngine", "Custom wavetable set");
    }

    // Public: register a sampled region (build per-octave band tables)
    void registerSampleFromBufferPublic(const uint8_t *data, size_t size, int rootNote, int loKey, int hiKey, const std::string &name) {
        if (!data || size == 0) {
            __android_log_print(ANDROID_LOG_WARN, "OBoeEngine", "registerSampleFromBufferPublic called with empty buffer");
            return;
        }
        __android_log_print(ANDROID_LOG_INFO, "OBoeEngine", "registerSampleFromBufferPublic: size=%zu root=%d lo=%d hi=%d name=%s", size, rootNote, loKey, hiKey, name.c_str());

        // Create base single-cycle wavetable from sample
        Wavetable base(data, size);
        if (!base.parsedOk()) {
            __android_log_print(ANDROID_LOG_WARN, "OBoeEngine", "registerSampleFromBufferPublic: failed to parse sample data, unsupported format or corrupt file");
            return;
        }
        std::vector<float> baseTable(Wavetable::kWavetableSize);
        std::memcpy(baseTable.data(), base.data(), sizeof(float) * Wavetable::kWavetableSize);

        // Prepare new SampleRegion
        auto region = std::make_shared<SampleRegion>();
        region->rootNote = rootNote;
        region->loKey = loKey;
        region->hiKey = hiKey;
        region->name = name;

        const float sr = static_cast<float>(sampleRate_.load());
        const float nyquist = sr * 0.5f;
        const float baseFreq = midiNoteToHz(rootNote);

        // For each band (octave), compute cutoff and lowpass
        std::vector<float> temp(Wavetable::kWavetableSize);
        for (int b = 0; b < kSampleBandCount; ++b) {
            const int bandOffset = b - kSampleBandMid; // number of octaves from root
            const int centerNote = rootNote + bandOffset * 12;
            const float centerFreq = midiNoteToHz(centerNote);
            const float T = (baseFreq > 1e-6f) ? (centerFreq / baseFreq) : 1.0f;
            float cutoff = nyquist / std::max(1e-6f, T);
            if (cutoff > nyquist) cutoff = nyquist;
            // Apply lowpass (zero-phase approx)
            lowpassTable(baseTable.data(), temp.data(), Wavetable::kWavetableSize, cutoff);
            region->bandTables[b].assign(temp.begin(), temp.end());
        }

        // Atomically replace samples list: copy then push_back and swap
        auto newList = std::make_shared<SampleList>(*samplesList_);
        newList->push_back(region);
        std::atomic_store_explicit(&samplesList_, newList, std::memory_order_release);

        auto names = getLoadedSampleNames();
        __android_log_print(ANDROID_LOG_INFO, "OBoeEngine", "After register: total samples=%zu", names.size());
    }
};

OboeSynthEngine *fromHandle(jlong handle) {
    return reinterpret_cast<OboeSynthEngine *>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeCreate(JNIEnv *, jobject) {
    auto *engine = new OboeSynthEngine();
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeDelete(JNIEnv *, jobject, jlong handle) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->close();
    delete engine;
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeLoadWavetableFromDirectBuffer(JNIEnv *env, jobject, jlong handle, jobject byteBuffer, jlong size) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    if (!byteBuffer || size <= 0) return;
    void *buf = env->GetDirectBufferAddress(byteBuffer);
    if (!buf) return;
    const uint8_t *data = reinterpret_cast<const uint8_t *>(buf);
    engine->loadWavetableFromBufferPublic(data, static_cast<size_t>(size));
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeRegisterSample(JNIEnv *env, jobject, jlong handle, jobject byteBuffer, jlong size, jint rootNote, jint loKey, jint hiKey, jstring name) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    if (!byteBuffer || size <= 0) return;
    void *buf = env->GetDirectBufferAddress(byteBuffer);
    if (!buf) return;
    const uint8_t *data = reinterpret_cast<const uint8_t *>(buf);
    const char *nameC = nullptr;
    if (name != nullptr) {
        nameC = env->GetStringUTFChars(name, nullptr);
    }
    engine->registerSampleFromBuffer(data, static_cast<size_t>(size), static_cast<int>(rootNote), static_cast<int>(loKey), static_cast<int>(hiKey), nameC);
    if (nameC) env->ReleaseStringUTFChars(name, nameC);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeGetLoadedSampleNames(JNIEnv *env, jobject, jlong handle) {
    auto *engine = fromHandle(handle);
    if (!engine) return nullptr;
    auto names = engine->getLoadedSampleNames();
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), strClass, nullptr);
    for (size_t i = 0; i < names.size(); ++i) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(names[i].c_str()));
    }
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeUnloadSample(JNIEnv *, jobject, jlong handle, jint index) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->unloadSampleByIndex(static_cast<int>(index));
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeStart(JNIEnv *, jobject, jlong handle) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeNoteOn(
    JNIEnv *, jobject, jlong handle, jint channel, jint note, jint velocity) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    // Non-allocating, non-blocking direct call to FluidSynth
    fluid_synth_t *s = engine->getFluidSynth();
    if (!s) return;
    int chan = std::clamp(static_cast<int>(channel), 0, 15);
    int key = static_cast<int>(note);
    int vel = std::clamp(static_cast<int>(velocity), 0, 127);
    fluid_synth_noteon(s, chan, key, vel);
#else
    (void)engine; (void)channel; (void)note; (void)velocity;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeNoteOff(
    JNIEnv *, jobject, jlong handle, jint channel, jint note) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t *s = engine->getFluidSynth();
    if (!s) return;
    int chan = std::clamp(static_cast<int>(channel), 0, 15);
    int key = static_cast<int>(note);
    fluid_synth_noteoff(s, chan, key);
#else
    (void)engine; (void)channel; (void)note;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativePitchBend(
    JNIEnv *, jobject, jlong handle, jint channel, jint bend14) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t *s = engine->getFluidSynth();
    if (!s) return;
    int chan = std::clamp(static_cast<int>(channel), 0, 15);
    // Clamp incoming 14-bit value (0..16383) and convert to FluidSynth range (-8192..8191)
    int b = std::clamp(static_cast<int>(bend14), 0, 16383);
    int pb = b - 8192;
    if (pb < -8192) pb = -8192;
    if (pb > 8191) pb = 8191;
    fluid_synth_pitch_bend(s, chan, pb);
#else
    (void)engine; (void)channel; (void)bend14;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeChannelPressure(
    JNIEnv *, jobject, jlong handle, jint channel, jint pressure) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t *s = engine->getFluidSynth();
    if (!s) return;
    int chan = std::clamp(static_cast<int>(channel), 0, 15);
    int p = std::clamp(static_cast<int>(pressure), 0, 127);
    fluid_synth_channel_pressure(s, chan, p);
#else
    (void)engine; (void)channel; (void)pressure;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeControlChange(
    JNIEnv *, jobject, jlong handle, jint channel, jint cc, jint value) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
#ifdef HAVE_FLUIDSYNTH
    fluid_synth_t *s = engine->getFluidSynth();
    if (!s) return;
    int chan = std::clamp(static_cast<int>(channel), 0, 15);
    int ctrl = std::clamp(static_cast<int>(cc), 0, 127);
    int val = std::clamp(static_cast<int>(value), 0, 127);
    fluid_synth_cc(s, chan, ctrl, val);
#else
    (void)engine; (void)channel; (void)cc; (void)value;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeSetFilterCutoff(
    JNIEnv *, jobject, jlong handle, jint channel, jfloat cutoffHz) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->setFilterCutoff(channel, cutoffHz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeSetEnvelope(
    JNIEnv *, jobject, jlong handle, jint channel, jfloat attackMs, jfloat decayMs, jfloat sustainLevel, jfloat releaseMs) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->setEnvelopeParameters(channel, attackMs, decayMs, sustainLevel, releaseMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeSetWaveform(
    JNIEnv *, jobject, jlong handle, jint index) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->setWaveform(index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeLoadSoundFont(
    JNIEnv *env, jobject, jlong handle, jstring path) {
    auto *engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    if (path == nullptr) return JNI_FALSE;
    const char *pathC = env->GetStringUTFChars(path, nullptr);
    if (!pathC) return JNI_FALSE;
    bool ok = engine->loadSoundFontFromPath(std::string(pathC));
    env->ReleaseStringUTFChars(path, pathC);
    return ok ? JNI_TRUE : JNI_FALSE;
}
