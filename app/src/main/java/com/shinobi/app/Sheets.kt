package com.shinobi.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Long-press options ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOptionsSheet(video: VideoItem, player: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(video.thumbnail, Modifier.width(112.dp).aspectRatio(16f / 9f), radius = 14, blur = SensitiveFilter.shouldBlur(video))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.channel, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
        }
        OptionRow(Icons.AutoMirrored.Rounded.PlaylistPlay, Loc.t("Play next")) { player.playNext(video); onDismiss() }
        OptionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, Loc.t("Add to queue")) { player.addToQueue(video); onDismiss() }
        OptionRow(Icons.Rounded.WatchLater, Loc.t("Save to Watch later")) { player.saveToWatchLater(video); onDismiss() }
        OptionRow(Icons.Rounded.Share, Loc.t("Share")) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${video.id}")
                .putExtra(Intent.EXTRA_SUBJECT, video.title)
            context.startActivity(Intent.createChooser(send, null))
            onDismiss()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.onSurface)
        Spacer(Modifier.width(18.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

// ── Queue ──────────────────────────────────────────────────────────────────────

/** Card under the player when something is queued; opens the queue sheet. */
@Composable
fun QueueCard(player: PlayerState, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val next = player.queue.firstOrNull() ?: return
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onOpen)
            .clip(RoundedCornerShape(24.dp))
            .background(c.secondaryContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(next.thumbnail, Modifier.width(88.dp).aspectRatio(16f / 9f), radius = 12, blur = SensitiveFilter.shouldBlur(next))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Loc.t("Queue") + " · " + player.queue.size,
                color = c.onSecondaryContainer, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
            )
            Text(next.title, color = c.onSecondaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = c.onSecondaryContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(player: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Loc.t("Queue"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.weight(1f))
            if (player.queue.isNotEmpty()) {
                Text(
                    Loc.t("Clear"), color = c.primary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                    modifier = Modifier.pressable { player.clearQueue() }.padding(8.dp),
                )
            }
        }
        if (player.queue.isEmpty()) {
            Text(
                Loc.t("Nothing queued. Press and hold a video to add it."),
                color = c.onSurfaceVariant, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxHeight(0.7f)) {
                itemsIndexed(player.queue, key = { i, v -> "$i-${v.id}" }) { index, video ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        VideoRow(video, onClick = { player.playFromQueue(index); onDismiss() }) {
                            Box(Modifier.size(40.dp).pressable { player.removeFromQueue(index) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, Loc.t("Remove from queue"), tint = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionsSheet(player: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Text(
            Loc.t("Captions"),
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Text(
            Loc.t("Choose a language. Your choice is remembered on this device."),
            color = c.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        )
        CaptionOption(label = Loc.t("Off"), selected = player.captionTrack == null) {
            player.setCaption(null)
            onDismiss()
        }
        if (player.captionTracks.isEmpty()) {
            Text(
                Loc.t("No caption tracks are available for this video."),
                color = c.onSurfaceVariant, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        } else {
            player.captionTracks.forEach { track ->
                val label = track.language.ifBlank { track.languageTag }
                CaptionOption(
                    label = label,
                    subtitle = Loc.t(if (track.autoGenerated) "Auto-generated" else "Creator-provided"),
                    selected = player.captionTrack?.key == track.key,
                ) {
                    player.setCaption(track)
                    onDismiss()
                }
            }
        }
        if (player.captionsLoading) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(Loc.t("Loading captions…"), color = c.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        player.captionsError?.let {
            Text(it, color = c.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CaptionOption(label: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.primaryContainer else c.primaryContainer.copy(alpha = 0f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Subtitles, null, tint = if (selected) c.onPrimaryContainer else c.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (selected) c.onPrimaryContainer else c.onSurface)
            subtitle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
        }
        if (selected) Icon(Icons.Rounded.Check, Loc.t("Selected"), tint = c.onPrimaryContainer)
    }
}

// ── SponsorBlock settings (inside the account sheet) ───────────────────────────

@Composable
fun SponsorBlockSettings() {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Loc.t("SponsorBlock"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(Loc.t("Skip sponsor reads and other segments"), color = c.onSurfaceVariant, fontSize = 13.sp)
            }
            Switch(
                checked = SponsorBlock.enabled,
                onCheckedChange = SponsorBlock::updateEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = c.primary),
            )
        }
        if (SponsorBlock.enabled) {
            Spacer(Modifier.height(10.dp))
            SponsorBlock.Category.entries.forEach { category -> CategoryRow(category) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            Loc.t("Segments from SponsorBlock (sponsor.ajay.app), a community project. Data licensed CC BY-NC-SA 4.0."),
            color = c.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun CategoryRow(category: SponsorBlock.Category) {
    val c = MaterialTheme.colorScheme
    val current = SponsorBlock.actionFor(category)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(category.color))
            Spacer(Modifier.width(8.dp))
            Text(Loc.t(category.label), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SponsorBlock.Action.entries.forEach { action ->
                val on = action == current
                Box(
                    Modifier
                        .pressable { SponsorBlock.setAction(category, action) }
                        .clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                        .background(if (on) c.secondaryContainer else c.surfaceContainerHighest)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(Loc.t(action.label), color = if (on) c.onSecondaryContainer else c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

// ── About (inside the account sheet) ───────────────────────────────────────────

/**
 * What this is, and the promises it keeps. The zero-ads policy is a product decision, not a
 * setting: there is nothing to switch off, so the sheet says so where the switches would be.
 */
@Composable
fun AboutSettings() {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The launcher icon, in the app: this is the one place the product names itself, so
            // it carries the brand tile rather than a bare word.
            BrandTile(size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Shinobi", fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(Loc.t("Version") + " " + BuildConfig.VERSION_NAME, color = c.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            Loc.t(
                "No ads, no ad SDKs, no analytics and no trackers — in this build and every other. " +
                    "There is nothing to disable here, and nothing that could be switched on later: " +
                    "the project's build refuses to compile with an advertising or tracking SDK in it.\n\n" +
                    "Sponsor reads inside a video are skipped automatically where the community has " +
                    "submitted them; the SponsorBlock controls above decide how.",
            ),
            color = c.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            Loc.t(
                "Free software under the GNU GPL version 3, built with NewPipeExtractor, " +
                    "AndroidX Media3 and Jetpack Compose. Not affiliated with YouTube or Google.",
            ),
            color = c.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 16.sp,
        )
    }
}

// ── Appearance, Language and Feed (inside the account sheet) ───────────────────

/** A labelled switch row: the one shape every boolean setting in the sheet uses. */
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.primary),
        )
    }
}

/** The pill row used by every settings choice: theme mode, language, and so on. */
@Composable
private fun SettingChoicePill(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(if (selected) 18.dp else 12.dp))
            .background(if (selected) c.primary else c.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) c.onPrimary else c.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (selected) c.onPrimary else c.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/** Light/dark mode (with a one-tap Light switch), then every Shinobi theme. */
@Composable
fun AppearanceSettings() {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val dark = isDarkTheme()
    Column(Modifier.fillMaxWidth()) {
        Text(Loc.t("Appearance"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        SettingSwitchRow(
            icon = if (dark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
            title = Loc.t("Light mode"),
            subtitle = if (dark) Loc.t("Off") + " · " + Loc.t("Night mode") else Loc.t("Light theme"),
            checked = !dark,
            onCheckedChange = { ThemeSettings.chooseMode(if (it) "light" else "dark") },
        )
        Spacer(Modifier.height(6.dp))
        // System follows the phone; Light and Dark pin the app either way.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChoicePill(
                label = Loc.t("System"),
                icon = Icons.Rounded.BrightnessAuto,
                selected = ThemeSettings.mode == "system",
                modifier = Modifier.weight(1f),
            ) { ThemeSettings.chooseMode("system") }
            SettingChoicePill(
                label = Loc.t("Light"),
                icon = Icons.Rounded.LightMode,
                selected = ThemeSettings.mode == "light",
                modifier = Modifier.weight(1f),
            ) { ThemeSettings.chooseMode("light") }
            SettingChoicePill(
                label = Loc.t("Dark"),
                icon = Icons.Rounded.DarkMode,
                selected = ThemeSettings.mode == "dark",
                modifier = Modifier.weight(1f),
            ) { ThemeSettings.chooseMode("dark") }
        }
        Spacer(Modifier.height(16.dp))
        Text(Loc.t("Theme color"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        val choices = buildList {
            if (ThemeSettings.wallpaperAvailable) add(null to Loc.t("Wallpaper"))
            SHINOBI_THEMES.forEach { add(it.hex to Loc.t(it.name)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            choices.forEach { (hex, name) ->
                val selected = ThemeSettings.seed?.lowercase() == hex?.lowercase()
                val scheme = schemeFor(context, hex, dark)
                Column(
                    Modifier.width(78.dp).pressable { ThemeSettings.choose(hex) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(if (selected) CookieShape else CircleShape)
                            .background(scheme.surface)
                            .padding(5.dp)
                            .clip(if (selected) CookieShape else CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    0f to scheme.primary, 0.5f to scheme.primary,
                                    0.5f to scheme.primaryContainer, 1f to scheme.primaryContainer,
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, Loc.t("Selected"), tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        name, fontSize = 11.5.sp, maxLines = 1, softWrap = false,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (selected) c.onSurface else c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Language: English (the default) or Arabic, which mirrors the whole app right-to-left. The
 * Activity is recreated on the spot so the change lands everywhere at once.
 */
@Composable
fun LanguageSettings() {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    Column(Modifier.fillMaxWidth()) {
        Text(Loc.t("Language"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            Loc.t("English is the default. Arabic mirrors the whole app right-to-left."),
            color = c.onSurfaceVariant,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChoicePill(
                label = Loc.t("English"),
                icon = Icons.Rounded.Language,
                selected = Loc.language == Loc.ENGLISH,
                modifier = Modifier.weight(1f),
            ) {
                if (Loc.language != Loc.ENGLISH) {
                    Loc.choose(Loc.ENGLISH)
                    activity?.recreate()
                }
            }
            SettingChoicePill(
                label = "العربية",
                icon = Icons.Rounded.Language,
                selected = Loc.language == Loc.ARABIC,
                modifier = Modifier.weight(1f),
            ) {
                if (Loc.language != Loc.ARABIC) {
                    Loc.choose(Loc.ARABIC)
                    activity?.recreate()
                }
            }
        }
    }
}

/** Feed behaviour: what "For you" is made of, and how Shorts behave while playing. */
@Composable
fun FeedSettings() {
    Column(Modifier.fillMaxWidth()) {
        Text(Loc.t("Feed"), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        SettingSwitchRow(
            icon = Icons.Rounded.SmartDisplay,
            title = Loc.t("Mix Shorts with videos"),
            subtitle = Loc.t("Shorts and videos together in For you"),
            checked = AppSettings.mixShortsInFeed,
            onCheckedChange = { AppSettings.mixShortsInFeed = it },
        )
        SettingSwitchRow(
            icon = Icons.Rounded.BlurOn,
            title = Loc.t("Blur sensitive thumbnails"),
            subtitle = Loc.t("Blur age-restricted and mature thumbnails. Regular videos are never blurred."),
            checked = AppSettings.blurSensitiveThumbnails,
            onCheckedChange = { AppSettings.blurSensitiveThumbnails = it },
        )
        Spacer(Modifier.height(12.dp))
        Text(Loc.t("Previews on Home"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            Loc.t("Play a few silent seconds of the video in view, like the YouTube app."),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        val previews = AppSettings.feedPreviews
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChoicePill(
                label = Loc.t("Always"),
                icon = Icons.Rounded.SmartDisplay,
                selected = previews == FeedPreview.ALWAYS,
                modifier = Modifier.weight(1f),
            ) { AppSettings.feedPreviews = FeedPreview.ALWAYS }
            SettingChoicePill(
                label = Loc.t("Wi-Fi only"),
                icon = Icons.Rounded.Wifi,
                selected = previews == FeedPreview.WIFI,
                modifier = Modifier.weight(1f),
            ) { AppSettings.feedPreviews = FeedPreview.WIFI }
            SettingChoicePill(
                label = Loc.t("Off"),
                icon = Icons.Rounded.VisibilityOff,
                selected = previews == FeedPreview.OFF,
                modifier = Modifier.weight(1f),
            ) { AppSettings.feedPreviews = FeedPreview.OFF; FeedPreview.stop() }
        }
    }
}
