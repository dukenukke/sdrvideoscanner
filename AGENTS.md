# SDRVideoScanner Development Rules

## General

* Use modern C++17 for all SDR processing.
* Kotlin is used only for Android UI and JNI interaction.
* Keep JNI as thin as possible.

## Architecture

* `native-lib.cpp` is a JNI wrapper only.
* Business logic must never be implemented inside JNI.
* The decoder must not know where IQ samples originate.
* All IQ sources shall implement `ISampleSource`.
* `FileSource` is the reference implementation for development and testing.
* `PlutoSource` shall use libiio but expose only the `ISampleSource` interface.
* Device-specific code must never leak outside the source module.

## DSP

* DSP shall be implemented as independent processing blocks.
* Processing blocks should be reusable and unit-testable.
* Pipeline stages should communicate only through `SampleBuffer`.

## Code organization

* Prefer composition over inheritance.
* Use RAII everywhere.
* Avoid global variables.
* Prefer `std::unique_ptr` over raw pointers.
* Keep classes focused on a single responsibility (SOLID).

## Project structure

* Do not change the project directory structure without approval.
* Do not move files between modules without approval.
* Keep interfaces separated from implementations.

## Development workflow

* Design architecture before writing code.
* Keep commits small and focused.
* Every architectural change should be discussed before implementation.

## Testing

* All DSP components should be testable without SDR hardware.
* FileSource shall be used for deterministic debugging.
* Playback from IQ files must use exactly the same DSP pipeline as live Pluto reception.

## Future extensibility

The architecture shall allow adding new sample sources without modifying the decoder, including:

* PlutoSDR (libiio)
* RTL-SDR
* HackRF
* LimeSDR
* LibreSDR
* Network IQ streams
* IQ replay files

## Performance

- Avoid dynamic memory allocation inside the real-time processing loop.
- Allocate buffers during initialization.
- Reuse existing buffers whenever possible.

## Data ownership

- SampleBuffer ownership must always be explicit.
- Avoid unnecessary copying of IQ data.
- Prefer move semantics and buffer reuse.