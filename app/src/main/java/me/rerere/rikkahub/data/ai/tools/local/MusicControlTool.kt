package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Control media playback and get current music information.
 * Uses MediaController to interact with the active media session.
 *
 * NOTE: On Android, controlling media requires the app to be a notification
 * listener OR the media app to explicitly allow external control. The tool
 * will attempt control but may return limited results depending on the
 * active media app's configuration.
 */
internal fun buildMusicControlTool(context: Context): Tool = Tool(
    name = "music_control",
    description = """
        Get current music playback information (artist, title, album, position,
        playing/paused state) or control playback (play, pause, skip next/previous).
        Works with most music apps that expose a media session.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonObject {
                        // can't use buildJsonArray at import time; described inline
                    })
                    put("description", "Action: 'info' (get current track info, default), 'play', 'pause', 'toggle', 'next', 'previous', 'seek_to' (needs position_ms).")
                })
                put("position_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Seek position in milliseconds. Only used with action='seek_to'.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "info"

        val controller = getActiveMediaController(context)

        if (action == "info") {
            val metadata = controller?.metadata
            val playbackState = controller?.playbackState

            if (metadata == null) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("playing", false)
                    put("message", "No active media session found. Start playing music first.")
                }.toString()))
            }

            val payload = buildJsonObject {
                put("playing", playbackState?.state == PlaybackState.STATE_PLAYING)
                put("title", metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown")
                put("artist", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown")
                put("album", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                if (duration > 0) {
                    put("duration_ms", duration)
                    put("duration_seconds", duration / 1000)
                }
                if (playbackState != null) {
                    put("position_ms", playbackState.position)
                    put("position_seconds", playbackState.position / 1000)
                    put("state", stateToString(playbackState.state))
                    put("speed", playbackState.playbackSpeed)
                }
                put("package", controller?.packageName ?: "unknown")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            if (controller == null) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "NO_SESSION")
                    put("message", "No active media session to control.")
                }.toString()))
            }

            val transportControls = controller.transportControls
            when (action) {
                "play" -> transportControls.play()
                "pause" -> transportControls.pause()
                "toggle" -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        transportControls.pause()
                    } else {
                        transportControls.play()
                    }
                }
                "next" -> transportControls.skipToNext()
                "previous" -> transportControls.skipToPrevious()
                "seek_to" -> {
                    val pos = params["position_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (pos != null) {
                        transportControls.seekTo(pos)
                    } else {
                        return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                            put("error", "MISSING_POSITION")
                            put("message", "position_ms is required for seek_to action.")
                        }.toString()))
                    }
                }
                else -> {
                    return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "INVALID_ACTION")
                        put("message", "Unknown action: $action. Use info, play, pause, toggle, next, previous, or seek_to.")
                    }.toString()))
                }
            }

            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("action", action)
            }.toString()))
        }
    }
)

/**
 * Get the currently active MediaController for the playing media session.
 * Returns null if no active session exists.
 */
private fun getActiveMediaController(context: Context): MediaController? {
    val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    val componentName = ComponentName(context, NotificationCaptureService::class.java)
    val notificationIntent = Intent().setComponent(componentName)

    // We don't have PendingIntent access to bind, so use a simpler approach:
    // query active media sessions
    var controller: MediaController? = null
    val latch = CountDownLatch(1)

    val sessions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        android.media.session.MediaSessionManager.RemoteSessionCallback { sessions ->
            controller = sessions.firstOrNull()?.let { token ->
                try {
                    MediaController(context, token)
                } catch (e: Exception) {
                    null
                }
            }
            latch.countDown()
        }.let { callback ->
            mediaSessionManager.registerRemoteSessionCallback({ command -> command.run() }, callback)
            latch.await(2, TimeUnit.SECONDS)
            mediaSessionManager.unregisterRemoteSessionCallback(callback)
            controller
        }
    } else {
        @Suppress("DEPRECATION")
        val activeSessions = mediaSessionManager.getActiveSessions(componentName)
        controller = activeSessions.firstOrNull()?.let { ctrl ->
            MediaController(context, ctrl.sessionToken)
        }
        controller
    }

    return controller
}

private fun stateToString(state: Int): String = when (state) {
    PlaybackState.STATE_PLAYING -> "playing"
    PlaybackState.STATE_PAUSED -> "paused"
    PlaybackState.STATE_STOPPED -> "stopped"
    PlaybackState.STATE_BUFFERING -> "buffering"
    PlaybackState.STATE_CONNECTING -> "connecting"
    PlaybackState.STATE_ERROR -> "error"
    PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
    PlaybackState.STATE_REWINDING -> "rewinding"
    PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping"
    else -> "none"
}
