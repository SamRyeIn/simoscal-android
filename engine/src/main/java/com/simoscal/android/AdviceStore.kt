package com.simoscal.android

import android.content.Context
import android.net.Uri

/** Recommendation-file import, using the same private copy and hash as every input. */
class AdviceStore(context: Context) {
    private val imports = ImportStore(context)

    suspend fun importRecommendations(uri: Uri): ImportedFile =
        imports.importFile(uri, InputKind.RECOMMENDATIONS)
}
