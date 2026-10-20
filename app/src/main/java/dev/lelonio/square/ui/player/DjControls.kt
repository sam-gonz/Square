package dev.lelonio.square.ui.player

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowUUpLeft
import com.adamglin.phosphoricons.regular.Check
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.DjSession
import dev.lelonio.square.playback.SleepTimer
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.pressable

@Composable
internal fun rememberDjZone(state: PlaybackState, remote: Boolean): ClosedFloatingPointRange<Float>? {
    val app = LocalContext.current.applicationContext as SquareApplication
    val settings by app.djMode.settings.collectAsStateWithLifecycle()
    val crossfade by app.crossfade.seconds.collectAsStateWithLifecycle()
    val zone by DjSession.zone.collectAsStateWithLifecycle()
    val kept by DjSession.kept.collectAsStateWithLifecycle()
    val atTrackEnd by SleepTimer.atTrackEnd.collectAsStateWithLifecycle()
    return if (!remote && settings.enabled && crossfade > 0 && state.hasNext &&
        !atTrackEnd && state.repeatMode != androidx.media3.common.Player.REPEAT_MODE_ONE && kept != state.mediaId
    ) zone?.fractionFor(state.mediaId, state.durationMs) else null
}

/** The same DJ switch, keep-this-song and undo controls as the personal build. No ticking UI state. */
@Composable
internal fun RowScope.DjControls(mediaId: String?, backdrop: Backdrop, visible: Boolean) {
    if (!visible) return
    val context = LocalContext.current
    val app = context.applicationContext as SquareApplication
    val settings by app.djMode.settings.collectAsStateWithLifecycle()
    val crossfade by app.crossfade.seconds.collectAsStateWithLifecycle()
    val zone by DjSession.zone.collectAsStateWithLifecycle()
    val kept by DjSession.kept.collectAsStateWithLifecycle()
    val undo by DjSession.undo.collectAsStateWithLifecycle()
    if (undo != null) {
        DjPill(backdrop, stringResource(R.string.dj_undo), false, DjSession::undo) {
            Icon(PhosphorIcons.Regular.ArrowUUpLeft, null, Modifier.size(16.dp))
        }
    }
    val description = stringResource(if (settings.enabled) R.string.dj_mode_on else R.string.dj_mode_off)
    DjPill(backdrop, stringResource(R.string.dj_chip), settings.enabled, {
        app.djMode.setEnabled(!settings.enabled)
        if (!settings.enabled && crossfade <= 0) {
            Toast.makeText(context, R.string.dj_mode_needs_crossfade, Toast.LENGTH_LONG).show()
        }
    }, description) {
        Icon(painterResource(if (settings.enabled) R.drawable.ic_dj_on else R.drawable.ic_dj_off), null, Modifier.size(18.dp))
    }
    val playingInFull = mediaId != null && mediaId == kept
    if (settings.enabled && mediaId != null && (playingInFull || zone?.mediaId == mediaId)) {
        DjPill(backdrop, stringResource(if (playingInFull) R.string.dj_kept_song else R.string.dj_keep_song), playingInFull,
            { DjSession.toggleKept(mediaId) }) {
            Icon(PhosphorIcons.Regular.Check, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DjPill(
    backdrop: Backdrop, label: String, active: Boolean, onClick: () -> Unit,
    description: String = label, icon: @Composable () -> Unit,
) {
    GlassSurface(backdrop, RoundedCornerShape(50), Modifier
        .semantics { contentDescription = description }
        .pressable(onClick)) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (active) ConnectedInk else GlassInkDim,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
