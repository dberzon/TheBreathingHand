#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>

namespace {

constexpr int kMaxChannels = 16;
constexpr float kTwoPi = 6.283185307179586f;
constexpr float kMaxGain = 0.2f;
constexpr float kReleaseEpsilon = 0.0005f;

struct ChannelState {
    std::atomic<float> bendRatio{1.0f};
    std::atomic<float> aftertouch{0.0f};
    std::atomic<float> brightness{0.0f};
};

struct Voice {
    std::atomic<float> baseFreq{0.0f};
    std::atomic<float> targetAmplitude{0.0f};
    std::atomic<bool> active{false};
    float phase = 0.0f;
    float amplitude = 0.0f;
};

class OboeSynthEngine : public oboe::AudioStreamCallback {
public:
    OboeSynthEngine() = default;

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
        voice.targetAmplitude.store(vel * kMaxGain);
        voice.phase = 0.0f;
        voice.active.store(true);
    }

    void noteOff(int channel) {
        if (channel < 0 || channel >= kMaxChannels) return;
        voices_[channel].targetAmplitude.store(0.0f);
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

                const float freq = voice.baseFreq.load() * channels_[ch].bendRatio.load();
                voice.phase += freq / sampleRate;
                if (voice.phase >= 1.0f) {
                    voice.phase -= 1.0f;
                }

                const float sine = std::sinf(kTwoPi * voice.phase);
                const float saw = 2.0f * voice.phase - 1.0f;
                const float brightness = channels_[ch].brightness.load();
                const float osc = sine * (1.0f - brightness) + saw * brightness;
                const float aftertouch = 0.5f + 0.5f * channels_[ch].aftertouch.load();
                mix += osc * voice.amplitude * aftertouch;
            }
            output[i] = mix;
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result /*error*/) override {
        isPlaying_.store(false);
    }

private:
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
    std::atomic<double> sampleRate_{48000.0};
    std::atomic<bool> isPlaying_{false};
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
