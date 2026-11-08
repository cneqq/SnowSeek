package dev.snowseek.app.local

import java.io.File

internal data class InstalledModel(
    val id: String,
    val displayName: String,
    val format: LocalModelFormat,
    val file: File,
    val fileSizeBytes: Long,
    val source: String,
    val validation: String,
    val memoryEstimate: String?,
)
