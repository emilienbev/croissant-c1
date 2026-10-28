# lpac-jni

lpac's libeuicc (the SGP.22 eSIM LPA in C) and OpenEUICC's JNI wrapper around it,
vendored for the app's eSIM page. An Android library module built
with ndk-build for arm64-v8a only; `:app` depends on it.

## Where it comes from

| Part | Source | Commit |
|---|---|---|
| `src/main/java`, `src/main/jni/lpac-jni`, `lpac-jni.mk`, `Application.mk` | OpenEUICC `libs/lpac-jni`, gitea.angry.im/PeterCxy/OpenEUICC | `9a537a2` (2026-09-02) |
| `src/main/jni/lpac/euicc`, `cjson-ext`, `LICENSES`, `REUSE.toml` | github.com/estkme-group/lpac | `d214738fa0bdb23faf5833d3d798963079a00468`, the one OpenEUICC pins |
| `src/main/jni/cjson/cjson` (`cJSON.c/.h`, `cJSON_Utils.c/.h`, `LICENSE`) | github.com/DaveGamble/cJSON | `c859b25da02955fef659d658b8f324b5cde87be3`, the one OpenEUICC pins |

The JNI symbol names carry the package, so it stays `net.typeblog.lpac_jni`.

## What was changed here

- `impl/HttpInterfaceImpl.kt` has no constructor arguments: OpenEUICC's verbose
  logging, proxy and ignore-TLS flows were its settings. With them went
  `AllowAllTrustManager.kt`, so nothing can switch certificate checks off; the SM-DP+
  is trusted only through the GSMA CI roots in `impl/RootCertificates.kt` that the card
  names. No androidx or coroutines dependency is left.
- `impl/HttpInterfaceImpl.kt` waits 10 s to connect and 60 s to read, 5 s each for a
  notification; OpenEUICC's were 2 s and no limit, 1 s each (2026-09-17, eSIM step 3).
- `impl/LocalProfileAssistantImpl.kt`: `profiles` freed the list's end (`curr`, always
  0) instead of its head; it frees `head`.
- `Application.mk` builds `arm64-v8a` only; the module's `abiFilters` say the same.
- OpenEUICC's `Android.bp` and example tests were not copied.

## Licences

lpac-jni is LGPL-2.1 (`LICENSE`); libeuicc is LGPL-2.1-only (lpac's `REUSE.toml`);
cJSON is MIT; `euicc/sha256.c` is CC0-1.0. This module keeps those licences; the rest of
the repo is Apache-2.0 (see the root `NOTICE`).

## Build

Needs NDK 28.2.13676358, AGP 9.4's default:
`sdkmanager --install "ndk;28.2.13676358"`. `./gradlew :app:assembleRelease` builds
it with the app; the library is about 110 KB.
