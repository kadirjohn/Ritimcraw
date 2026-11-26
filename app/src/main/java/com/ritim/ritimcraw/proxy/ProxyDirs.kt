package com.ritim.ritimcraw.proxy

import java.io.File

/**
 * Where the proxy cache lives on Android (docs/PROXY_PLAN.md section 3.1):
 * `filesDir/cache/proxies/` — `filesDir`, NOT `cacheDir`, the OS may clear
 * `cacheDir` and proxies take minutes to build. The same layout and
 * reasoning as `ImageStabAndroid.cacheDir` (`filesDir/cache/imagestab`).
 *
 * Pure path helper only (java.io.File, no Android classes): wiring a
 * `DirProxyStorage` at this path into app flows is a later phase, not here.
 */
object ProxyDirs {

    /** The proxy cache directory under the app's [filesDir]. */
    fun cacheDir(filesDir: File): File = File(File(filesDir, "cache"), "proxies")

    /** Same as [cacheDir], as a convenience for constructing the storage directly. */
    fun storage(filesDir: File): DirProxyStorage = DirProxyStorage(cacheDir(filesDir))
}
