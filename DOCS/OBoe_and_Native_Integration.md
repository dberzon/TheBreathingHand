# Oboe Integration & Native Audio — Developer Guide 🔧🎶

**Purpose:** This document explains how Oboe and the native enhancements (wavetables, sample mapping, SFZ import, JNI bridging and UI flows) are implemented in Breathinghand. It is written to help a new developer onboard quickly and safely modify or extend the audio engine.

---

## Quick summary ✅
- Real-time audio is handled by a native C++ engine using Oboe.
- Heavy DSP work (wavetable/sample resampling, band-limited table generation) is done off the audio thread.
- The audio callback performs no allocations or locks — it uses atomic loads of precomputed pointers.
- Kotlin UI (file pickers, SFZ import, Manage Samples) communicates with native code via `DirectByteBuffer` (no copy) and JNI wrappers.

---

## Quick start: build & run ⚡
- Build: `./gradlew assembleDebug` (Windows: `.\gradlew assembleDebug --no-daemon`).
- Install/run on device via Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Ensure Android NDK is installed and Android SDK/NDK paths set in `local.properties`.

---

## Project entrypoints & important files (map) 📁
- Native audio engine and synth:
  - `app/src/main/cpp/OboeSynthEngine.cpp` — main synth, voices, audio callback, sample registration and wavetable handling.
  - `app/src/main/cpp/Wavetable.h` & `Wavetable.cpp` — parsing WAV files, resampling to fixed single-cycle table (`kWavetableSize = 2048`), `render(phase)`.
  - `app/src/main/cpp/CMakeLists.txt` — native build inclusion.
- JNI / Kotlin wrappers:
  - `app/src/main/java/com/breathinghand/audio/OboeSynthesizer.kt` — high-level wrappers: `loadWavetableFromByteBuffer`, `registerSampleFromByteBuffer`, `getLoadedSampleNames()`, `unloadSample()`.
- UI & Import flows:
  - `app/src/main/java/com/breathinghand/MainActivity.kt` — file pickers (WAV, SFZ, folder & single-missing sample pickers), SFZ parsing & import flow, `showManageSamplesDialog()`.
  - `app/src/main/java/com/breathinghand/ControlsOverlayView.kt` — buttons/callbacks for Load Sample Map, Import SFZ, Manage Samples.
- Docs and helpers:
  - `DOCS/` (additions go here)
  - `README.md` (project-level overview)

---

## Oboe Integration: Architecture & real-time constraints 🧠
- Use Oboe to drive the audio stream and callback into `OboeSynthEngine`.
- The **audio callback** is a hot path: avoid allocations, locks, file I/O, or expensive math (FFT, resampling).
- Use `std::atomic<const float*>` (or `std::atomic<std::shared_ptr<T>>` for non-audio thread safety) to swap pointers that the audio thread reads.
- Precompute all sample-band tables and wavetables on background threads and swap atomically.
- Voice processing: voices hold an atomic wavetable pointer (if a custom wavetable/sample is applied to the voice it will point there, else fallback to built-in per-note table or sine table).

---

## Wavetable implementation (Wavetable.{h,cpp}) 🌀
- Responsibilities:
  - Parse RIFF/WAVE (support PCM16 and FLOAT32 mono/stereo), downmix to mono.
  - Resample / truncate to fixed-size single-cycle table (`kWavetableSize = 2048`).
  - Normalize amplitude for consistent levels.
  - Provide `render(phase)` using linear interpolation.
- JNI path: `nativeLoadWavetableFromDirectBuffer` → engine wrapper `loadWavetableFromBufferPublic` → per-voice atomic pointer swap.
- Kotlin: `OboeSynthesizer.loadWavetableFromByteBuffer(bb: ByteBuffer)` — ensures `ByteBuffer` is direct and little-endian.

---

