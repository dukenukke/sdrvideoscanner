#include "SpectrumAnalyzer.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <limits>
#include <stdexcept>

namespace sdr {
namespace {

constexpr double kPi = 3.141592653589793238462643383279502884;
constexpr double kCs16FullScale = 32768.0;
constexpr double kOccupiedBandwidthThresholdDb = 6.0;

bool isPowerOfTwo(std::size_t value) {
    return value != 0 && (value & (value - 1)) == 0;
}

}  // namespace

SpectrumAnalyzer::SpectrumAnalyzer(std::size_t fftSize)
        : fftSize_(fftSize),
          window_(fftSize),
          bitReverse_(fftSize),
          fftBuffer_(fftSize),
          powerBins_(fftSize),
          sortedPowerScratch_(fftSize) {
    if (!isPowerOfTwo(fftSize_) || fftSize_ < 2) {
        throw std::invalid_argument("FFT size must be a power of two");
    }

    buildHannWindow();
    buildBitReverseTable();
}

SpectrumStats SpectrumAnalyzer::analyze(
        const SampleBuffer& buffer,
        std::uint64_t sampleRateHz,
        std::uint64_t centerFrequencyHz,
        bool hasCenterFrequencyHz) {
    if (!canAnalyze(buffer)) {
        throw std::invalid_argument("SampleBuffer does not contain enough samples for FFT");
    }

    loadWindowedSamples(buffer);
    fft();
    computePowerSpectrum();

    const auto peakIt = std::max_element(powerBins_.begin(), powerBins_.end());
    const auto peakBinIndex = static_cast<std::size_t>(std::distance(powerBins_.begin(), peakIt));
    const auto peakPower = *peakIt;

    sortedPowerScratch_ = powerBins_;
    const auto noiseSampleCount = std::max<std::size_t>(1, (fftSize_ * 3) / 4);
    std::nth_element(
            sortedPowerScratch_.begin(),
            sortedPowerScratch_.begin() + static_cast<std::ptrdiff_t>(noiseSampleCount),
            sortedPowerScratch_.end());

    double noisePowerSum = 0.0;
    for (std::size_t index = 0; index < noiseSampleCount; ++index) {
        noisePowerSum += sortedPowerScratch_[index];
    }

    const double averageNoisePower = noisePowerSum / static_cast<double>(noiseSampleCount);
    const double noiseFloorDbfs = dbfsFromPower(averageNoisePower);
    const double occupiedThresholdDbfs = noiseFloorDbfs + kOccupiedBandwidthThresholdDb;

    bool hasOccupiedBin = false;
    std::size_t firstOccupiedBin = 0;
    std::size_t lastOccupiedBin = 0;
    for (std::size_t index = 0; index < powerBins_.size(); ++index) {
        if (dbfsFromPower(powerBins_[index]) >= occupiedThresholdDbfs) {
            if (!hasOccupiedBin) {
                firstOccupiedBin = index;
                hasOccupiedBin = true;
            }
            lastOccupiedBin = index;
        }
    }

    const std::size_t occupiedBins =
            hasOccupiedBin ? (lastOccupiedBin - firstOccupiedBin + 1) : 0;

    SpectrumStats stats;
    stats.fftSize = fftSize_;
    stats.averageNoiseFloorDbfs = noiseFloorDbfs;
    stats.peakBinIndex = peakBinIndex;
    stats.peakLevelDbfs = dbfsFromPower(peakPower);
    stats.occupiedBandwidthBins = occupiedBins;
    stats.hasSampleRate = sampleRateHz != 0;
    if (stats.hasSampleRate) {
        stats.binWidthHz = static_cast<double>(sampleRateHz) / static_cast<double>(fftSize_);
        stats.occupiedBandwidthHz = static_cast<double>(occupiedBins) * stats.binWidthHz;
        if (peakBinIndex <= fftSize_ / 2) {
            stats.peakFrequencyOffsetHz =
                    static_cast<double>(peakBinIndex) * stats.binWidthHz;
        } else {
            stats.peakFrequencyOffsetHz =
                    -static_cast<double>(fftSize_ - peakBinIndex) * stats.binWidthHz;
        }

        stats.hasCenterFrequency = hasCenterFrequencyHz;
        if (stats.hasCenterFrequency) {
            stats.peakRfFrequencyHz =
                    static_cast<double>(centerFrequencyHz) + stats.peakFrequencyOffsetHz;
        }
    }

    return stats;
}

void SpectrumAnalyzer::copyShiftedDbfsBins(std::vector<float>& output) const {
    output.resize(fftSize_);
    const auto half = fftSize_ / 2;
    for (std::size_t index = 0; index < fftSize_; ++index) {
        const auto sourceIndex = (index + half) % fftSize_;
        output[index] = static_cast<float>(dbfsFromPower(powerBins_[sourceIndex]));
    }
}

void SpectrumAnalyzer::buildHannWindow() {
    if (fftSize_ == 1) {
        window_[0] = 1.0;
        coherentGain_ = 1.0;
        return;
    }

    double windowSum = 0.0;
    for (std::size_t index = 0; index < fftSize_; ++index) {
        window_[index] =
                0.5 * (1.0 - std::cos((2.0 * kPi * static_cast<double>(index)) /
                                       static_cast<double>(fftSize_ - 1)));
        windowSum += window_[index];
    }

    coherentGain_ = windowSum / static_cast<double>(fftSize_);
}

void SpectrumAnalyzer::buildBitReverseTable() {
    std::size_t bitCount = 0;
    for (std::size_t value = fftSize_; value > 1; value >>= 1) {
        ++bitCount;
    }

    for (std::size_t index = 0; index < fftSize_; ++index) {
        std::size_t reversed = 0;
        for (std::size_t bit = 0; bit < bitCount; ++bit) {
            reversed = (reversed << 1) | ((index >> bit) & 1U);
        }
        bitReverse_[index] = reversed;
    }
}

void SpectrumAnalyzer::loadWindowedSamples(const SampleBuffer& buffer) {
    for (std::size_t index = 0; index < fftSize_; ++index) {
        const auto sourceIndex = bitReverse_[index];
        const double windowValue = window_[sourceIndex];
        fftBuffer_[index].real =
                (static_cast<double>(buffer.i(sourceIndex)) / kCs16FullScale) * windowValue;
        fftBuffer_[index].imag =
                (static_cast<double>(buffer.q(sourceIndex)) / kCs16FullScale) * windowValue;
    }
}

void SpectrumAnalyzer::fft() {
    for (std::size_t length = 2; length <= fftSize_; length <<= 1) {
        const double angle = -2.0 * kPi / static_cast<double>(length);
        const Complex phaseStep{std::cos(angle), std::sin(angle)};

        for (std::size_t start = 0; start < fftSize_; start += length) {
            Complex phase{1.0, 0.0};
            const auto halfLength = length / 2;

            for (std::size_t offset = 0; offset < halfLength; ++offset) {
                const auto evenIndex = start + offset;
                const auto oddIndex = evenIndex + halfLength;
                const auto even = fftBuffer_[evenIndex];
                const auto oddSource = fftBuffer_[oddIndex];
                const Complex odd{
                        phase.real * oddSource.real - phase.imag * oddSource.imag,
                        phase.real * oddSource.imag + phase.imag * oddSource.real};

                fftBuffer_[evenIndex] = {even.real + odd.real, even.imag + odd.imag};
                fftBuffer_[oddIndex] = {even.real - odd.real, even.imag - odd.imag};
                phase = {
                        phase.real * phaseStep.real - phase.imag * phaseStep.imag,
                        phase.real * phaseStep.imag + phase.imag * phaseStep.real};
            }
        }
    }
}

void SpectrumAnalyzer::computePowerSpectrum() {
    const double scale = 1.0 /
                         (static_cast<double>(fftSize_) * coherentGain_ *
                          static_cast<double>(fftSize_) * coherentGain_);

    for (std::size_t index = 0; index < fftSize_; ++index) {
        const auto& value = fftBuffer_[index];
        powerBins_[index] = (value.real * value.real + value.imag * value.imag) * scale;
    }
}

double SpectrumAnalyzer::dbfsFromPower(double power) const {
    if (power <= 0.0) {
        return -std::numeric_limits<double>::infinity();
    }

    return 10.0 * std::log10(power);
}

}  // namespace sdr
