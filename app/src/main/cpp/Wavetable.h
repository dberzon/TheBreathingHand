#pragma once

#include <cstdint>
#include <vector>

class Wavetable {
public:
    static constexpr int kWavetableSize = 2048;

    // Construct from raw WAV file bytes (RIFF/WAVE). If parsing fails, this creates a sine table fallback.
    Wavetable(const uint8_t *data, size_t size);

    // Access the internal table (kWavetableSize floats)
    const float *data() const { return table_.data(); }

    // Returns true if the constructor successfully parsed the input data as a WAV file
    bool parsedOk() const { return parsedOk_; }

    // Render one sample from the table using a normalized phase in [0,1).
    inline float render(float phase) const {
        const float idx = phase * static_cast<float>(kWavetableSize);
        int i0 = static_cast<int>(idx) % kWavetableSize;
        if (i0 < 0) i0 += kWavetableSize;
        int i1 = (i0 + 1) % kWavetableSize;
        const float frac = idx - static_cast<float>(i0);
        return table_[i0] * (1.0f - frac) + table_[i1] * frac;
    }

private:
    std::vector<float> table_;
    bool parsedOk_ = false;

    void makeSineTable();
    bool parseWav(const uint8_t *data, size_t size, std::vector<float> &out);
    void resampleToTable(const std::vector<float> &in, std::vector<float> &out);
};
