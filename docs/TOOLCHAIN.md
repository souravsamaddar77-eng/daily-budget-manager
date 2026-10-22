# Toolchain

Pinned for the October 2026 build. Execution results are in [VALIDATION.md](VALIDATION.md).

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle wrapper | 9.8.2 |
| Build JDK used | 25.0.2; emitted bytecode targets 17 |
| Kotlin / Compose compiler | 2.4.21 |
| KSP | 2.3.10 |
| Compose BOM | 2026.09.00 |
| Core KTX | 1.19.1 |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.11.0 |
| Room | 2.8.5 |
| Coroutines | 1.11.0 |
| Compile / target SDK | 37 / 37 |
| Minimum SDK | 26 |
| SDK platform | `platforms;android-37.0` |
| Build Tools | 37.0.0 |
| SDK command-line tools | 22.0 |
| Emulator | 37.2.12, WHPX, AOSP x86_64; v0.2 checks use API 26 and API 36 |
| Host Android test runtime | Robolectric 4.17, API 35, native SQLite |

AGP's built-in Kotlin is aligned with the Compose compiler using its documented classpath override. No separate `org.jetbrains.kotlin.android` plugin is applied. [AGP compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [Kotlin override](https://developer.android.com/build/releases/agp-9-0-0-release-notes).

Kotlin's published fully supported Gradle range is narrower than 9.8.2. Recorded builds/tests give project-specific evidence for this combination. [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html).

Wrapper JAR SHA-256: `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`. Distribution SHA-256: `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`, pinned in the wrapper properties. The source audit checks both. [Wrapper verification](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification).

SDK installation is explicit; `android.builder.sdkDownload=false` prevents automatic installation during builds. Wrapper/dependency downloads still occur on first use. Android Studio is optional. [SDK Manager](https://developer.android.com/tools/sdkmanager).

Shared Room/Compose tests run in `testDebugUnitTest` and native instrumentation. Debug-only test activities are unexported and excluded from release. Robolectric uses Android resources, API 35, paused looper mode, a 2 GB heap, and Java module-access flags listed in the app build file. Exported schema assets support the real v1→v2 migration test. [Robolectric](https://robolectric.org/getting-started/), [Compose testing](https://developer.android.com/develop/ui/compose/testing/migrate-v2).

Host tests set `robolectric.conscryptMode=OFF`. With Java 25, Robolectric's Conscrypt provider caused JCE signed-JAR verification to fail while initializing HMAC. This host-only workaround leaves the HMAC implementation and assertions intact and uses the host Java crypto provider. Native instrumentation and the Android application use the real Android provider; the workaround does not alter the APK. The current host suite contains 116 tests, including 45 shared Android-dependent cases.

Schema exports are registered as host classpath resources and native test-APK assets. Test fixture names are kept short to avoid Windows native SQLite path limits. The Android 16/API 36 emulator uses 4 KB pages; static ZIP-offset and ELF PT_LOAD alignment checks across all four packaged ABIs are not a 16 KB-page runtime test.

Gradle/AGP, Java native-access, and AndroidX native-symbol build-console warnings are distinct from Android lint. The validation record reports actual lint results.

Keep SDK paths, caches, build outputs, private samples, and signing keys out of Git. The public APK is a debug artifact, not a Play Store release.
