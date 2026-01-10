cmake_minimum_required(VERSION 3.22.1)

project(oboe_synth)

add_library(oboe_synth SHARED
    OboeSynthEngine.cpp
)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(oboe_synth
    oboe::oboe
    android
    log
)

target_compile_features(oboe_synth PRIVATE cxx_std_17)