## Sample mapping & band-limited tables 🗺️
- `SampleRegion` stores: sample name, root note, lo key, hi key, and precomputed per-band tables.
- Banded approach: precompute `kSampleBandCount` (default 11) tables per sample scaled to octave bands to reduce aliasing.
- Registration process (`registerSampleFromBufferPublic`):
  - Parse sample file (16-bit or float), resample to base sample format if needed.
  - Generate band tables — done on worker thread.
  - Push the new SampleRegion into `samplesList_` (atomic swap of `shared_ptr<vector<SampleRegion>>`).
- Memory: each table ≈ 2048 floats × 4 bytes ≈ 8 KB; `kSampleBandCount × tables × number_of_regions` determines total usage — monitor on low-memory devices.

---

## SFZ importer (current capabilities & extension points) 📂
- Current parser extracts simple region attributes: `sample=`, `lokey=`, `hikey=`, `pitch_keycenter=/key=`.
- Flow: pick SFZ file → parse regions → ask user to select folder containing samples (OpenDocumentTree) → attempt to match files → for missing files prompt user to pick each missing file.
- Registered sample call: `internalSynth.registerSampleFromByteBuffer(bb, root, lo, hi, name)`.
- Extension ideas: support loop points, group parameters, release/envelope, velocity layers, and more SFZ opcodes.

---

## Compressed audio support (OGG/MP3/AAC) 🗜️
- Rationale: Many SFZs reference compressed audio (e.g., `.ogg`, `.mp3`). The native WAV parser accepts only PCM16 or IEEE float WAV files.
- Implementation (current): Automatic decode-and-register flow implemented in Kotlin:
  - `app/src/main/java/com/breathinghand/audio/AudioDecoder.kt` decodes compressed tracks using `MediaExtractor` + `MediaCodec` on a background coroutine and assembles a WAV byte array (PCM16 LE) via `buildWavFromPcm`.
  - UI integration in `MainActivity` detects non-WAV MIME types and invokes the decoder off the UI thread (Dispatchers.IO) before calling `registerSampleFromByteBuffer`.
  - Coroutines dependency added: `org.jetbrains.kotlinx:kotlinx-coroutines-android`.
- Behavior & reliability:
  - Decoding uses device-provided codecs (MediaCodec). Common formats (Vorbis/MP3/AAC) are supported on most devices; if a decoder is unavailable, the import will fail with a clear Toast and Logcat message.
  - Native side still expects WAV bytes; decoder outputs PCM-wrapped WAV to preserve native parsing logic and avoid adding large native decoders.
- Logs & tags:
  - Decode logs: tag `AudioDecoder` (Kotlin)
  - Native parse/register logs: tag `OBoeEngine` (C++) — warnings emitted when parsing fails
- Future options:
  - Integrate ffmpeg/Exo or add native decoders for guaranteed format coverage (tradeoff: app size & build complexity).

---

## UI & Import flows additions ✅
- SFZ and single-sample pickers now support compressed files transparently (attempt decode on import).
- Long-running decode work runs on `Dispatchers.IO` and reports progress/feedback via Toasts and Logcat.
- Immediate verification: the UI checks `getLoadedSampleNames()` before/after registration and notifies the user if no sample was added.


---

## UI: file pickers and management 🎛️
- File pickers implemented in `MainActivity` using `ActivityResultContracts.OpenDocument` and `OpenDocumentTree`.
- Manage Samples dialog uses `internalSynth.getLoadedSampleNames()` and `internalSynth.unloadSample(index)` to present names and allow unloading.
- Careful: UI threads may call native registration functions; ensure heavy work does not run on UI thread long enough to stall the app. Consider spinning registration jobs to a background executor if you see jank.

---

## JNI & Kotlin notes 🧩
- Use `DirectByteBuffer` when passing file contents: no copy & simpler native access.
- Byte order: ensure `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)` when constructing (WAV RIFF is LE).
- Exposed native functions:
  - `nativeLoadWavetableFromDirectBuffer` / `loadWavetableFromBufferPublic`
  - `nativeRegisterSample(...)` / `registerSampleFromBufferPublic`
  - `nativeGetLoadedSampleNames()` / `nativeUnloadSample()`
- Keep JNI wrappers minimal; validation should be done in Kotlin before calling native code (e.g., check buffer not null, direct).

---

