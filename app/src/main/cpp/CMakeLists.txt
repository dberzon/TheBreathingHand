cmake_minimum_required(VERSION 3.22.1)

project(oboe_synth)

add_library(oboe_synth SHARED
    OboeSynthEngine.cpp
    Wavetable.cpp
)

# === FluidSynth (prebuilt) configuration ===
# Expect prebuilt headers and libs at:
#   third_party/fluidsynth/include
#   third_party/fluidsynth/libs/<abi>/libfluidsynth.(so|a)
set(FLUIDSYNTH_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/fluidsynth")
set(FLUIDSYNTH_INCLUDE_DIR "${FLUIDSYNTH_DIR}/include")
set(FLUIDSYNTH_LIB_DIR "${FLUIDSYNTH_DIR}/libs/${ANDROID_ABI}")

if(NOT DEFINED ANDROID_ABI)
    message(FATAL_ERROR "ANDROID_ABI not defined; cannot locate FluidSynth prebuilt for target ABI.")
endif()

# Check for FluidSynth headers; make feature optional so builds succeed when not present
if(EXISTS "${FLUIDSYNTH_INCLUDE_DIR}/fluidsynth.h")
    message(STATUS "FluidSynth headers found at ${FLUIDSYNTH_INCLUDE_DIR}")
    target_include_directories(oboe_synth PRIVATE "${FLUIDSYNTH_INCLUDE_DIR}")
    target_compile_definitions(oboe_synth PRIVATE HAVE_FLUIDSYNTH=1)

    # Prefer shared lib, fallback to static
    set(FLUIDSYNTH_SO "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so")
    set(FLUIDSYNTH_A  "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.a")

    if(EXISTS "${FLUIDSYNTH_SO}")
        add_library(fluidsynth SHARED IMPORTED)
        set_target_properties(fluidsynth PROPERTIES IMPORTED_LOCATION "${FLUIDSYNTH_SO}")
    elseif(EXISTS "${FLUIDSYNTH_A}")
        add_library(fluidsynth STATIC IMPORTED)
        set_target_properties(fluidsynth PROPERTIES IMPORTED_LOCATION "${FLUIDSYNTH_A}")
    else()
        message(STATUS "FluidSynth headers found but no library found at ${FLUIDSYNTH_LIB_DIR} for ABI ${ANDROID_ABI}; header-only build (linking disabled).")
    endif()
else()
    message(STATUS "FluidSynth headers not found at ${FLUIDSYNTH_INCLUDE_DIR}; building without FluidSynth support.")
endif()

# === Oboe (existing) ===
find_package(oboe REQUIRED CONFIG)

# Add compiler options suitable for realtime DSP in Release builds
target_compile_options(oboe_synth PRIVATE
    $<$<CONFIG:Release>:-O3 -ffast-math>
)

# Link libraries. Fluidsynth is added conditionally (only if the imported target exists).
target_link_libraries(oboe_synth
    oboe::oboe
    android
    log
    $<$<TARGET_EXISTS:fluidsynth>:fluidsynth>
)

# Real C++ standard
target_compile_features(oboe_synth PRIVATE cxx_std_17)
