# R8 rules for the *instrumented test* APK only — never for the shipped app.
#
# Only consulted when the androidTest APK is itself minified, i.e. when
# `connectedAndroidTest` runs against the release variant via -PtestReleaseBuild.
# Wired in through `testProguardFiles`, deliberately separate from
# `proguard-rules.pro` so nothing here can widen what ships.

# androidx.test's Tracer is annotated with Error Prone's @MustBeClosed, which is
# a compile-only annotation and is absent at runtime by design. R8 treats the
# dangling reference as an error; the annotation has no runtime behaviour to
# preserve, so warning off it is the whole fix.
-dontwarn com.google.errorprone.annotations.**
