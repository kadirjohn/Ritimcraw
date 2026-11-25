package com.ritim.ritimcraw.data.grade2

/**
 * MIGRATIONS[0] — schema 1 -> 2 (color model v2 F1-3, docs/COLOR_MODEL_V2_PLAN.md
 * §6). A PURE root map -> root map function (the ProjectMigrations contract),
 * so it is JVM-testable and crash-safe: the whole root is migrated in memory
 * and only then atomically written ([ProjectFile.open]'s single write). Every
 * grade-carrying fiber is converted by the SAME field mapping:
 *
 *   - `grades` (the per-clip fiber, GradeStore): each record is re-nested
 *     into the v2 group shape ([ClipGradeV2]);
 *   - `sources` (the inputless-clip rule, §6.3.1): every source id with NO
 *     grade record gets an EXPLICIT neutral legacy record materialized, so a
 *     future non-legacy default (K3) can never silently change how an
 *     existing-but-never-graded clip looks;
 *   - `presets`: each preset's `grade` converts with the same mapping (§6.1);
 *   - `exports`: each job's `snapshot` converts with the same mapping; a
 *     RUNNING job stops RUNNING (K13) — the app was killed mid-export, so the
 *     row lands as FAILED with an honest reason instead of a zombie that
 *     blocks the queue; QUEUED jobs migrate their snapshot and keep waiting.
 *
 * ROW TOLERANCE (§6.4.4): a record this step cannot interpret (not an object,
 * or a grade whose fields are wrong-typed) is carried RAW — never dropped,
 * never guessed. The chain is also IDEMPOTENT: a record that already declares
 * `v2` is left alone, so a re-run (or a step assembled differently) cannot
 * double-convert. Unknown keys inside a converted record are DROPPED from
 * the re-rendered shape but the record's own file copy (the backup) holds
 * them; the plan's swap is total — v2 owns the record shape.
 *
 * The version stamp itself is [ProjectMigrations.migrate]'s job (the chain
 * re-stamps "version" after the step returns).
 */
object GradeV2Migration {

    /** The step ProjectMigrations ships as MIGRATIONS[0]: root of v1 in, root of v2 out. */
    fun fromV1(root: Map<String, Any?>): Map<String, Any?> {
        var out = root
        out = migrateGrades(out)
        out = materializeInputlessClips(out)
        out = migratePresets(out)
        out = migrateExports(out)
        return out
    }

    // ---- the grades fiber ----

    private fun migrateGrades(root: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val grades = root["grades"] as? Map<String, Any?> ?: return root
        val migrated = LinkedHashMap<String, Any?>(grades.size)
        for ((id, record) in grades) {
            migrated[id] = convertGradeCarrier(record)
        }
        return root + ("grades" to migrated)
    }

    /**
     * The inputless-clip rule (§6.3.1, K3's explicit-record half): every
     * source with no grade record gets a NEUTRAL LEGACY record, so the
     * migrated file says "this clip is legacy-off" instead of relying on a
     * default a future build might change. Sources with a record (or a raw
     * carried one) are untouched.
     */
    private fun materializeInputlessClips(root: Map<String, Any?>): Map<String, Any?> {
        val sources = root["sources"] as? List<*> ?: return root
        @Suppress("UNCHECKED_CAST")
        val grades = (root["grades"] as? Map<String, Any?>)?.toMutableMap() ?: LinkedHashMap()
        var changed = false
        for (src in sources) {
            val m = src as? Map<*, *> ?: continue
            val id = m["id"] as? String ?: continue
            if (!grades.containsKey(id)) {
                grades[id] = neutralRecord()
                changed = true
            }
        }
        return if (changed) root + ("grades" to grades) else root
    }

    /** A neutral v2 record: every default value, explicitly (§6.3.1). */
    internal fun neutralRecord(): Map<String, Any?> = linkedMapOf(
        "v2" to ClipGradeV2.SCHEMA_VERSION.toDouble(),
        "develop" to DevelopDefaults.RENDERED,
        "legacy" to LegacyDefaults.RENDERED,
        "nodes" to emptyList<Any?>(),
        "output" to linkedMapOf<String, Any?>(
            "mode" to OutputTransformV2.MODE_LEGACY,
            "legacy" to LegacyOutputDefaults.RENDERED,
            "opendrt" to OpenDrtDefaults.RENDERED,
        ),
        // Neutral crop + no stab: the transform group renders empty (both
        // halves omit their defaults — v1's own omit rules).
        "transform" to linkedMapOf<String, Any?>(),
    )

    // ---- the presets fiber ----

    @Suppress("UNCHECKED_CAST")
    private fun migratePresets(root: Map<String, Any?>): Map<String, Any?> {
        val presets = root["presets"] as? List<Any?> ?: return root
        val out = ArrayList<Any?>(presets.size)
        for (preset in presets) {
            val m = preset as? Map<String, Any?>
            if (m == null) {
                out.add(preset) // row-tolerant: carry raw
                continue
            }
            val grade = m["grade"]
            out.add(m + ("grade" to convertGradeCarrier(grade)))
        }
        return root + ("presets" to out)
    }

