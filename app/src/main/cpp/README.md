# Native (C++) README — Breathinghand 🧭

Purpose: A short onboarding guide for native contributors working on the Oboe-based audio engine, wavetable/sample support, and JNI bindings.

---

## Quick start 🔧
- Build from the project root: `./gradlew assembleDebug` (Windows: `.\gradlew assembleDebug --no-daemon`).
- NDK/CMake are used for native builds; check `app/src/main/cpp/CMakeLists.txt` to add source files.
- Use Android Studio's "Attach debugger to Android process" or `adb logcat` to inspect native logs.

---

## Key files 🗂️
- `OboeSynthEngine.cpp` — main synth, audio callback, voice management, sample/wavetable registration.
- `Wavetable.h` / `Wavetable.cpp` — WAV parsing, resampling to `kWavetableSize` (2048), and `render(phase)`.
- `CMakeLists.txt` — add new .cpp/.h to be compiled into the native library.
- `app/src/main/java/com/breathinghand/audio/OboeSynthesizer.kt` — Kotlin JNI wrappers and helper APIs.

---

## Real-time rules (must follow) ⚠️
- NEVER allocate memory or lock inside the audio callback.
- Avoid file I/O, system calls, or heavy math (no FFTs) in the audio thread.
- Use `std::atomic<const float*>` or `std::atomic<std::shared_ptr<T>>` to swap buffers/tables safely.
- Precompute tables (band-limited tables, wavetables) on worker threads and swap pointers atomically.

---

## Common contributor tasks 🔁
- Adding a source file: add .cpp and .h, then update `CMakeLists.txt` and rebuild.
- Adding a native API: add the C++ function, a JNI export, and the Kotlin wrapper in `OboeSynthesizer.kt`.
- Adding a sample or wavetable flow: read byte buffer via DirectByteBuffer on Kotlin side and call native registration function; heavy processing must run off the audio thread.

---

## Debugging tips 🐞
- C++ compile errors: run `./gradlew assembleDebug` and inspect Gradle/native output.
- JNI mismatches: verify method signatures and `extern "C" JNIEXPORT` names and correct Java package/class names.
- Silent audio: check `internalSynth.start()` is called, atomic pointers are non-null, and tables are generated.
- Logs: native logs are emitted with tag `OBoeEngine` (use `adb logcat | findstr OBoeEngine`). Kotlin-side decoder logs use tag `AudioDecoder`.

---

## Compressed audio support (quick note)
- The app now decodes compressed audio (OGG/MP3/AAC) on the Android side using `MediaExtractor` + `MediaCodec` and wraps decoded PCM into a WAV byte array for native registration.
- Decoder lives in `app/src/main/java/com/breathinghand/audio/AudioDecoder.kt`. Keep decode logic on the Java/Kotlin side to avoid adding large native decoder dependencies.
- If decoding fails, native registration is not attempted; look for logs (`AudioDecoder` / `OBoeEngine`) to diagnose format or decoder availability issues.

---

## Tests & verification ✅
- Smoke tests: import a wavetable, import an SFZ (folder), import `.ogg`/.mp3 samples (decoded & registered), list samples, unload a sample, and play across the keyboard while watching CPU/memory.
- Use Android Studio profiler for CPU/memory traces during import and playback.
- Unit tests: `AudioDecoderTest` verifies WAV header construction used by the decoder wrapper.

---

## Useful links 🔗
- Oboe: https://github.com/google/oboe
- Android NDK & JNI docs: https://developer.android.com/ndk and https://developer.android.com/training/articles/perf-jni

---

If you'd like, I can add a short example showing how to add a CMake entry or a minimal smoke-test script to the repo.