package com.simoscal.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileProviderBoundaryTest {

    @Test
    fun stagedBundlesAreShareableButImportedRecommendationsAreNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.fileprovider"
        val bundle = File(context.filesDir, "staging/bundles/test/bundle.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        val imported = File(context.filesDir, "imports/test.advice.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }

        try {
            val uri = FileProvider.getUriForFile(context, authority, bundle)
            assertEquals("content", uri.scheme)
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, imported)
            }
        } finally {
            bundle.parentFile?.deleteRecursively()
            imported.delete()
        }
    }
}