## Real-time safety checklist (must follow when editing audio code) ⚠️
- Never allocate memory inside the audio callback.
- Never lock mutexes or use blocking IO in the audio callback.
- Use `std::atomic` for pointer swaps or `std::atomic<std::shared_ptr<...>>` for safe container swaps.
- Do all heavy work (resampling, filtering, table generation) on background threads.
- When changing voices or samples, swap pointers atomically to avoid races.

---

## Testing & smoke test checklist ✅
- Build: `./gradlew assembleDebug` → install on a device.
- Verify these flows in the running app:
  1. Long-press MPE Switch → pick a single `.wav` → confirm `Loaded wavetable` Toast and sanity sound test.
  2. ControlsOverlay → Import SFZ → pick SFZ file → pick folder containing samples → confirm `Imported X regions` Toast.
  3. ControlsOverlay → Manage Samples → list shows loaded names → Unload sample removes it and mappings.
  4. Play notes across keyboard (use internal UI/virtual keyboard) and listen for aliasing or obvious artifacts.
- Monitor: Android Studio profiler for CPU and memory; watch for spikes when importing large SFZs.

---

## Debugging tips & common pitfalls 🐞
- C++ syntax errors after edits are common: run `./gradlew assembleDebug` and inspect the native compiler output.
- Kotlin unresolved references often mean misplaced braces or incorrectly scoped functions (we recently fixed a stray `setContentView` placement).
- If audio is silent: check that the stream is started (`internalSynth.start()`), sample pointers not null, and sample tables were generated successfully.
- For missing SFZ files: double-check `DocumentFile.findFile` behavior — use base name fallback if original path includes directories.

---

## Performance & future improvements 🚀
- Better band-limiting: replace naive per-octave filtering with sinc FIR or FFT-based band-limited downsampling.
- On-demand per-note table generation to lower memory usage (cache hot notes/bands only).
- Consider compressed table storage (float16) for large sample sets on memory-constrained devices.
- Add more comprehensive SFZ feature support (envelopes, loops, groups, velocity layers).

---

## How to add a new sample and wiring steps (practical) 🛠️
1. In Kotlin: read file as bytes, allocate `ByteBuffer` direct LE and call `internalSynth.registerSampleFromByteBuffer(bb, root, lo, hi, fileName)`.
2. Native: `registerSampleFromBufferPublic` should parse sample, create band-limited tables on worker thread and atomically insert `SampleRegion` into `samplesList_`.
3. Verify by calling `getLoadedSampleNames()` and inspecting playback.

---

## Changelog / recent notable edits ✍️
- Added `Wavetable.{h,cpp}` to parse and resample WAV files to single-cycle tables.
- Added per-sample per-band mapping and `SampleRegion` registration APIs.
- Implemented SFZ importer (basic region parsing), folder resolution + missing-file resolution flows.
- Added Manage Samples UI and JNI bindings for listing and unloading samples.
- Fixed Kotlin file-scoped syntax bugs and ensured `setContentView(container)` is inside `setupUI()`.
- Added compressed audio decode-and-register flow:
  - `AudioDecoder.kt` decodes OGG/MP3/AAC → PCM WAV via MediaCodec on `Dispatchers.IO` and builds WAV bytes for native registration.
  - MainActivity integrates decoding for SFZ imports and single-sample pickers and verifies registrations.
  - Added unit test `AudioDecoderTest` for WAV header generation and basic decoding plumbing.
- Added clear logging tags: `AudioDecoder` (decoder) and `OBoeEngine` (native parsing/registration).


---

## References & links 🔗
- Oboe docs: https://github.com/google/oboe
- WAV / RIFF spec: https://www.w3.org/TR/2011/NOTE-WAVE-20110317/
- Android JNI guide: https://developer.android.com/training/articles/perf-jni

---

## Want more? 💡
Tell me if you want any of the following added:
- A separate short README placed in `app/src/main/cpp/README.md` for native devs.
- A concise test script or instrumentation tests for SFZ import flows.
- A Memory/CPU report template to use when running profiler tests.

---

*Created for Breathinghand — January 2026*