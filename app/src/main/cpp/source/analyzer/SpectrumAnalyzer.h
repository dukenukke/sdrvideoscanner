#pragma once

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

#include "../SampleBuffer.h"

namespace sdr {

struct SpectrumStats {
    std::size_t fftSize = 0;
    double binWidthHz = 0.0;
    double averageNoiseFloorDbfs = 0.0;
    std::size_t peakBinIndex = 0;
    double peakLevelDbfs = 0.0;
    double peakFrequencyOffsetHz = 0.0;
    double peakRfFrequencyHz = 0.0;
    std::size_t occupiedBandwidthBins = 0;
    double occupiedBandwidthHz = 0.0;
    bool hasSampleRate = false;
    bool hasCenterFrequency = false;
};

class SpectrumAnalyzer {
public:
    explicit SpectrumAnalyzer(std::size_t fftSize);

    std::size_t fftSize() const {
        return fftSize_;
    }

    bool canAnalyze(const SampleBuffer& buffer) const {
        return buffer.sampleCount() >= fftSize_;
    }

    SpectrumStats analyze(
            const SampleBuffer& buffer,
            std::uint64_t sampleRateHz,
            std::uint64_t centerFrequencyHz,
            bool hasCenterFrequencyHz);

    void copyShiftedDbfsBins(std::vector<float>& output) const;

private:
    struct Complex {
        double real = 0.0;
        double imag = 0.0;
    };

    void buildHannWindow();
    void buildBitReverseTable();
    void loadWindowedSamples(const SampleBuffer& buffer);
    void fft();
    void computePowerSpectrum();
    double dbfsFromPower(double power) const;

    std::size_t fftSize_;
    double coherentGain_ = 1.0;
    std::vector<double> window_;
    std::vector<std::size_t> bitReverse_;
    std::vector<Complex> fftBuffer_;
    std::vector<double> powerBins_;
    std::vector<double> sortedPowerScratch_;
};

}  // namespace sdr
