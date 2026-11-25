package com.ritim.ritimcraw.proxy

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Flat name-addressed storage the cache sits on. Names never contain separators. */
interface ProxyStorage {
    fun names(): List<String>
    fun exists(name: String): Boolean
    fun size(name: String): Long
    fun lastModified(name: String): Long
    fun setLastModified(name: String, ms: Long): Boolean
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    /** Real file for [name] (a writer such as MediaMuxer needs a path). */
    fun fileFor(name: String): File
    /** Atomic rename, replacing [to] if present. */
    fun moveAtomic(from: String, to: String)
    fun delete(name: String): Boolean
}

/** java.io.File implementation rooted at [root] (created on demand). */
class DirProxyStorage(private val root: File) : ProxyStorage {
    init { root.mkdirs() }

    override fun names(): List<String> = root.list()?.sorted() ?: emptyList()
    override fun exists(name: String) = fileFor(name).isFile
    override fun size(name: String) = fileFor(name).length()
    override fun lastModified(name: String) = fileFor(name).lastModified()
    override fun setLastModified(name: String, ms: Long) = fileFor(name).setLastModified(ms)
    override fun read(name: String): ByteArray? =
        try { fileFor(name).readBytes() } catch (_: java.io.IOException) { null }
    override fun write(name: String, bytes: ByteArray) { fileFor(name).writeBytes(bytes) }
    override fun fileFor(name: String): File {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "bad name: $name" }
        return File(root, name)
    }
    override fun moveAtomic(from: String, to: String) {
        Files.move(fileFor(from).toPath(), fileFor(to).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    override fun delete(name: String) = fileFor(name).delete()
}
