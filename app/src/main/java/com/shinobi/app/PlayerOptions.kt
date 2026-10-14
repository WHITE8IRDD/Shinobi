package com.shinobi.app

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same menu in portrait and landscape; no inert entries or simulated report endpoint. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerOptionsSheet(state: PlayerState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(Loc.t("Player options"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Option(Loc.t("Quality") + " · " + (state.playingFormat ?: state.details?.quality ?: Loc.t("Auto"))) { state.sheet = PlayerSheet.Quality }
            Option(Loc.t("Captions")) { state.sheet = PlayerSheet.Captions }
            Text(Loc.t("Playback speed") + " · ${PlaybackSpeed.label(state.speed)}×", Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
                    FilterChip(selected = state.speed == speed, onClick = { state.previewSpeed(speed); state.commitSpeed(speed) }, label = { Text("${PlaybackSpeed.label(speed)}×") })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Loc.t("Autoplay next"), Modifier.padding(vertical = 14.dp))
                Switch(AppSettings.autoplayNext, AppSettings::setAutoplay)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Loc.t("Loop"), Modifier.padding(vertical = 14.dp))
                Switch(state.looping, state::setLooping)
            }
            Option(Loc.t(if (AudioOnly.enabled) "Video playback" else "Audio only / background playback")) {
                state.setAudioOnly(!AudioOnly.enabled); onDismiss()
            }
            Option(Loc.t("Queue")) { state.sheet = PlayerSheet.Queue }
            Option(Loc.t("Report on YouTube")) {
                // Reports require YouTube's authenticated UI, not a fake local confirmation.
                state.current?.id?.let { id ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, YouTube.watchUrl(id).toUri())) }
                        .onFailure { state.actionMessage = Loc.t("No browser is available.") }
                }
                onDismiss()
            }
            Text(Loc.t("Playback stats"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("${state.playingFormat ?: "—"} · ${PlaybackSpeed.label(state.speed)}×\n" +
                Loc.t("Buffered") + ": ${formatDuration((state.bufferedMs - state.positionMs).coerceAtLeast(0L))}\n" +
                Loc.t("Video") + ": ${state.current?.id.orEmpty()}", fontSize = 13.sp)
            if (state.details?.chapters?.isNotEmpty() == true) {
                Text(Loc.t("Chapters"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                state.details?.chapters?.forEach { chapter ->
                    Option("${formatDuration(chapter.startMs)} · ${chapter.title}") { state.seekTo(chapter.startMs); onDismiss() }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Option(label: String, click: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 14.dp))
}
