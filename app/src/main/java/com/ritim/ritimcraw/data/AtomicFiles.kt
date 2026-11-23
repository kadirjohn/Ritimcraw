package com.ritim.ritimcraw.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe whole-file replace (plan section 3.2): write a temp file in
 * the SAME directory, fsync it, then atomically rename it over the target.
 * A crash or power loss leaves either the complete old file or the
 * complete new one, never a torn mix. There is deliberately NO
 * non-atomic fallback: on failure the temp file is removed, the target is
 * untouched and the [IOException] propagates.
 */
internal object AtomicFiles {

    fun writeText(file: File, text: String) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.fd.sync() // data on disk before the rename publishes it
            }
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }
}
