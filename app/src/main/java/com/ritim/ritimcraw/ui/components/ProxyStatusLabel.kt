package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.proxy.ProxyStatus

/** The status line's text for one proxy status; null = show nothing. */
@Composable
fun proxyStatusText(status: ProxyStatus): String? = when (status) {
    ProxyStatus.None -> null
    ProxyStatus.Queued -> stringResource(R.string.proxy_status_queued)
    is ProxyStatus.Preparing -> stringResource(R.string.proxy_status_preparing, status.percent)
    ProxyStatus.Ready -> stringResource(R.string.proxy_status_ready)
    ProxyStatus.Failed -> stringResource(R.string.proxy_status_failed)
}

/**
 * The thin proxy status: a 12dp icon and one short 11sp line in the muted color
 * ("Proxy preparing 40%" -> "Proxy ready"); nothing at all without a proxy.
 * Failed is the same size, only tinted with the error color.
 */
@Composable
fun ProxyStatusLabel(status: ProxyStatus, modifier: Modifier = Modifier) {
    val text = proxyStatusText(status) ?: return
    val tint = if (status == ProxyStatus.Failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (status) {
                ProxyStatus.Ready -> Icons.Filled.Check
                ProxyStatus.Failed -> Icons.Filled.Warning
                else -> Icons.Filled.Refresh
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(text = text, style = TextStyle(fontSize = 11.sp), color = tint, maxLines = 1)
    }
}
