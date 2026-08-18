package com.simoscal.quickedit

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * The embedded kernel must use the exact NumPy proven by the cross-runtime
 * parity gate (CR-20260724-05). An unchanged APK must not change numeric
 * behaviour merely because a newer compatible wheel was published.
 *
 * This assertion used to live in the simoscal repo's `tests/test_packaging.py`,
 * reading this file across the old single-repo layout. The two repos split on
 * 2026-08-18, so the guard moved to the repo that actually owns the file.
 */
class NumpyPinTest {

    @Test
    fun `the embedded numpy runtime is pinned to the parity-proven version`() {
        val gradle = File("build.gradle.kts")
        assertTrue(
            "expected engine/build.gradle.kts relative to the module dir; got ${gradle.absolutePath}",
            gradle.isFile,
        )
        assertTrue(
            "the embedded NumPy must stay pinned to the parity-proven 1.26.2",
            gradle.readText().contains("install(\"numpy==1.26.2\")"),
        )
    }
}
