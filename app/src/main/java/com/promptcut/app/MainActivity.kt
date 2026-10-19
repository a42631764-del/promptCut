package com.promptcut.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/** Public URL of the privacy policy (required by Google Play). Replace with where you host privacy-policy.html. */
const val PRIVACY_POLICY_URL = "https://example.com/promptcut/privacy"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                val vm: EditorViewModel = viewModel()
                if (vm.showSettings) KeySetupScreen(vm) else EditorScreen(vm)
            }
        }
    }
}

// ============================================================== Key setup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySetupScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(vm.settings.provider) }
    var key by remember(provider) { mutableStateOf(vm.settings.key(provider)) }
    var showKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember(provider) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val canClose = vm.settings.hasActiveKey

    BackHandler(enabled = canClose) { vm.showSettings = false }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (canClose) "AI settings" else "Welcome to PromptCut") },
            navigationIcon = {
                if (canClose) IconButton(onClick = { vm.showSettings = false }) { Icon(Icons.Default.Close, "Close") }
            },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "PromptCut edits your videos from a text prompt. The \"thinking\" is done by a free AI service, " +
                    "using your own free key. It takes about a minute to get one, and no payment card is needed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("1. Choose a free AI service", style = MaterialTheme.typography.titleMedium)
            Provider.entries.forEach { p ->
                val selected = p == provider
                OutlinedCard(
                    onClick = { provider = p },
                    border = if (selected) CardDefaults.outlinedCardBorder().copy(width = 2.dp) else CardDefaults.outlinedCardBorder(),
                    colors = if (selected) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { provider = p })
                        Column {
                            Text(p.displayName, fontWeight = FontWeight.SemiBold)
                            Text(p.tagline, style = MaterialTheme.typography.bodySmall)
                            if (vm.settings.key(p).isNotBlank()) Text("Key saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Text("2. Get your free key", style = MaterialTheme.typography.titleMedium)
            provider.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyPageUrl))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Get free key — opens ${Uri.parse(provider.keyPageUrl).host}") }

            Text("3. Paste it here", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); result = null },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { key = it; result = null }
                }) { Text("Paste") }
                Button(
                    enabled = key.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            result = try {
                                val model = clientFor(provider, key, null).pickModel()
                                vm.settings.setKey(provider, key)
                                vm.settings.setModel(provider, model)
                                vm.settings.provider = provider
                                true to "Key works. Using model: $model"
                            } catch (e: Exception) {
                                false to (e.message ?: "Couldn't check the key.")
                            }
                            checking = false
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check & save key") }
            }
            result?.let { (ok, msg) ->
                Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            if (result?.first == true) {
                Button(onClick = { vm.showSettings = false }, modifier = Modifier.fillMaxWidth()) { Text("Start editing") }
            }
            HorizontalDivider()
            Text("Privacy", style = MaterialTheme.typography.titleSmall)
            Text(
                "• Your videos stay on this phone. They are analysed and rendered here.\n" +
                    "• To plan an edit, a text description of your clips is sent to the AI service you choose " +
                    "(Google Gemini or Groq). With Gemini, small still frames are sent too. With a Groq key, the clips' " +
                    "audio is sent to Groq for speech-to-text.\n" +
                    "• Music searches go to Openverse; songs you pick are downloaded from there.\n" +
                    "• Your API keys are encrypted on this phone and sent only to their own service.\n" +
                    "• PromptCut has no account, no ads and no analytics, and runs no servers of its own.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }) {
                Text("Full privacy policy")
            }
        }
    }
}

