#include "Wavetable.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// Little-endian helpers
static uint16_t read_u16_le(const uint8_t *p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}
static uint32_t read_u32_le(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

Wavetable::Wavetable(const uint8_t *data, size_t size) {
    table_.assign(kWavetableSize, 0.0f);

    std::vector<float> samples;
    if (!parseWav(data, size, samples) || samples.empty()) {
        // Fallback to sine
        makeSineTable();
        parsedOk_ = false;
        return;
    }

    parsedOk_ = true;
    resampleToTable(samples, table_);

    // Normalize to avoid clipping
    float maxv = 0.0f;
    for (float v : table_) maxv = std::max(maxv, std::fabs(v));
    if (maxv < 1e-6f) maxv = 1.0f;
    for (float &v : table_) v /= maxv;
}

void Wavetable::makeSineTable() {
    for (int i = 0; i < kWavetableSize; ++i) {
        const float phase = static_cast<float>(i) / static_cast<float>(kWavetableSize);
        table_[i] = std::sin(2.0f * static_cast<float>(M_PI) * phase);
    }
}

bool Wavetable::parseWav(const uint8_t *data, size_t size, std::vector<float> &out) {
    // Minimal RIFF/WAVE parser. Accepts PCM 16-bit or IEEE float 32.
    if (!data || size < 12) return false;
    if (std::memcmp(data, "RIFF", 4) != 0) return false;
    if (std::memcmp(data + 8, "WAVE", 4) != 0) return false;

    size_t pos = 12;
    uint16_t audioFormat = 0;
    uint16_t numChannels = 1;
    uint32_t sampleRate = 44100;
    uint16_t bitsPerSample = 16;
    const uint8_t *dataStart = nullptr;
    size_t dataBytes = 0;

    while (pos + 8 <= size) {
        const uint8_t *hdr = data + pos;
        char chunkId[5];
        std::memcpy(chunkId, hdr, 4);
        chunkId[4] = '\0';
        uint32_t chunkSize = read_u32_le(hdr + 4);
        pos += 8;

        if (pos + chunkSize > size) return false; // malformed

        if (std::memcmp(hdr, "fmt ", 4) == 0) {
            // parse fmt chunk
            if (chunkSize < 16) return false;
            audioFormat = read_u16_le(data + pos);
            numChannels = read_u16_le(data + pos + 2);
            sampleRate = read_u32_le(data + pos + 4);
            bitsPerSample = read_u16_le(data + pos + 14);
            // ignore any extra fmt bytes
        } else if (std::memcmp(hdr, "data", 4) == 0) {
            dataStart = data + pos;
            dataBytes = chunkSize;
            break; // done
        }

        pos += chunkSize;
    }

    if (!dataStart || dataBytes == 0) return false;

    const size_t bytesPerSampleFrame = (bitsPerSample / 8) * numChannels;
    if (bytesPerSampleFrame == 0) return false;

    const size_t frameCount = dataBytes / bytesPerSampleFrame;
    out.resize(frameCount);

    // Convert/Downmix to mono float
    if (audioFormat == 1 && bitsPerSample == 16) {
        // PCM 16-bit
        const int16_t *src = reinterpret_cast<const int16_t *>(dataStart);
        for (size_t i = 0; i < frameCount; ++i) {
            float acc = 0.0f;
            for (uint16_t c = 0; c < numChannels; ++c) {
                int16_t s = src[i * numChannels + c];
                acc += static_cast<float>(s) / 32768.0f;
            }
            out[i] = acc / static_cast<float>(numChannels);
        }
        return true;
    } else if (audioFormat == 3 && bitsPerSample == 32) {
        // IEEE float 32-bit
        const float *src = reinterpret_cast<const float *>(dataStart);
        for (size_t i = 0; i < frameCount; ++i) {
            float acc = 0.0f;
            for (uint16_t c = 0; c < numChannels; ++c) {
                acc += src[i * numChannels + c];
            }
            out[i] = acc / static_cast<float>(numChannels);
        }
        return true;
    }

    // Unsupported format
    return false;
}

void Wavetable::resampleToTable(const std::vector<float> &in, std::vector<float> &out) {
    if (in.empty()) {
        makeSineTable();
        return;
    }
    const size_t inN = in.size();
    const size_t outN = out.size();
    for (size_t j = 0; j < outN; ++j) {
        const float pos = (static_cast<float>(j) * static_cast<float>(inN)) / static_cast<float>(outN);
        const size_t i0 = static_cast<size_t>(std::floor(pos)) % inN;
        const size_t i1 = (i0 + 1) % inN;
        const float frac = pos - std::floor(pos);
        out[j] = in[i0] * (1.0f - frac) + in[i1] * frac;
    }
}
