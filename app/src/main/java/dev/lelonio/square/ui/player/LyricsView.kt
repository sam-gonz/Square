package dev.lelonio.square.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.LyricTimeline
import dev.lelonio.square.data.Lyrics
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Line-level lyrics: only a change of line updates the list, with no frame clock or glyph effects. */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
    showTranslation: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    val listState = remember(lyrics) { LazyListState() }
    val timeline = remember(lyrics) { LyricTimeline(lyrics) }
    val activeLine by remember(timeline, positionMs) {
        derivedStateOf { timeline.activeLineAt(positionMs.value) }
    }

    LaunchedEffect(activeLine, listState) {
        if (activeLine < 0) return@LaunchedEffect
        // Opening midway through a song can precede the list's first layout.
        val layout = snapshotFlow { listState.layoutInfo }.first {
            it.viewportSize.height > 0 && it.visibleItemsInfo.isNotEmpty()
        }
        // Let the listener read/scroll without fighting a line transition.
        if (listState.isScrollInProgress) return@LaunchedEffect
        val item = layout.visibleItemsInfo.find { it.index == activeLine }
        if (item == null) {
            // A seek or opening late in a song should not animate through all preceding lines.
            listState.scrollToItem(activeLine, -layout.viewportSize.height / 3)
        } else {
            val centre = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            val delta = item.offset + item.size / 2 - centre
            if (abs(delta) > 4) listState.animateScrollBy(delta.toFloat(), tween(180))
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(vertical = 48.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            LyricLineRow(
                line = line,
                highlighted = !lyrics.synced || index == activeLine,
                synced = lyrics.synced,
                showTranslation = showTranslation,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun LyricLineRow(
    line: LyricLine,
    highlighted: Boolean,
    synced: Boolean,
    showTranslation: Boolean,
    onSeek: (Long) -> Unit,
) {
    val ink = if (highlighted) GlassInk else GlassInk.copy(alpha = 0.38f)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = synced && line.startTimeMs != null) { line.startTimeMs?.let(onSeek) }
            .padding(horizontal = 24.dp, vertical = 2.dp),
    ) {
        Text(
            text = line.text.ifBlank { "♪" },
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = ink,
        )
        val translation = line.translation.takeIf { showTranslation && !it.isNullOrBlank() }
        if (translation != null) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyLarge,
                color = ink.copy(alpha = ink.alpha * 0.75f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