// ============================================================== Editor

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { vm.addClips(it) }
    val pickVideos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val songPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pickSong(it) } }
    val pickSong = { songPicker.launch(arrayOf("audio/*")) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { idx -> vm.clips.firstOrNull { it.meta.index == idx }?.let { c -> ClipEditDialog(vm, c) { editing = null } } }
    val listState = rememberLazyListState()
    var styleOpen by remember { mutableStateOf(true) }
    LaunchedEffect(vm.plan) { if (vm.plan != null) styleOpen = false }
    LaunchedEffect(vm.chat.size) { if (vm.chat.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    // When a new video is ready, bring it into view; while typing, show the end of the conversation.
    LaunchedEffect(vm.busy) { if (!vm.busy && vm.output != null) listState.animateScrollToItem(1) }
    val typing = WindowInsets.isImeVisible
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(typing, inputFocused) { if (typing && inputFocused) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    val submit = {
        if (vm.plan == null) vm.create(input) else vm.send(input)
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PromptCut") },
                actions = {
                    IconButton(onClick = { vm.startOver() }) { Icon(Icons.Default.Refresh, "Start over") }
                    IconButton(onClick = { vm.showSettings = true }) { Icon(Icons.Default.Settings, "AI settings") }
                },
            )
        },
    ) { pad ->
        // One scrolling page: clips, video, buttons, check, style and chat all move together, so scrolling up
        // (or typing) gives the text the whole screen instead of a thin strip under a fixed player.
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "clips") {
                    LazyRow(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.clips, key = { it.meta.index }) { clip ->
                            ClipTile(clip, cut = vm.clipCuts.containsKey(clip.meta.index), onOpen = { editing = clip.meta.index }) { vm.removeClip(clip) }
                        }
                        item {
                            OutlinedCard(onClick = pickVideos, modifier = Modifier.size(width = 96.dp, height = 96.dp)) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Default.Add, null)
                                    Text(if (vm.clips.isEmpty()) "Add videos" else "Add more", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                vm.output?.let { file ->
                    item(key = "player") { PreviewPlayer(file.absolutePath, Modifier.fillMaxWidth().height(300.dp)) }
                }
                if (vm.output != null && !vm.busy) item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.saveToGallery { } }) { Text("Save to gallery") }
                        Button(onClick = {
                            vm.saveToGallery { uri ->
                                if (uri != null) ctx.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                        .apply { vm.shareCredit?.let { putExtra(Intent.EXTRA_TEXT, it) } }
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video"))
                            }
                        }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                    }
                }
                if (vm.output != null && !vm.busy) vm.qa?.let { r -> item(key = "qa") { QaCard(r) } }
                if (vm.clips.isEmpty()) {
                    item {
                        Text(
                            "Add your videos. I'll watch them on your phone, suggest a few styles, and cut them into a story with music.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else if (styleOpen) {
                    item { StylePanel(vm, pickSong, onCreate = { submit() }) }
                } else {
                    item {
                        OutlinedCard(onClick = { styleOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${vm.mood.emoji} ${vm.mood.label} · ${vm.aspect.key} · ${vm.color.label} · " +
                                        when (vm.musicSource) {
                                            MusicSource.MY_SONG -> vm.song?.name?.substringBeforeLast('.') ?: "My song"
                                            MusicSource.LIBRARY -> vm.libSong?.name ?: "Free song"
                                            else -> vm.musicSource.label
                                        },
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                )
                                Text("Change style", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                items(vm.chat) { m -> ChatBubble(m) }
                vm.plan?.let { p ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("The story", style = MaterialTheme.typography.labelLarge)
                            p.segments.forEachIndexed { i, s ->
                                Text(
                                    "${i + 1}. " + (if (s.soundGain > 0.02) "🔊 " else "🎵 ") + (s.why.ifBlank { "shot" }) + "  ·  clip ${s.clip}, ${round1(s.start)}–${round1(s.end)} s" +
                                        if (s.speed != 1.0) " ×${s.speed}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("Ask for changes below, e.g. \"start with the smile\", \"make it calmer\", \"no title\".",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Status
            vm.status?.let { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s + if (vm.progress >= 0) " ${vm.progress}%" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (vm.busy) TextButton(onClick = { vm.cancel() }) { Text("Cancel") }
                    }
                    if (vm.busy || vm.analyzing) {
                        if (vm.progress >= 0) LinearProgressIndicator(progress = { vm.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            // Input
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (vm.plan == null) "Your own idea (optional)…" else "Change something… e.g. \"shorter intro\"") },
                    modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (input.isNotBlank()) submit() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(enabled = !vm.busy && input.isNotBlank() && vm.clips.isNotEmpty(), onClick = { submit() }) {
                    Icon(Icons.Default.Send, "Send")
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StylePanel(vm: EditorViewModel, pickSong: () -> Unit, onCreate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val s = vm.suggestions
        when {
            vm.analyzing && s == null -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("👀 Watching your clips…", fontWeight = FontWeight.SemiBold)
                    Text("Finding people, smiles, shot changes and sounds so the cuts make sense. You can already pick a style.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            s != null && s.understanding.isNotBlank() -> Card(Modifier.fillMaxWidth()) {
                Text("👀 ${s.understanding}", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (s != null && s.presets.isNotEmpty()) {
            Text("Suggested for your video", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.presets.forEach { p ->
                    val sel = vm.selectedPreset == p
                    OutlinedCard(
                        onClick = { if (sel) vm.clearPreset() else vm.applyPreset(p) },
                        modifier = Modifier.width(210.dp),
                        border = if (sel) CardDefaults.outlinedCardBorder().copy(width = 2.dp) else CardDefaults.outlinedCardBorder(),
                        colors = if (sel) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${p.mood.emoji} ${p.name}", fontWeight = FontWeight.SemiBold)
                            Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Text("${p.lengthSec}s · ${p.aspect.key} · ${p.color.label}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Style", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (vm.off.isNotEmpty()) TextButton(onClick = { vm.useDefaults() }) { Text("Use defaults") }
            if (!vm.nothingSelected) TextButton(onClick = { vm.clearAll() }) { Text("Clear all") }
        }
        Text(
            if (vm.nothingSelected) "📝 Nothing selected: only what you type below is sent to the AI. Say length, mood, music, cuts or crop in your own words."
            else "Tap a selected option again to unselect it. Unselected rows are not sent; the AI decides from your words.",
            style = MaterialTheme.typography.labelSmall,
            color = if (vm.nothingSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptRow(vm, Opt.OCCASION, "What is it?", OCCASIONS, vm.occasion, { it.replaceFirstChar { c -> c.uppercase() } }) { vm.occasion = it }
        OptRow(vm, Opt.MOOD, "Mood", Mood.entries, vm.mood, { "${it.emoji} ${it.label}" }) { vm.mood = it }
        OptRow(vm, Opt.LENGTH, "Length", LENGTHS, vm.lengthSec, { when { it == 0 -> "Auto"; it >= 120 -> "${it / 60} min"; else -> "${it}s" } }) { vm.setLength(it) }
        OptRow(vm, Opt.FORMAT, "Format", Aspect.entries, vm.aspect, { it.label }) { vm.aspect = it }
        OptRow(vm, Opt.LOOK, "Look", ColorLook.entries, vm.color, { it.label }) { vm.color = it }
        OptRow(vm, Opt.TRANSITION, "Transitions", Transition.entries, vm.transition, {
            if (it == Transition.AUTO) "Auto (${Transition.forMood(vm.mood).label.lowercase()})" else it.label
        }) { vm.transition = it }
        OptRow(vm, Opt.MUSIC, "Music", MusicSource.entries, vm.musicSource, { it.label }) {
            vm.musicSource = it
            if (it == MusicSource.MY_SONG && vm.song == null) pickSong()
            if (it == MusicSource.LIBRARY && vm.libResults.isEmpty()) vm.searchLibrary()
        }
        if (vm.isOn(Opt.MUSIC)) when (vm.musicSource) {
            MusicSource.MY_SONG -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.song?.let { song -> "🎵 ${song.name.substringBeforeLast('.')}" + (song.tempo?.let { " · ${it.bpm.toInt()} BPM" } ?: "") }
                        ?: "No song picked yet",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = pickSong) { Text(if (vm.song == null) "Pick a song" else "Change") }
            }
            MusicSource.LIBRARY -> LibraryPanel(vm)
            MusicSource.MOOD -> Text("Simple original ${vm.mood.label.lowercase()} music composed on your phone. Works offline.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MusicSource.ORIGINAL -> {}
        }
        if (!vm.isOn(Opt.MUSIC) || vm.musicSource != MusicSource.ORIGINAL) {
            OptRow(vm, Opt.SOUND, "Original sound", SoundMode.entries, vm.soundMode, { it.label }) { vm.soundMode = it }
        }
        OptRow(vm, Opt.SPEED, "Speed", SpeedMode.entries, vm.speedMode, { it.label }) { vm.speedMode = it }
        OptRow(vm, Opt.USE_ALL, "Use every clip", listOf(true), vm.useAllClips, { "Use every clip" }) { vm.useAllClips = true }
        OptRow(vm, Opt.ORDER, "Order", OrderMode.entries, vm.orderMode, { it.label }) { vm.orderMode = it }

        Button(
            onClick = onCreate,
            enabled = !vm.busy && vm.clips.isNotEmpty() && (vm.musicSource != MusicSource.MY_SONG || vm.song != null) && vm.libLoadingId == null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("✨ Create video", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Free Creative Commons songs: search, listen, pick. If none is picked, a fitting one is chosen automatically. */
@AndroidXOptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryPanel(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var playingId by remember { mutableStateOf<String?>(null) }
    val searchInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player.release() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.libSong?.let { s ->
            Text("🎵 Using: ${s.name}" + (s.tempo?.let { " · ${it.bpm.toInt()} BPM" } ?: ""), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold)
        } ?: Text("Pick a song below, or just tap Create: I'll choose one that fits the mood.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.libQuery, onValueChange = { vm.libQuery = it }, singleLine = true,
                placeholder = { Text(MusicLibrary.defaultQuery(vm.mood, vm.occasion)) },
                label = { Text("Search free music by mood") },
                modifier = Modifier.weight(1f)
                    .bringIntoViewRequester(searchInView)
                    .onFocusEvent { if (it.isFocused) scope.launch { kotlinx.coroutines.delay(350); searchInView.bringIntoView() } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.searchLibrary() }),
            )
            TextButton(onClick = { vm.searchLibrary() }, enabled = !vm.libSearching) { Text(if (vm.libSearching) "…" else "Search") }
        }
        vm.libError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        vm.libResults.take(8).forEach { t ->
            val chosen = vm.libSong?.name == "${t.title} – ${t.artist}"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    if (playingId == t.id) { player.stop(); playingId = null }
                    else { player.setMediaItem(MediaItem.fromUri(t.url)); player.prepare(); player.play(); playingId = t.id }
                }) { Text(if (playingId == t.id) "■" else "▶") }
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("${t.artist} · ${(t.durationSec / 60).toInt()}:${"%02d".format((t.durationSec % 60).toInt())} · ${t.license}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                when {
                    vm.libLoadingId == t.id -> Text("Loading…", style = MaterialTheme.typography.labelSmall)
                    chosen -> Text("✓ Chosen", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    else -> TextButton(onClick = { player.stop(); playingId = null; vm.useLibraryTrack(t) }) { Text("Use") }
                }
            }
        }
        if (vm.libResults.isNotEmpty()) Text("Free Creative Commons music via Openverse. CC BY songs need a credit, which is added when you share.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tap a clip: draw the area to show (crop) and review the parts that are cut out. */
@Composable
private fun ClipEditDialog(vm: EditorViewModel, clip: Clip, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(clip.uri) {
        frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.media.MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, clip.uri)
                    r.getScaledFrameAtTime((clip.meta.durationSec * 500_000).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720)
                }
            }.getOrNull()
        }
    }
    var box by remember { mutableStateOf(clip.meta.crop ?: CropBox(0.1, 0.1, 0.9, 0.9)) }
    var cropOn by remember { mutableStateOf(clip.meta.crop != null) }
    val dur = clip.meta.durationSec
    val cuts = vm.clipCuts[clip.meta.index].orEmpty()
    val kept = remember { mutableStateOf(
        (cuts.firstOrNull { it.start <= 0.01 }?.end ?: 0.0).toFloat()..(cuts.firstOrNull { it.end >= dur - 0.01 }?.start ?: dur).toFloat()
    ) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Clip #${clip.meta.index} · ${round1(dur)} s") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Crop to an area", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = cropOn, onCheckedChange = { cropOn = it })
                }
                frame?.let { bmp ->
                    val ratio = bmp.width.toFloat() / bmp.height
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        if (cropOn) CropOverlay(box) { box = it }
                    }
                } ?: Text("Loading frame…", style = MaterialTheme.typography.bodySmall)
                if (cropOn) Text("Drag the corners or the middle of the box. Only this area is shown in the video.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text("Use only ${round1(kept.value.start.toDouble())}–${round1(kept.value.endInclusive.toDouble())} s", style = MaterialTheme.typography.labelLarge)
                RangeSlider(value = kept.value, onValueChange = { kept.value = it }, valueRange = 0f..dur.toFloat())
                val middle = cuts.filter { it.start > 0.01 && it.end < dur - 0.01 }
                if (middle.isNotEmpty()) {
                    Text("Cut out (from your messages):", style = MaterialTheme.typography.labelLarge)
                    middle.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✂️ ${round1(c.start)}–${round1(c.end)} s", Modifier.weight(1f))
                            TextButton(onClick = { vm.setCuts(clip.meta.index, cuts - c) }) { Text("Undo") }
                        }
                    }
                }
                Text("Tip: in the chat you can write \"remove 10 to 13 s\" or \"crop only the iPad\".",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setCrop(clip.meta.index, if (cropOn) box else null)
                val middle = vm.clipCuts[clip.meta.index].orEmpty().filter { it.start > 0.01 && it.end < dur - 0.01 }
                val ends = listOfNotNull(
                    Span(0.0, kept.value.start.toDouble(), "outside kept part").takeIf { kept.value.start > 0.05f },
                    Span(kept.value.endInclusive.toDouble(), dur, "outside kept part").takeIf { kept.value.endInclusive < dur - 0.05 },
                )
                vm.setCuts(clip.meta.index, middle + ends)
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** A draggable crop rectangle: drag a corner to resize, the middle to move. Coordinates are 0..1 of the frame. */
@Composable
private fun CropOverlay(box: CropBox, onChange: (CropBox) -> Unit) {
    val current by rememberUpdatedState(box)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var mode = 0 // 1..4 corners (tl, tr, bl, br), 5 move
            detectDragGestures(
                onDragStart = { p ->
                    val b = current
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val corners = listOf(b.x0 to b.y0, b.x1 to b.y0, b.x0 to b.y1, b.x1 to b.y1)
                        .map { (x, y) -> androidx.compose.ui.geometry.Offset((x * w).toFloat(), (y * h).toFloat()) }
                    val near = corners.withIndex().minByOrNull { (it.value - p).getDistance() }!!
                    mode = if ((near.value - p).getDistance() < 60f) near.index + 1
                        else if (p.x / w in b.x0..b.x1 && p.y / h in b.y0..b.y1) 5 else 0
                },
                onDrag = { change, d ->
                    change.consume()
                    val dx = d.x / size.width; val dy = d.y / size.height
                    val b = current
                    val nb = when (mode) {
                        1 -> b.copy(x0 = b.x0 + dx, y0 = b.y0 + dy)
                        2 -> b.copy(x1 = b.x1 + dx, y0 = b.y0 + dy)
                        3 -> b.copy(x0 = b.x0 + dx, y1 = b.y1 + dy)
                        4 -> b.copy(x1 = b.x1 + dx, y1 = b.y1 + dy)
                        5 -> {
                            val mx = dx.toDouble().coerceIn(-b.x0, 1 - b.x1); val my = dy.toDouble().coerceIn(-b.y0, 1 - b.y1)
                            CropBox(b.x0 + mx, b.y0 + my, b.x1 + mx, b.y1 + my)
                        }
                        else -> b
                    }
                    val c = CropBox(nb.x0.coerceIn(0.0, 1.0), nb.y0.coerceIn(0.0, 1.0), nb.x1.coerceIn(0.0, 1.0), nb.y1.coerceIn(0.0, 1.0))
                    if (c.width >= 0.1 && c.height >= 0.1) onChange(c)
                },
            )
        }
    ) {
        val l = (box.x0 * size.width).toFloat(); val t = (box.y0 * size.height).toFloat()
        val r = (box.x1 * size.width).toFloat(); val btm = (box.y1 * size.height).toFloat()
        val shade = androidx.compose.ui.graphics.Color(0x99000000)
        drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, t))
        drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, btm), size = androidx.compose.ui.geometry.Size(size.width, size.height - btm))
        drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, t), size = androidx.compose.ui.geometry.Size(l, btm - t))
        drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(r, t), size = androidx.compose.ui.geometry.Size(size.width - r, btm - t))
        drawRect(androidx.compose.ui.graphics.Color.White, topLeft = androidx.compose.ui.geometry.Offset(l, t),
            size = androidx.compose.ui.geometry.Size(r - l, btm - t), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        listOf(l to t, r to t, l to btm, r to btm).forEach { (x, y) ->
            drawCircle(androidx.compose.ui.graphics.Color.White, 14f, androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The app's own check of the finished video. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QaCard(r: QaReport) {
    var open by remember(r) { mutableStateOf(!r.ok) }
    OutlinedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (r.ok) "✅ Quality check passed (${r.items.size} checks)" else "⚠️ Quality check: ${r.issues.size} issue(s)",
                style = MaterialTheme.typography.labelLarge,
            )
            if (open) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.items.forEach { it ->
                    AssistChip(onClick = {}, label = {
                        Text((if (it.ok) "✓ " else "⚠ ") + it.label + if (it.detail.isNotBlank()) " – ${it.detail}" else "",
                            style = MaterialTheme.typography.labelSmall)
                    })
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
/** A style row that can be unselected: tap the selected chip again and the row is not sent to the AI. */
@Composable
private fun <T> OptRow(vm: EditorViewModel, opt: Opt, title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val on = vm.isOn(opt) && !(opt == Opt.USE_ALL && !vm.useAllClips)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (on) title else "$title · not set", style = MaterialTheme.typography.labelLarge,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                val sel = on && o == selected
                FilterChip(selected = sel, onClick = { if (sel) vm.unset(opt) else { vm.turnOn(opt); onPick(o) } }, label = { Text(label(o)) })
            }
        }
    }
}

@Composable
private fun ClipTile(clip: Clip, cut: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen)) {
        clip.thumbnail?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            "#${clip.meta.index} · ${round2(clip.meta.durationSec)}s",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp),
        )
        if (clip.meta.crop != null || cut) Text(
            (if (clip.meta.crop != null) "🔲" else "") + (if (cut) "✂️" else ""),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(RoundedCornerShape(11.dp))
                .background(androidx.compose.ui.graphics.Color(0x99000000)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, "Remove", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    val bg = when {
        m.isError -> MaterialTheme.colorScheme.errorContainer
        m.fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            m.text,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@AndroidXOptIn(UnstableApi::class)
@Composable
private fun PreviewPlayer(path: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    LaunchedEffect(path) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(path))))
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.Black),
    )
}
