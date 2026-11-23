package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * App-level preview-stats persistence (the trace-dims instrument's A/B
 * toggle, 2026-11-14): the debug Settings row's choice for the GPU
 * preview's stats readback (draw 3 + the synchronous glMapBufferRange —
 * the stage the trace line's `stats=` span measures), stored in the
 * shared `files/project.json` as a TOP-LEVEL BOOLEAN SCALAR next to the
 * other stores' scalars — the RenderBackendStore pattern:
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ],
 *     "previewQuality": "HIGH",
 *     "renderBackend": "AUTO",
 *     "previewStats": false }   <- this store; omitted at default
 *
 * Omit-when-default: the default is TRUE (today's behavior — the
 * readback runs and the trace carries the stats pack), so a file that
 * never toggled stays byte-identical, and toggling off then back on
 * REMOVES the key rather than writing "true". Absent key, JSON null,
 * a non-boolean value, or a corrupt file all resolve to TRUE — a bad
 * file can never silently disable the readback.
 *
 * Sharing rules, same as every other project.json store: every write
 * re-renders the whole file but touches ONLY this store's key — the
 * other stores' entries are preserved raw ([ProjectJson.renderRaw]
 * round-trips booleans), and their writes preserve this key because
 * [ProjectJson.withSection] keeps unknown top-level entries it
 * re-renders.
 *
 * Debug-only on purpose: the row lives in the BuildConfig.DEBUG block
 * and the apply-on-start is BuildConfig.DEBUG-gated, so release builds
 * never write this key and never call the native setter — the native
 * default (on) is exactly the pre-toggle behavior there.
 */
class PreviewStatsStore(private val file: File) {

    fun get(): Boolean = synchronized(lock) { read() }

    /** Persists immediately. Writing the default REMOVES the key. */
    fun put(enabled: Boolean) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, enabled)
            file.parentFile?.mkdirs()
            // AtomicFiles via the single write gate (plan §6.5/F1-2):
            // tmp + fsync + atomic rename, and an observable no-op when
            // the process is read-only (a newer build's project file).
            ProjectFile.writeTextIfWritable(file, merged)
        }
    }

    private fun read(): Boolean {
        if (!file.exists()) return DEFAULT
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8))
                .parseValue() as? Map<*, *>
                ?: return DEFAULT
            // Absent key, JSON null, or a non-boolean value (a string
            // "false", a number) all resolve to the default.
            root[WIRE_NAME] as? Boolean ?: DEFAULT
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable at the default (the
            // GradeStore corrupt-file contract).
            DEFAULT
        }
    }

    companion object {
        // One lock for every instance: AppNav and tests can each hold a
        // store over the same file without racing.
        private val lock = Any()

        /** The shared wire key — a JSON boolean, omitted at default. */
        internal const val WIRE_NAME = "previewStats"

        /** Today's behavior: the stats readback runs. */
        internal const val DEFAULT = true

        /**
         * Renders project.json with the preview-stats key set to
         * [enabled] — or ABSENT at the default — preserving every other
         * top-level entry of [existingText] raw. The single wire-format
         * definition; pure and JVM-testable without touching the
         * filesystem.
         *
         * Render order: "version" first (preserved from the file, 1 for a
         * fresh one), then every other existing entry in encounter order,
         * then this store's key last when set. Deterministic.
         */
        internal fun mergeInto(existingText: String?, enabled: Boolean): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
            sb.append("{\"version\":")
                .append(ProjectJson.renderRaw(root?.get("version") ?: 1))
            root?.forEach { (k, v) ->
                val name = k.toString()
                if (name != "version" && name != WIRE_NAME && v != null) {
                    sb.append(',')
                        .append(ProjectJson.quote(name))
                        .append(':')
                        .append(ProjectJson.renderRaw(v))
                }
            }
            if (enabled != DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_NAME))
                    .append(':')
                    .append(enabled)
            }
            sb.append('}')
            return sb.toString()
        }
    }
}

/**
 * The stats toggle's seam: persistence plus the native apply in one
 * JVM-testable object (the constructor-injected function sink the
 * ExportStageTiming log pattern rides). AppNav injects the real applier
 * — the available-guarded [com.ritim.ritimcraw.engine.NativeSource]
 * call — so the UI's change path is exactly `persist + apply`; tests
 * inject a recording fake and lock the call order/values without any
 * Android or native dependency.
 *
 * The apply exists because the native toggle is PROCESS state (a bridge
 * static, not per-session): [applyPersisted] is the app-start path — a
 * restarted process must re-apply the persisted choice or the row
 * would show the stored state while the bridge still ran the default.
 */
class PreviewStatsToggle(
    private val store: PreviewStatsStore,
    private val apply: (Boolean) -> Unit = {},
) {

    /** The persisted value — the row's checked state. */
    fun current(): Boolean = store.get()

    /** The row's change callback: persist, then apply immediately. */
    fun change(enabled: Boolean) {
        store.put(enabled)
        apply(enabled)
    }

    /** App start: apply whatever the file says (the restart lock). */
    fun applyPersisted() {
        apply(store.get())
    }
}
