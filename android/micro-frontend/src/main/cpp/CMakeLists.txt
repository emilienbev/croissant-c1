cmake_minimum_required(VERSION 3.22.1)
project(micro_frontend C CXX)

# The TFLite Micro audio frontend as pymicro-features 2.0.2 builds it (its setup.py): the same
# sources, FIXED_POINT=16, the repo root and kissfft on the include path.
set(LIB tensorflow/lite/experimental/microfrontend/lib)
add_library(micro_frontend SHARED
    micro_frontend_jni.cpp
    ${LIB}/kiss_fft_int16.cc
    ${LIB}/fft.cc
    ${LIB}/fft_util.cc
    ${LIB}/filterbank.cc
    ${LIB}/filterbank_util.cc
    ${LIB}/frontend.cc
    ${LIB}/frontend_util.cc
    ${LIB}/log_lut.cc
    ${LIB}/log_scale.cc
    ${LIB}/log_scale_util.cc
    ${LIB}/noise_reduction.cc
    ${LIB}/noise_reduction_util.cc
    ${LIB}/pcan_gain_control.cc
    ${LIB}/pcan_gain_control_util.cc
    ${LIB}/window.cc
    ${LIB}/window_util.cc
)
target_compile_definitions(micro_frontend PRIVATE FIXED_POINT=16)
target_include_directories(micro_frontend PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/kissfft)
target_compile_options(micro_frontend PRIVATE -O2)
# 16 KB pages, as Android 15+ wants; harmless on the R1.
target_link_options(micro_frontend PRIVATE -Wl,-z,max-page-size=16384)
