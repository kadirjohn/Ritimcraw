package com.ritim.ritimcraw.engine

/**
 * F1-8d: turns a node's `lutId` (the LUT library's fingerprint id) into the native handle the node chain wire
 * carries. The cube is parsed ONCE natively (core parseCubeFile, cached by path in NodeLutRegistry) and the
 * handle is cached here by id: library cubes are immutable, content-addressed blobs, so an id never changes
 * what it names. [pathFor] is installed by AppNav from the LutStore; [register] defaults to the JNI door and is
 * replaceable in tests. No resolver / an unreadable cube / a failed parse answer 0: the wire encoder then refuses
 * honestly (the render falls back to the CPU path that carries the LUT itself), never silently drops the LUT.
 */
object NodeLutHandles {
    @Volatile var pathFor: (String) -> String? = { null }
    @Volatile var register: (String) -> Int = { NativeSource.jniRegisterNodeLut(it) }
    private val cache = HashMap<String, Int>()

    @Synchronized
    fun handleFor(lutId: String): Int {
        cache[lutId]?.let { return it }
        val path = pathFor(lutId) ?: return 0
        val h = register(path)
        if (h > 0) cache[lutId] = h
        return h
    }

    @Synchronized
    internal fun clearForTest() = cache.clear()
}
