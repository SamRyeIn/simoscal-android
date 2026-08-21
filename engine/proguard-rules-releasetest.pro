# Extra app-side keeps that exist ONLY so the instrumented suite can run against
# the minified release variant (-PtestReleaseBuild). NOT part of a shipping build.
#
# Why this is a separate, property-gated file rather than lines in
# proguard-rules.pro: without the gate these keeps would widen every release
# artifact to satisfy a test harness. Gated, the default `assembleRelease` /
# `bundleRelease` output is unchanged, and only the build you deliberately point
# tests at carries them.
#
# The cost of the gate, stated plainly: the APK the instrumented suite exercises
# is therefore not byte-identical to the one that ships. The delta is confined to
# the unrelated androidx.tracing package below — the Chaquopy/JNI path that the
# suite actually exists to prove is processed identically in both.

# androidx.test's AndroidJUnitRunner calls androidx.tracing.Trace from onCreate,
# and the runner shares the *app's* classloader. androidx.tracing is on the app's
# runtime classpath (transitively) but the app itself never calls it, so R8
# correctly strips it from the app APK — and the runner then dies with
# NoClassDefFoundError before a single test method runs.
-keep class androidx.tracing.Trace { *; }
-dontwarn androidx.tracing.**

# Same shape of problem, wider surface: androidx.test is partly written in Kotlin
# and resolves stdlib helpers (kotlin.LazyKt, etc.) out of the app's classloader.
# The app's own Kotlin usage is compiled down to the handful of stdlib members it
# actually touches, so R8 keeps only those and the runner dies on the rest.
# Breadth is deliberate and safe here precisely because this file is gated out of
# every shipping build — the alternative is rediscovering one missing stdlib class
# per run.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# The instrumented tests address SimoscalBridge / SimoscalEngine by their source
# names, but R8 renames them (SimoscalBridge became `a3.e`), so the test APK binds
# against a class whose INSTANCE field no longer answers to that name:
#   NoSuchFieldError: No field INSTANCE of type La3/e;
# Pinning the names costs nothing in fidelity for what this suite exists to prove.
# Whether the *bridge* is obfuscated is irrelevant to the Chaquopy question — the
# app's own UI calls it through the same renamed symbols either way, and the
# com.chaquo.python keeps under test are in proguard-rules.pro, untouched here.
-keep class com.simoscal.engine.SimoscalBridge { *; }
-keep class com.simoscal.engine.SimoscalBridge$** { *; }
-keep class com.simoscal.engine.SimoscalEngine { *; }