    // ---- the exports fiber (K13) ----

    @Suppress("UNCHECKED_CAST")
    private fun migrateExports(root: Map<String, Any?>): Map<String, Any?> {
        val exports = root["exports"] as? List<Any?> ?: return root
        val out = ArrayList<Any?>(exports.size)
        for (job in exports) {
            val m = job as? Map<String, Any?>
            if (m == null) {
                out.add(job)
                continue
            }
            var row = m
            val snapshot = m["snapshot"]
            if (snapshot != null) {
                row = row + ("snapshot" to convertGradeCarrier(snapshot))
            }
            // K13: a RUNNING export died with the process. It cannot resume
            // (the exporter holds no resume state), so the row lands FAILED
            // with the honest reason instead of a zombie RUNNING row — the
            // user re-queues it (Retry keeps the migrated snapshot).
            if (row["state"] == "RUNNING") {
                row = row + (
                    "state" to "FAILED"
                    ) + (
                    "detail" to
                        "Export interrupted by the project upgrade (migration to schema 2) - recovery: queue the export again"
                    )
            }
            out.add(row)
        }
        return root + ("exports" to out)
    }

    // ---- the one shared grade conversion ----

    /**
     * Converts one v1 grade record (a parsed JSON object) to its v2 twin, the
     * single field mapping every fiber shares. Tolerant per §6.4.4:
     *   - not an object -> carried RAW (nullsafe: null stays null);
     *   - already v2 (`v2` key present) -> untouched (idempotence);
     *   - a field with an unexpected type -> that field's default (the v1
     *     parser's own per-field tolerance, kept).
     */
    internal fun convertGradeCarrier(record: Any?): Any? {
        if (record == null) return null
        val m = record as? Map<*, *> ?: return record
        @Suppress("UNCHECKED_CAST")
        val g = m as Map<String, Any?>
        if (g.containsKey("v2")) return g // already schema 2: idempotent
        return buildV2Record(g)
    }

    private fun buildV2Record(g: Map<String, Any?>): Map<String, Any?> {
        // The rule for every numeric carry: the FILE's own Double travels
        // VERBATIM (the migration never re-rounds a stored number — K2's
        // bit-exactness starts at the file); the Float fallback only seeds
        // a field the v1 record never wrote.
        val tempK = (g["tempK"] as? Double)?.toFloat() ?: 5600f
        val tint = (g["tint"] as? Double)?.toFloat() ?: 0f
        val develop = linkedMapOf<String, Any?>(
            "exposureEv" to carry(g["exposureEv"], 0.0),
            "tempK" to carry(g["tempK"], 5600.0),
            "tint" to carry(g["tint"], 0.0),
            "asShot" to (g["asShot"] as? Boolean ?: (tempK == 5600f && tint == 0f)),
            "highlightReconstruction" to (g["highlightReconstruction"] as? Boolean ?: true),
            "keepVignetting" to (g["keepVignetting"] as? Boolean ?: true),
            "noiseReduction" to carry(g["noiseReduction"], 0.0),
            "chromaDenoise" to carry(g["chromaDenoise"], 0.0),
            "temporalNr" to (g["temporalNr"] as? Boolean ?: false),
            "temporalStrength" to carry(g["temporalStrength"], 0.0),
            "sharpness" to carry(g["sharpness"], 0.0),
            "detail" to carry(g["detail"], 0.0),
        )
        // A stored "highlightsCurve" key (removed versioning) is deliberately NOT carried: dropped silently.
        // Only an enabled recovery is written; absent = off.
        if (g["highlightRecovery"] as? Boolean == true) develop["highlightRecovery"] = true
        val legacy = linkedMapOf<String, Any?>(
            "highlights" to carry(g["highlights"], 0.0),
            "blackPoint" to carry(g["blackPoint"], 0.0),
            "whitePoint" to carry(g["whitePoint"], 0.0),
            "shadows" to carry(g["shadows"], 0.0),
            "midtones" to carry(g["midtones"], 0.0),
            "contrast" to carry(g["contrast"], 0.0),
            "saturation" to carry(g["saturation"], 0.0),
            "wheelShadowX" to carry(g["wheelShadowX"], 0.0),
            "wheelShadowY" to carry(g["wheelShadowY"], 0.0),
            "wheelMidX" to carry(g["wheelMidX"], 0.0),
            "wheelMidY" to carry(g["wheelMidY"], 0.0),
            "wheelHighX" to carry(g["wheelHighX"], 0.0),
            "wheelHighY" to carry(g["wheelHighY"], 0.0),
            "splitShadowHue" to carry(g["splitShadowHue"], -1.0),
            "splitHighlightHue" to carry(g["splitHighlightHue"], -1.0),
            "splitBalance" to carry(g["splitBalance"], 0.0),
        )
        val legacyLut = linkedMapOf<String, Any?>(
            // The wire renders every number as a JSON double-parsed value
            // (JsonParser hands back Double), so the built record's ints
            // ride as Doubles — byte-consistency with a parsed record.
            "tonemap" to (((g["tonemap"] as? Double)?.toInt() ?: 0).toDouble()),
            "lutId" to (g["lutId"] as? String),
            "lutStrength" to carry(g["lutStrength"], 100.0),
        )
        (g["lutInterp"] as? Map<*, *>)?.let { legacyLut["lutInterp"] = it }
        val output = linkedMapOf<String, Any?>(
            "mode" to OutputTransformV2.MODE_LEGACY,
            "legacy" to legacyLut,
            "opendrt" to OpenDrtDefaults.RENDERED,
        )
        // transform: crop keys (omit-when-neutral, v1's own rule — a
        // neutral crop writes nothing), then the folded stab group (K10):
        // ALL sub values when stabilization is on, engine EXPLICIT (the
        // absent-key = gyro rule resolved once, here — a v2 writer never
        // re-derives it); NO group at all when off (§7's omit-when-off).
        val transform = LinkedHashMap<String, Any?>()
        val cropAspect = (g["cropAspect"] as? Double)?.toInt() ?: 0
        val cropX = carry(g["cropX"], 0.0)
        val cropY = carry(g["cropY"], 0.0)
        val cropW = carry(g["cropW"], 1.0)
        val cropH = carry(g["cropH"], 1.0)
        val straightenDeg = carry(g["straightenDeg"], 0.0)
        if (cropAspect != 0 || cropX != 0.0 || cropY != 0.0 || cropW != 1.0 ||
            cropH != 1.0 || straightenDeg != 0.0
        ) {
            transform["cropAspect"] = cropAspect.toDouble()
            transform["cropX"] = cropX
            transform["cropY"] = cropY
            transform["cropW"] = cropW
            transform["cropH"] = cropH
            transform["straightenDeg"] = straightenDeg
        }
        if (g["stabilization"] as? Boolean == true) {
            transform["stab"] = linkedMapOf<String, Any?>(
                "engine" to ((g["stabilizationEngine"] as? Double)?.toInt() ?: 0).toDouble(),
                "mode" to ((g["stabilizationMode"] as? Double)?.toInt() ?: 0).toDouble(),
                "strength" to carry(g["stabilizationStrength"], 50.0),
                "zoomLimit" to carry(g["stabilizationZoomLimit"], 1.25),
                "trimMs" to carry(g["stabilizationTrimMs"], 0.0),
                "focalMm" to carry(g["stabilizationFocalMm"], 0.0),
            )
        }
        return linkedMapOf<String, Any?>(
            "v2" to ClipGradeV2.SCHEMA_VERSION.toDouble(),
            "develop" to develop,
            "legacy" to legacy,
            "nodes" to emptyList<Any?>(),
            "output" to output,
            "transform" to transform,
        )
    }

