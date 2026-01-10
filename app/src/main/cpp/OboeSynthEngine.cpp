#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>
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
        if (channel < 0 || channel >= kMaxChannels) return;
        const float freq = midiNoteToHz(note);
        const float vel = static_cast<float>(velocity) / 127.0f;
        auto &voice = voices_[channel];
        voice.baseFreq.store(freq);
        voice.noteNumber.store(note);
        voice.targetAmplitude.store(vel * kMaxGain);
        voice.phase = 0.0f;
        voice.active.store(true);

        // Reset envelope for attack
        voice.envStage = Voice::Attack;
        voice.envLevel = 0.0f;

        // Initialize filter smoothing from channel target
        voice.cutoffSmoothed = channels_[channel].filterCutoffHz.load();
        voice.filtState = 0.0f;
    }

    void noteOff(int channel) {
        if (channel < 0 || channel >= kMaxChannels) return;
        // Enter release stage
        voices_[channel].envStage = Voice::Release;
        voices_[channel].targetAmplitude.store(0.0f);
    }

    // Set currently active wavetable by index
    void setWaveform(int index) {
        if (index < 0 || index >= kNumWavetables) return;
        activeWaveformId_.store(index);
    }

    // Public wrapper for loading wavetable data (calls private loader)
    void loadWavetableFromBufferPublic(const uint8_t *data, size_t size) {
        loadWavetableFromBuffer(data, size);
    }

    void pitchBend(int channel, int bend14) {
        if (channel < 0 || channel >= kMaxChannels) return;
        const int centered = bend14 - 8192;
        const float semitones = static_cast<float>(centered) / 8192.0f * 2.0f;
        const float ratio = std::pow(2.0f, semitones / 12.0f);
        channels_[channel].bendRatio.store(ratio);
    }

    void channelPressure(int channel, int pressure) {
        if (channel < 0 || channel >= kMaxChannels) return;
        channels_[channel].aftertouch.store(static_cast<float>(pressure) / 127.0f);
    }

    void controlChange(int channel, int cc, int value) {
        if (channel < 0 || channel >= kMaxChannels) return;
        if (cc != 74) return;
        channels_[channel].brightness.store(static_cast<float>(value) / 127.0f);
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
        if (!isPlaying_.load()) {
            std::fill(output, output + numFrames, 0.0f);
            return oboe::DataCallbackResult::Continue;
        }

        const float sampleRate = static_cast<float>(sampleRate_.load());
        const float attackCoeff = 1.0f - std::exp(-1.0f / (sampleRate * 0.005f));
        const float releaseCoeff = 1.0f - std::exp(-1.0f / (sampleRate * 0.02f));

        for (int32_t i = 0; i < numFrames; ++i) {
            float mix = 0.0f;
            for (int ch = 0; ch < kMaxChannels; ++ch) {
                auto &voice = voices_[ch];
                if (!voice.active.load()) {
                    continue;
                }
                const float target = voice.targetAmplitude.load();
                const float coeff = target > voice.amplitude ? attackCoeff : releaseCoeff;
                voice.amplitude += (target - voice.amplitude) * coeff;

                if (target <= 0.0f && voice.amplitude < kReleaseEpsilon) {
                    voice.amplitude = 0.0f;
                    voice.active.store(false);
                    continue;
                }

                const float pitchedFreq = voice.baseFreq.load() * channels_[ch].bendRatio.load();
                voice.phase += pitchedFreq / sampleRate;
                if (voice.phase >= 1.0f) {
                    voice.phase -= 1.0f;
                }

                // Wavetable lookup (linear interpolation)
                // Choose table based on active waveform and note number (per-MIDI-note)
                int waveId = activeWaveformId_.load();
                float tableSample = 0.0f;
                if (waveId == WAVE_CUSTOM_ID) {
                    auto wav = std::atomic_load_explicit(&customWavetable_, std::memory_order_acquire);
                    if (wav) {
                        tableSample = wav->render(voice.phase);
                    } else {
                        tableSample = std::sinf(kTwoPi * voice.phase);
                    }
                } else {
                    int note = voice.noteNumber.load();
                    if (note < 0 || note >= kNumNotes) note = 69; // default to A4 if unknown
                    const float* table = tablePtrs_[waveId][note];
                    if (table) {
                        const float idx = voice.phase * static_cast<float>(kWavetableSize);
                        int i0 = static_cast<int>(idx) % kWavetableSize;
                        int i1 = (i0 + 1) % kWavetableSize;
                        const float frac = idx - static_cast<float>(i0);
                        tableSample = table[i0] * (1.0f - frac) + table[i1] * frac;
                    } else {
                        tableSample = std::sinf(kTwoPi * voice.phase);
                    }
                }
                const float aftertouch = 0.5f + 0.5f * channels_[ch].aftertouch.load();

                // --- Envelope processing (per-voice) ---
                const float attackMs = channels_[ch].attackMs.load();
                const float decayMs = channels_[ch].decayMs.load();
                const float sustain = channels_[ch].sustainLevel.load();
                const float releaseMs = channels_[ch].releaseMs.load();

                const float attackSec = std::max(0.001f, attackMs * 0.001f);
                const float decaySec = std::max(0.001f, decayMs * 0.001f);
                const float releaseSec = std::max(0.001f, releaseMs * 0.001f);

                const float attackCoeff = 1.0f - std::exp(-1.0f / (sampleRate * attackSec));
                const float decayCoeff = 1.0f - std::exp(-1.0f / (sampleRate * decaySec));
                const float releaseCoeff = 1.0f - std::exp(-1.0f / (sampleRate * releaseSec));

                // Advance envelope stage
                if (voice.envStage == Voice::Attack) {
                    voice.envLevel += (1.0f - voice.envLevel) * attackCoeff;
                    if (voice.envLevel >= 0.999f) {
                        voice.envLevel = 1.0f;
                        voice.envStage = Voice::Decay;
                    }
                } else if (voice.envStage == Voice::Decay) {
                    voice.envLevel += (sustain - voice.envLevel) * decayCoeff;
                    if (std::fabs(voice.envLevel - sustain) < 0.001f) {
                        voice.envLevel = sustain;
                        voice.envStage = Voice::Sustain;
                    }
                } else if (voice.envStage == Voice::Sustain) {
                    voice.envLevel = sustain;
                } else if (voice.envStage == Voice::Release) {
                    voice.envLevel += (0.0f - voice.envLevel) * releaseCoeff;
                    if (voice.envLevel <= 1e-5f) {
                        voice.envLevel = 0.0f;
                        voice.active.store(false);
                        continue;
                    }
                }

                // Apply envelope to oscillator amplitude
                float rawSample = tableSample * voice.amplitude * voice.envLevel * aftertouch;

                // --- One-pole lowpass per-voice ---
                const float cutoffTarget = channels_[ch].filterCutoffHz.load();
                // Smooth cutoff a little to avoid zipper noise
                voice.cutoffSmoothed += 0.001f * (cutoffTarget - voice.cutoffSmoothed);
                const float fc = std::max(20.0f, voice.cutoffSmoothed);
                const float alpha = 1.0f - std::exp(-2.0f * kTwoPi * fc / sampleRate);
                voice.filtState += alpha * (rawSample - voice.filtState);
                float filtered = voice.filtState;

                mix += filtered;
            }
            output[i] = mix;
        }
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
            ->setChannelCount(1)
            ->setCallback(this);

        auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK || !stream_) {
            return false;
        }

        sampleRate_.store(stream_->getSampleRate());
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

    std::atomic<double> sampleRate_{48000.0};
    std::atomic<bool> isPlaying_{false};

    // Load a user-supplied wavetable and switch engine to use it safely
    void setCustomWavetable(const std::shared_ptr<Wavetable> &wav) {
        std::atomic_store_explicit(&customWavetable_, wav, std::memory_order_release);
        activeWaveformId_.store(WAVE_CUSTOM_ID);
    }

    void loadWavetableFromBuffer(const uint8_t *data, size_t size) {
        if (!data || size == 0) return;
        auto wav = std::make_shared<Wavetable>(data, size);
        setCustomWavetable(wav);
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
    engine->noteOn(channel, note, velocity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeNoteOff(
    JNIEnv *, jobject, jlong handle, jint channel, jint note) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    (void)note;
    engine->noteOff(channel);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativePitchBend(
    JNIEnv *, jobject, jlong handle, jint channel, jint bend14) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->pitchBend(channel, bend14);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeChannelPressure(
    JNIEnv *, jobject, jlong handle, jint channel, jint pressure) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->channelPressure(channel, pressure);
}

extern "C" JNIEXPORT void JNICALL
Java_com_breathinghand_audio_OboeSynthesizer_nativeControlChange(
    JNIEnv *, jobject, jlong handle, jint channel, jint cc, jint value) {
    auto *engine = fromHandle(handle);
    if (!engine) return;
    engine->controlChange(channel, cc, value);
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
