package dev.lelonio.square.ui.player

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import dev.lelonio.square.backend.youtube.SkipFadePlayer

/** Queue-order command shared by the app controller and playback session. */
object QueueCommands {
    const val SET_ORDER = "dev.lelonio.square.SET_QUEUE_ORDER"
    const val ORDER = "queue_order"
}

/**
 * Moves real timeline indices in the order currently shown by the queue.
 * `beforeIndex == null` puts them at the end. The service applies the resulting
 * permutation to ExoPlayer's ShuffleOrder when shuffle is enabled.
 */
fun moveQueueBefore(player: Player?, indices: List<Int>, beforeIndex: Int?) {
    player ?: return
    val itemCount = player.mediaItemCount
    val shuffle = player.shuffleModeEnabled
    val timeline = player.currentTimeline
    val first = timeline.getFirstWindowIndex(shuffle).takeIf { it in 0 until itemCount } ?: 0
    val order = timelineQueueOrder(itemCount, first) { index ->
        timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    val moved = moveTimelineIndicesBefore(order, indices, beforeIndex)
    if (moved == order) return

    if (player is MediaController) {
        val args = Bundle().apply { putIntArray(QueueCommands.ORDER, moved.toIntArray()) }
        player.sendCustomCommand(
            SessionCommand(QueueCommands.SET_ORDER, Bundle.EMPTY),
            args,
        )
    } else {
        applyQueueOrder(player, moved)
    }
}

/** Applies a complete permutation through Player's move operation. */
internal fun applyQueueOrder(player: Player, order: List<Int>) {
    if (order.size != player.mediaItemCount || order.toSet() != (0 until order.size).toSet()) return
    if (player.shuffleModeEnabled) {
        skipFadeOf(player)?.setQueueOrder(order)
        if (skipFadeOf(player) != null) return
    }
    val workingOrder = (0 until order.size).toMutableList()
    order.forEachIndexed { targetIndex, itemIndex ->
        val fromIndex = workingOrder.indexOf(itemIndex)
        if (fromIndex != targetIndex) {
            player.moveMediaItem(fromIndex, targetIndex)
            workingOrder.add(targetIndex, workingOrder.removeAt(fromIndex))
        }
    }
}

internal fun skipFadeOf(player: Player): SkipFadePlayer? = when (player) {
    is SkipFadePlayer -> player
    is androidx.media3.common.ForwardingPlayer -> skipFadeOf(player.wrappedPlayer)
    else -> null
}