    private fun num(v: Any?, fallback: Float): Float = (v as? Double)?.toFloat() ?: fallback

    /**
     * The numeric carry rule: the file's own Double travels verbatim (K2's
     * bit-exactness starts at the file — the migration never re-rounds a
     * stored number); the fallback seeds a field the record never wrote.
     */
    private fun carry(v: Any?, fallback: Double): Double = v as? Double ?: fallback
}

/** The rendered neutral group maps the migration writes for materialized records. */
private object DevelopDefaults {
    val RENDERED: Map<String, Any?> = linkedMapOf(
        "exposureEv" to 0.0,
        "tempK" to 5600.0,
        "tint" to 0.0,
        "asShot" to true,
        "highlightReconstruction" to true,
        "keepVignetting" to true,
        "noiseReduction" to 0.0,
        "chromaDenoise" to 0.0,
        "temporalNr" to false,
        "temporalStrength" to 0.0,
        "sharpness" to 0.0,
        "detail" to 0.0,
    )
}

private object LegacyDefaults {
    val RENDERED: Map<String, Any?> = linkedMapOf(
        "highlights" to 0.0,
        "blackPoint" to 0.0,
        "whitePoint" to 0.0,
        "shadows" to 0.0,
        "midtones" to 0.0,
        "contrast" to 0.0,
        "saturation" to 0.0,
        "wheelShadowX" to 0.0,
        "wheelShadowY" to 0.0,
        "wheelMidX" to 0.0,
        "wheelMidY" to 0.0,
        "wheelHighX" to 0.0,
        "wheelHighY" to 0.0,
        "splitShadowHue" to -1.0,
        "splitHighlightHue" to -1.0,
        "splitBalance" to 0.0,
    )
}

private object LegacyOutputDefaults {
    val RENDERED: Map<String, Any?> = linkedMapOf(
        "tonemap" to 0.0,
        "lutId" to null,
        "lutStrength" to 100.0,
    )
}

private object OpenDrtDefaults {
    val RENDERED: Map<String, Any?> = linkedMapOf(
        "look" to 0.0,
        "tonescale" to 0.0,
        "cwp" to 0.0,
        "cwpLimit" to 0.25,
        "grey" to 10.0,
        "display" to 0.0,
    )
}
