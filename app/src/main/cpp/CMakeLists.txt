cmake_minimum_required(VERSION 3.22.1)

project(oboe_synth)

add_library(oboe_synth SHARED
    OboeSynthEngine.cpp
    Wavetable.cpp
)

# === FluidSynth (Prefab or prebuilt) configuration ===
# First attempt to locate a Prefab-provided package from AAR dependency (recommended when using
# the Maven AAR that ships Prefab metadata). If Prefab isn't present, fall back to the
# third_party prebuilt layout: third_party/fluidsynth/{include,libs/<abi>}.
# Try both possible package names used by Prefab: 'fluidsynth' and 'fluidsynth-android'
find_package(fluidsynth QUIET CONFIG)
if (NOT TARGET fluidsynth::fluidsynth)
    find_package(fluidsynth-android QUIET CONFIG)
endif()

if (TARGET fluidsynth::fluidsynth)
    message(STATUS "Found FluidSynth via Prefab (fluidsynth::fluidsynth)")
    target_link_libraries(oboe_synth fluidsynth::fluidsynth)
    target_compile_definitions(oboe_synth PRIVATE HAVE_FLUIDSYNTH=1)
elseif (TARGET fluidsynth-android::fluidsynth)
    message(STATUS "Found FluidSynth via Prefab (fluidsynth-android::fluidsynth)")
    target_link_libraries(oboe_synth fluidsynth-android::fluidsynth)
    target_compile_definitions(oboe_synth PRIVATE HAVE_FLUIDSYNTH=1)
else()
    # If Prefab package didn't provide a CMake-config target, try to find the prebuilt .so
    # under the Prefab staging root (CMAKE_FIND_ROOT_PATH). This handles AARs that ship
    # native libs and headers via Prefab but don't export a CMake target.
    find_library(FLUIDSYNTH_PREFAB_LIB
        NAMES fluidsynth
        PATH_SUFFIXES modules/fluidsynth/libs/${ANDROID_ABI} modules/fluidsynth/libs/android.${ANDROID_ABI}
        PATHS ${CMAKE_FIND_ROOT_PATH}
        NO_DEFAULT_PATH
    )
    if (FLUIDSYNTH_PREFAB_LIB)
        message(STATUS "Found FluidSynth prebuilt via Prefab at ${FLUIDSYNTH_PREFAB_LIB}")
        add_library(fluidsynth_prefab SHARED IMPORTED)
        set_target_properties(fluidsynth_prefab PROPERTIES IMPORTED_LOCATION "${FLUIDSYNTH_PREFAB_LIB}")
        set_target_properties(fluidsynth_prefab PROPERTIES IMPORTED_GLOBAL TRUE)
        target_include_directories(oboe_synth PRIVATE "${CMAKE_FIND_ROOT_PATH}/modules/fluidsynth/include")
        target_link_libraries(oboe_synth fluidsynth_prefab)
        target_compile_definitions(oboe_synth PRIVATE HAVE_FLUIDSYNTH=1)

        # Ensure the Prefab-provided .so is present in the CMake library output dir so AGP packages it.
        set(FLUIDSYNTH_OUTPUT_PATH "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libfluidsynth.so")
        add_custom_command(TARGET oboe_synth POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different "${FLUIDSYNTH_PREFAB_LIB}" "${FLUIDSYNTH_OUTPUT_PATH}"
            COMMENT "Copying FluidSynth prefab .so to ${FLUIDSYNTH_OUTPUT_PATH} for APK packaging"
        )
    endif()

    # Fallback: check bundled third_party prebuilt headers/libs
    set(FLUIDSYNTH_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/fluidsynth")
    set(FLUIDSYNTH_INCLUDE_DIR "${FLUIDSYNTH_DIR}/include")
    set(FLUIDSYNTH_LIB_DIR "${FLUIDSYNTH_DIR}/libs/${ANDROID_ABI}")

    if(NOT DEFINED ANDROID_ABI)
        message(FATAL_ERROR "ANDROID_ABI not defined; cannot locate FluidSynth prebuilt for target ABI.")
    endif()

    if(EXISTS "${FLUIDSYNTH_INCLUDE_DIR}/fluidsynth.h")
        message(STATUS "FluidSynth headers found at ${FLUIDSYNTH_INCLUDE_DIR}")
        target_include_directories(oboe_synth PRIVATE "${FLUIDSYNTH_INCLUDE_DIR}")
        target_compile_definitions(oboe_synth PRIVATE HAVE_FLUIDSYNTH=1)

        # Prefer shared lib, fallback to static
        if(EXISTS "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so")
            add_library(fluidsynth SHARED IMPORTED)
            set_target_properties(fluidsynth PROPERTIES IMPORTED_LOCATION "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so")
            set_target_properties(fluidsynth PROPERTIES IMPORTED_GLOBAL TRUE)
            target_link_libraries(oboe_synth fluidsynth)

            # Copy third_party shared lib into build output so Gradle includes it in the APK
            set(FLUIDSYNTH_OUTPUT_PATH "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libfluidsynth.so")
            add_custom_command(TARGET oboe_synth POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so" "${FLUIDSYNTH_OUTPUT_PATH}"
                COMMENT "Copying third_party FluidSynth .so to ${FLUIDSYNTH_OUTPUT_PATH} for APK packaging"
            )
        elseif(EXISTS "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.a")
            add_library(fluidsynth STATIC IMPORTED)
            set_target_properties(fluidsynth PROPERTIES IMPORTED_LOCATION "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.a")
            target_link_libraries(oboe_synth fluidsynth)
        else()
            message(STATUS "FluidSynth headers found but no library found at ${FLUIDSYNTH_LIB_DIR} for ABI ${ANDROID_ABI}; header-only build (linking disabled).")
        endif()
    else()
        message(STATUS "FluidSynth not found via Prefab or third_party; building without FluidSynth support.")
    endif()
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
