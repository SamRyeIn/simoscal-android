// Versions are pinned rather than floating: the V0 gate's whole output is a
// statement about a specific runtime, and a plugin that silently moved would
// invalidate the parity result it produced.
//
// AGP 8.1.4 + Gradle 8.4 (see gradle-wrapper.properties), moved up from
// AGP 7.4.2 + Gradle 7.6.4 on 2026-08-20 to reach compileSdk 35, which Play's
// minimum targetSdk requires. The old pin's stated reason — that Gradle 8.0+
// turns Chaquopy 17's undeclared task-graph edges into a hard build failure —
// is too broad: it was measured on Gradle 9.3.0 and 8.11.1, and Gradle 8.4
// builds through the same edges cleanly (`check`, `assembleRelease` and
// `bundleRelease` all pass). Gradle tightened that validation across the 8.x
// line, so 8.4 sits in a window that later 8.x versions close.
//
// That makes this a narrow pin, not a comfortable one. Do not bump Gradle past
// 8.4 without re-running the Chaquopy Python tasks; the failure it guards
// against is a build error, so it will announce itself, but it will announce
// itself to whoever bumps it. Keeping org.gradle.parallel and
// org.gradle.caching OFF (see gradle.properties) is part of what keeps 8.4
// tolerant of those edges.
//
// The AGP 7.4 route to compileSdk 35 was tried first and is a dead end: aapt2
// from AGP 7.4 cannot parse android-35's resource table at all
// ("RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data"), so
// android.suppressUnsupportedCompileSdk does not help.
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}
