# micro-frontend

The TFLite Micro audio frontend, the feature maker microWakeWord models are trained on, built
for the R1 through JNI.

- Source: `pymicro_features-2.0.2.tar.gz` from PyPI (fetched 2026-11-24), Apache 2.0 (LICENSE
  here). It carries TensorFlow's `tensorflow/lite/experimental/microfrontend/lib` (Apache 2.0)
  and kissfft (BSD-3-Clause, the notice in each file).
- Copied as they are: the 16 `.cc` files its `setup.py` builds and their headers, kissfft's
  `kiss_fft.cc`, `tools/kiss_fftr.cc` and headers (pulled in by `kiss_fft_int16.cc`).
  Left out: the `_io`, `_main`, `_test` and memmap files, kissfft's other tools.
- New: `micro_frontend_jni.cpp` (the settings of pymicro-features' `micro_features.cpp`),
  `CMakeLists.txt`, `MicroFrontend.kt`.

The settings must stay those of the version a model was trained with, or the model hears
something else. Checked on the R1 on 2026-11-24 against pymicro-features on five recordings:
the same wakes at every cutoff, with rare one-frame differences in two of the 40 channels.
