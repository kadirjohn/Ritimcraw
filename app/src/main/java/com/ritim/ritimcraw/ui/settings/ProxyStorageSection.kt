package com.ritim.ritimcraw.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.proxy.ProxyClipUsage
import com.ritim.ritimcraw.proxy.ProxyStorageState
import com.ritim.ritimcraw.ui.components.SettingsGroup
import com.ritim.ritimcraw.ui.components.SettingsGroupItem
import com.ritim.ritimcraw.ui.components.SettingRowBase
import java.util.Locale

/**
 * The proxy storage page section (G3, docs/PROXY_PLAN.md section 6 + the P4
 * acceptance row): usage against the cap, the per-clip bytes, the honest
 * "X GB over the cap" line (owner decision 2026-11-26 item 2: the line shows
 * while over, and the number is the pure [ProxyStorageState.usage] values),
 * per-clip delete and the clear-all action — all over the pure
 * [ProxyStorageState] feed; this composable holds no state of its own.
 *
 * UI patterns copied from the existing settings rows (the animation rule):
 *  - rows are [SettingRowBase] inside [SettingsGroupItem] (the Storage &
 *    device group's own shape, SettingsComponents.kt:292);
 *  - the clip list expands with AnimatedVisibility + expandVertically +
 *    fadeIn, tween(200) — SettingRowExpandable's own expand spec
 *    (SettingsComponents.kt:423-426), the same duration/easing the
 *    expandable rows use everywhere;
 *  - every tap target is >= 48dp (IconButton's default) and the buttons
 *    carry heightIn(min = 48.dp) — the exports rows' own Open button shape
 *    (ExportsScreen.kt:729-734).
 *
 * Ships read-only while the feature flag is false: the delete/clear actions
 * arrive as null callbacks and the section renders the state without them
 * (the page still shows the cache's real numbers — usage is a read).
 */
@Composable
fun ProxyStorageSection(
    state: ProxyStorageState,
    /** The display name for each fingerprint (the clip rows read it); null = the id. */
    nameFor: (String) -> String?,
    /** Per-clip delete; null while the feature flag is off (read-only page). */
    onDeleteClip: ((String) -> Unit)? = null,
    /** Clear-all; null while the feature flag is off. */
    onClearAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // ---- The usage row: total against the cap ----
        SettingsGroup {
            SettingsGroupItem(index = 0, count = if (state.perClip.isEmpty()) 1 else 2) {
                SettingRowBase(
                    icon = com.ritim.ritimcraw.ui.components.Folder,
                    title = stringResource(R.string.proxy_storage_title),
                    hint = proxyUsageHint(state),
                )
                // The usage bar under the top line: the same 16dp gutter the
                // expandable rows' control slot uses (the note box pattern).
                if (state.usage.capBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            val fraction = state.usage.totalBytes.toFloat() / state.usage.capBytes
                            fraction.coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp),
                        color = if (state.usage.isOverCap) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            // ---- The over-cap line (owner decision 2026-11-26 item 2) ----
            AnimatedVisibility(
                visible = state.usage.isOverCap,
                enter = expandVertically(animationSpec = tween(200)) +
                    fadeIn(animationSpec = tween(200)),
            ) {
                SettingsGroupItem(index = 1, count = 2) {
                    Text(
                        text = stringResource(
                            R.string.proxy_storage_over_cap,
                            formatGb(state.usage.overCapBytes),
                        ),
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // ---- The per-clip rows ----
        if (state.perClip.isNotEmpty()) {
            SettingsGroup {
                state.perClip.forEachIndexed { i, clip ->
                    SettingsGroupItem(index = i, count = state.perClip.size) {
                        ProxyClipRow(
                            clip = clip,
                            name = nameFor(clip.fingerprint) ?: clip.fingerprint.take(8),
                            queued = clip.fingerprint in state.queued,
                            building = clip.fingerprint in state.building,
                            failed = state.failed[clip.fingerprint],
                            onDelete = onDeleteClip?.let { delete -> { delete(clip.fingerprint) } },
                        )
                    }
                }
            }
            if (onClearAll != null) {
                OutlinedButton(
                    onClick = onClearAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.proxy_storage_clear_all))
                }
            }
        }
    }
}

/** One clip's proxy row: name, bytes, the honest queue state, the delete button. */
@Composable
private fun ProxyClipRow(
    clip: ProxyClipUsage,
    name: String,
    queued: Boolean,
    building: Boolean,
    failed: String?,
    onDelete: (() -> Unit)?,
) {
    val statusHint = when {
        building -> stringResource(R.string.proxy_clip_building)
        queued -> stringResource(R.string.proxy_clip_queued)
        failed != null -> stringResource(R.string.proxy_clip_failed, failed)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val bytesLine = formatMb(clip.bytes) +
                (statusHint?.let { " · $it" } ?: "")
            Text(
                text = bytesLine,
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = if (failed != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
            )
        }
        if (onDelete != null) {
            // The exports row's own delete affordance shape: a 48dp
            // IconButton with the delete icon (the swipe-delete's icon).
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.proxy_clip_delete, name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** The usage row's hint: "X GB of Y GB" (the SystemMonitorBar's own formatGb rule: decimal GB). */
@Composable
private fun proxyUsageHint(state: ProxyStorageState): String =
    stringResource(
        R.string.proxy_storage_usage,
        formatGb(state.usage.totalBytes),
        formatGb(state.usage.capBytes),
    )

/** Decimal GB (1e9), the SystemStatsMath.formatGb convention the monitor strip already shows. */
private fun formatGb(bytes: Long): String {
    val gb = bytes / 1e9
    return if (gb >= 10.0) String.format(Locale.ROOT, "%.0f", gb)
    else String.format(Locale.ROOT, "%.1f", gb)
}

/** MiB (the export report's megabytes convention, 2^20). */
private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 100.0) String.format(Locale.ROOT, "%.0f MB", mb)
    else if (mb >= 10.0) String.format(Locale.ROOT, "%.1f MB", mb)
    else String.format(Locale.ROOT, "%.2f MB", mb)
}
