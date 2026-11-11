package com.ritim.ritimcraw.data

/**
 * The E2 deletion seam (owner finding 2026-11-11 E2: "library'deki
 * videoları silme yapılmıyor. basılı tutup çöp kutusu ile library'yi
 * temizleme yok" — long-press to select, trash to clean the library):
 * ONE place composes every store remove a clip delete touches, so the
 * deletion scope is a rule, not an accident of wiring.
 *
 * What a delete REMOVES — the app's record and its derived data only:
 *  - the MediaSource record (project.json "sources" section);
 *  - the clip's persisted grade (project.json "grades" section) — a
 *    re-import of the same file gets the same fingerprint id back, and
 *    with the grade row gone it opens NEUTRAL, never on a stale grade;
 *  - the persisted thumbnail PNG (files/thumbnails/&lt;id&gt;.png).
 *
 * What a delete NEVER touches:
 *  - the user's ORIGINAL SAF file: no store here writes to (or deletes)
 *    the source uri — the app only ever held a read grant;
 *  - export history rows (project.json "exports"): their grade snapshots
 *    were captured at enqueue and are self-contained, so DONE rows keep
 *    opening their already-written output; ExportsScreen falls back to
 *    the source id for the row name;
 *  - named presets and LUT cube copies (shared sections, clip-agnostic).
 *
 * Every underlying remove is idempotent: missing ids are no-ops, and a
 * delete never disturbs other clips' rows, grades or thumbnails.
 */
object ClipDeletion {

    /**
     * Deletes each clip in [ids] per the scope in the object KDoc.
     * Missing ids are no-ops in all three stores (delete-then-refresh
     * never races a concurrent removal).
     */
    fun delete(
        ids: Collection<String>,
        sources: SourceRepository,
        grades: GradeStore,
        thumbnails: ThumbnailStore,
    ) {
        for (id in ids) {
            sources.remove(id)
            grades.remove(id)
            thumbnails.remove(id)
        }
    }
}
