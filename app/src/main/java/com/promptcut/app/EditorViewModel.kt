package com.promptcut.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(val fromUser: Boolean, val text: String, val isError: Boolean = false)

enum class MusicSource(val label: String) {
    LIBRARY("Free songs"), MOOD("Built-in music"), MY_SONG("My song"), ORIGINAL("Original sound only")
}

/** A song the user picked, with its tempo so cuts can follow the beat. */
class Song(
    val uri: Uri, val name: String, val envelope: FloatArray?, val tempo: Beat.Tempo?, val durationSec: Double,
    /** Credit line for Creative Commons songs (put it in the post caption). */
    val credit: String? = null,
)

val OCCASIONS = listOf("birthday", "travel", "wedding", "family", "kids", "friends", "party", "festival", "sports", "pets", "food", "vlog", "other")
val LENGTHS = listOf(0, 15, 30, 60, 180, 600)

/** Rows of style choices the user can unselect. */
enum class Opt { OCCASION, MOOD, LENGTH, FORMAT, LOOK, TRANSITION, MUSIC, SOUND, SPEED, USE_ALL, ORDER }

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val settings = Settings(app)

    // Footage
    val clips = mutableStateListOf<Clip>()
    val insights = mutableStateMapOf<Int, ClipInsight>()
    /** Parts of each clip the user removed (from chat or the clip editor). */
    val clipCuts = mutableStateMapOf<Int, List<Span>>()
    var analyzing by mutableStateOf(false); private set
    var suggestions by mutableStateOf<Suggestions?>(null); private set

    // Style choices
    var occasion by mutableStateOf("other")
    var mood by mutableStateOf(Mood.HAPPY)
    var lengthSec by mutableStateOf(0)
    var aspect by mutableStateOf(Aspect.PORTRAIT)
    var color by mutableStateOf(ColorLook.NATURAL)
    var musicSource by mutableStateOf(MusicSource.LIBRARY)
    var transition by mutableStateOf(Transition.AUTO)
    var speedMode by mutableStateOf(SpeedMode.NATURAL)
    var soundMode by mutableStateOf(SoundMode.VOICES)
    var useAllClips by mutableStateOf(true)
    var orderMode by mutableStateOf(OrderMode.MIX)
    /** Style rows the user unselected: they are not sent, and the user's words / the AI decide instead. */
    val off = mutableStateListOf<Opt>()
    fun isOn(o: Opt) = o !in off
    /** Tapping a selected chip unselects its row. */
    fun unset(o: Opt) { if (o !in off) off += o; if (o == Opt.LENGTH) lengthSec = 0; if (o == Opt.USE_ALL) useAllClips = false; selectedPreset = null }
    fun turnOn(o: Opt) { off -= o }
    /** Nothing selected: only the user's own text goes to the AI. */
    fun clearAll() { off.clear(); off += Opt.entries; lengthSec = 0; useAllClips = false; selectedPreset = null }
    fun useDefaults() { off.clear(); useAllClips = true }
    val nothingSelected get() = off.size == Opt.entries.size
    /** Length the user asked for in words ("make a 20 second video"); kept for later changes. */
    var askedLengthSec by mutableStateOf<Double?>(null); private set
    var song by mutableStateOf<Song?>(null); private set

    // Free song library
    val libResults = mutableStateListOf<LibraryTrack>()
    var libQuery by mutableStateOf("")
    var libSearching by mutableStateOf(false); private set
    var libSong by mutableStateOf<Song?>(null); private set
    var libLoadingId by mutableStateOf<String?>(null); private set
    var libError by mutableStateOf<String?>(null); private set
    var selectedPreset by mutableStateOf<Preset?>(null); private set

    // Result
    val chat = mutableStateListOf<ChatMessage>()
    var plan by mutableStateOf<EditPlan?>(null); private set
    var output by mutableStateOf<File?>(null); private set
    /** Result of the app's own check of the last video. */
    var qa by mutableStateOf<QaReport?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(-1); private set
    var busy by mutableStateOf(false); private set
    var showSettings by mutableStateOf(!settings.hasActiveKey)

    private var job: Job? = null
    private var analysisJob: Job? = null
    private var cachedFrames: List<Frame>? = null

    val totalFootage: Double get() = clips.sumOf { it.meta.durationSec }

    init {
        // Foreground service while watching/rendering, so long jobs survive the screen turning off.
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { (busy || analyzing) to (status to progress) }.collect { (working, sp) ->
                if (working) {
                    WorkService.start(ctx, sp.first ?: "Working…")
                    WorkService.update(ctx, sp.first ?: "Working…", sp.second)
                } else WorkService.stop(ctx)
            }
        }
    }

    override fun onCleared() {
        WorkService.stop(ctx)
        super.onCleared()
    }

    /** Parts of long footage chosen in the first planning pass (kept for later chat changes). */
    private var windows: Map<Int, List<Span>>? = null

    /** Long footage gets a two-step plan: overview → pick parts → detailed cut of just those parts. */
    private val isLong: Boolean
        get() = totalFootage > 300 || clips.sumOf { it.meta.description.length } > 12_000

    private fun metasCompact() = clips.map { c ->
        c.meta.copy(description = insights[c.meta.index]?.describe(c.meta, compact = true) ?: c.meta.description)
    }

    private fun metasIn(w: Map<Int, List<Span>>) = clips.filter { w.containsKey(it.meta.index) }.map { c ->
        c.meta.copy(description = insights[c.meta.index]?.describe(c.meta, windows = w[c.meta.index]) ?: c.meta.description)
    }

    private fun mergeWindows(a: Map<Int, List<Span>>, b: Map<Int, List<Span>>): Map<Int, List<Span>> =
        (a.keys + b.keys).associateWith { k ->
            val merged = mutableListOf<Span>()
            ((a[k] ?: emptyList()) + (b[k] ?: emptyList())).sortedBy { it.start }.forEach { s ->
                val last = merged.lastOrNull()
                if (last != null && s.start <= last.end + 2.0) merged[merged.lastIndex] = Span(last.start, maxOf(last.end, s.end))
                else merged += s
            }
            merged
        }

    private suspend fun selectWindows(client: LlmClient, request: String, frames: List<Frame>): Map<Int, List<Span>> {
        val compact = metasCompact()
        val prompt = PlanCodec.describeClips(compact) + "\nTotal footage: ${round1(totalFootage)} s.\n\nUSER REQUEST: $request"
        var reply = client.complete(PlanCodec.SELECT_SYSTEM, prompt, frames)
        return try {
            PlanCodec.parseWindows(reply, compact)
        } catch (e: PlanException) {
            reply = client.complete(PlanCodec.SELECT_SYSTEM, "$prompt\n\nYour previous reply was unusable: ${e.message}. Return the JSON only.", frames)
            PlanCodec.parseWindows(reply, compact)
        }
    }

    // ------------------------------------------------------------------ clips & understanding

    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            status = "Reading clips…"
            val next = (clips.maxOfOrNull { it.meta.index } ?: -1) + 1
            val loaded = ClipAnalyzer.load(ctx, uris, next)
            clips += loaded
            cachedFrames = null
            status = if (loaded.size < uris.size) "Some files couldn't be read and were skipped." else null
            understand()
        }
    }

    fun removeClip(clip: Clip) {
        clips.remove(clip)
        insights.remove(clip.meta.index)
        clipCuts.remove(clip.meta.index)
        cachedFrames = null
        plan = null
        output = null
        understand()
    }

    fun startOver() {
        job?.cancel(); analysisJob?.cancel()
        clips.clear(); insights.clear(); chat.clear(); clipCuts.clear(); askedLengthSec = null
        plan = null; output = null; qa = null; status = null; busy = false; analyzing = false
        suggestions = null; selectedPreset = null; cachedFrames = null; windows = null
    }

    /** Watches every new clip on the phone, then asks the AI what the footage is and which styles fit. */
    private fun understand() {
        analysisJob?.cancel()
        windows = null
        if (clips.isEmpty()) { suggestions = null; return }
        analysisJob = viewModelScope.launch {
            analyzing = true
            try {
                val groqKey = settings.key(Provider.GROQ)
                val stt: (suspend (File) -> List<Span>)? = if (groqKey.isNotBlank()) { f -> SpeechToText.groq(groqKey, f) } else null
                for (i in clips.indices) {
                    val clip = clips.getOrNull(i) ?: break
                    if (insights.containsKey(clip.meta.index)) continue
                    val ins = runCatching {
                        ClipUnderstanding.analyze(ctx, clip, stt) { step -> status = step }
                    }.getOrElse { if (it is CancellationException) throw it; ClipInsight(clip.meta.index) }
                    insights[clip.meta.index] = ins
                    val idx = clips.indexOfFirst { it.meta.index == clip.meta.index }
                    if (idx >= 0) clips[idx] = Clip(clip.uri, clip.meta.copy(description = ins.describe(clip.meta)), clip.thumbnail, clip.isHdr)
                }
                status = "Thinking about styles…"
                suggestStyles()
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't get style ideas: ${e.message}. You can still pick a style below."
            } finally {
                analyzing = false
            }
        }
    }

    private suspend fun suggestStyles() {
        val provider = settings.provider
        val key = settings.key(provider)
        if (key.isBlank()) return
        val frames = framesFor(provider)
        val client = clientFor(provider, key, settings.model(provider))
        val reply = client.complete(PlanCodec.SUGGEST_SYSTEM, PlanCodec.buildSuggestPrompt(if (isLong) metasCompact() else clips.map { it.meta }, frames.isNotEmpty()), frames)
        client.activeModel?.let { settings.setModel(provider, it) }
        val s = PlanCodec.parseSuggestions(reply)
        suggestions = s
        // Only pre-select when the user hasn't started an edit or picked something themselves.
        if (busy || plan != null || selectedPreset != null) return
        if (s.occasion in OCCASIONS) occasion = s.occasion
        // Styles are suggestions only: nothing is applied until the user taps one.
    }

    fun applyPreset(p: Preset) {
        off -= listOf(Opt.MOOD, Opt.FORMAT, Opt.LOOK, Opt.LENGTH)
        selectedPreset = p
        mood = p.mood
        aspect = p.aspect
        color = p.color
        lengthSec = LENGTHS.filter { it > 0 }.minByOrNull { kotlin.math.abs(it - p.lengthSec) }?.takeIf { p.lengthSec < totalFootage + 1 } ?: 0
    }

    fun clearPreset() { selectedPreset = null }

    private suspend fun framesFor(provider: Provider): List<Frame> =
        if (provider.seesVideo) cachedFrames ?: ClipAnalyzer.sampleFrames(ctx, clips).also { cachedFrames = it } else emptyList()

    // ------------------------------------------------------------------ per-clip edits (crop, cuts)

    fun setCrop(clipIndex: Int, crop: CropBox?) {
        val i = clips.indexOfFirst { it.meta.index == clipIndex }
        if (i < 0) return
        val c = clips[i]
        clips[i] = Clip(c.uri, c.meta.copy(crop = crop?.normalized()), c.thumbnail, c.isHdr)
    }

    fun setCuts(clipIndex: Int, cuts: List<Span>) {
        if (cuts.isEmpty()) clipCuts.remove(clipIndex) else clipCuts[clipIndex] = TimeCuts.merge(cuts)
    }

    private fun cutsNote(): String = if (clipCuts.isEmpty()) "" else
        " The user removed these parts; NEVER use them: " + clipCuts.entries.joinToString("; ") { (c, l) ->
            "clip $c " + l.joinToString { "${round1(it.start)}-${round1(it.end)} s" }
        } + "."

    /** "crop only the iPad": ask a vision model (Gemini) where the object is, then crop to it. */
    private suspend fun cropToObject(client: LlmClient, provider: Provider, target: String): String {
        if (!provider.seesVideo) return "To crop to the $target automatically, switch to Gemini in Settings (it can see the video), " +
            "or tap the clip and draw the area with Crop."
        var found = 0
        for (c in clips.toList()) {
            val frames = ClipAnalyzer.sampleFrames(ctx, listOf(c)).let { f -> if (f.size > 6) f.filterIndexed { i, _ -> i % (f.size / 6 + 1) == 0 } else f }
            if (frames.isEmpty()) continue
            val reply = runCatching {
                client.complete(
                    "You locate objects in video frames. Return ONLY JSON: {\"found\": true, \"box\": [ymin, xmin, ymax, xmax]} " +
                        "with coordinates from 0 to 1000 for ONE box that contains the $target in all frames (a small margin is fine). " +
                        "If the $target is not visible, return {\"found\": false}.",
                    "Find the $target in these frames of one video clip.", frames,
                )
            }.getOrNull() ?: continue
            val obj = runCatching { org.json.JSONObject(JsonRepair.repairObject(reply) ?: reply) }.getOrNull() ?: continue
            if (!obj.optBoolean("found", false)) continue
            val arr = obj.optJSONArray("box") ?: continue
            val box = CropBox.fromBox1000((0 until arr.length()).map { arr.optDouble(it) }) ?: continue
            val m = 0.03
            setCrop(c.meta.index, CropBox(box.x0 - m, box.y0 - m, box.x1 + m, box.y1 + m))
            found++
        }
        return if (found > 0) "Cropped to the $target in $found clip(s). Tap a clip to adjust the area."
        else "Couldn't find the $target in the clips. Tap a clip and draw the area with Crop."
    }

    // ------------------------------------------------------------------ music

    private suspend fun analyzeSong(uri: Uri, name: String, credit: String? = null): Song? {
        val hop = 0.02
        val env = withContext(Dispatchers.Default) { AudioTools.envelope(ctx, uri, hop, 300.0) } ?: return null
        val tempo = withContext(Dispatchers.Default) { Beat.detect(env, hop) }
        return Song(uri, name, env, tempo, env.size * hop, credit)
    }

    fun pickSong(uri: Uri) {
        viewModelScope.launch {
            status = "Listening to your song…"
            val name = withContext(Dispatchers.IO) {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } ?: "Song"
            val s = analyzeSong(uri, name)
            if (s == null) { status = "Couldn't read that audio file. Try another song."; return@launch }
            song = s
            musicSource = MusicSource.MY_SONG
            status = if (s.tempo != null) "Song ready: ${name.substringBeforeLast('.')} (${s.tempo.bpm.toInt()} BPM, cuts will follow the beat)."
            else "Song ready: ${name.substringBeforeLast('.')}."
        }
    }

    fun searchLibrary(query: String? = null) {
        val q = (query ?: libQuery).ifBlank { MusicLibrary.defaultQuery(mood, occasion) }
        libQuery = q
        viewModelScope.launch {
            libSearching = true; libError = null
            try {
                val minSec = maxOf(20.0, plan?.totalSec ?: lengthSec.toDouble())
                var found = MusicLibrary.search(q, minSec)
                if (found.isEmpty()) {
                    // A film or chart song name finds nothing here: only free (Creative Commons) music is searchable.
                    val moodQ = MusicLibrary.defaultQuery(mood, occasion)
                    found = runCatching { MusicLibrary.search(moodQ, minSec) }.getOrDefault(emptyList())
                    libError = "\"$q\" isn't in the free library. It only has Creative Commons music, not film or chart songs. " +
                        "Showing ${mood.label.lowercase()} songs instead. To use a song you own, pick \"My song\" " +
                        "(fine for personal videos; public posts may get muted for copyright)."
                }
                libResults.clear(); libResults += found
            } catch (e: Exception) {
                libError = e.message
            } finally {
                libSearching = false
            }
        }
    }

    /** Downloads a library song and analyses its beat. */
    private suspend fun loadLibraryTrack(t: LibraryTrack): Song {
        libLoadingId = t.id
        try {
            val f = MusicLibrary.download(ctx, t)
            return analyzeSong(Uri.fromFile(f), "${t.title} – ${t.artist}", if (t.needsCredit) t.credit else null)
                ?: throw ApiException("Couldn't read that song. Pick another one.")
        } finally {
            libLoadingId = null
        }
    }

    fun useLibraryTrack(t: LibraryTrack) {
        viewModelScope.launch {
            try {
                libSong = loadLibraryTrack(t)
                musicSource = MusicSource.LIBRARY
                libError = null
            } catch (e: Exception) {
                libError = e.message
            }
        }
    }

    /** Picks a fitting free song automatically (first long-enough result for the mood). */
    private suspend fun autoPickSong(p: EditPlan): Song? {
        val q = p.musicQuery.ifBlank { libQuery.ifBlank { MusicLibrary.defaultQuery(p.mood, occasion) } }
        status = "Finding a free ${p.mood.label.lowercase()} song…"
        val found = runCatching { MusicLibrary.search(q, p.totalSec + 2) }.getOrNull().orEmpty()
            .ifEmpty { runCatching { MusicLibrary.search(MusicLibrary.defaultQuery(p.mood, occasion), p.totalSec + 2) }.getOrNull().orEmpty() }
        if (found.isNotEmpty()) { libResults.clear(); libResults += found; libQuery = q }
        for (t in found.take(3)) {
            val s = runCatching { loadLibraryTrack(t) }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    // ------------------------------------------------------------------ editing

    /** The request built from the style the user picked. */
    private fun styleRequest(extra: String): String = buildString {
        append("Make a video. ")
        if (off.isNotEmpty()) append("Anything not stated here: decide yourself from the user's words and the footage. ")
        if (isOn(Opt.OCCASION)) append("Occasion: $occasion. ")
        if (isOn(Opt.MOOD)) append("Mood: ${mood.key}. ")
        if (isOn(Opt.LENGTH)) append(if (lengthSec > 0) "Target length: about $lengthSec s (shorter if the good footage runs out). " else if (totalFootage > 300) "Length: a highlight of about 1-3 minutes. " else "Length: whatever the good footage supports, usually 15-40 s. ")
        if (isOn(Opt.FORMAT)) append("Format: ${aspect.key}. ")
        if (isOn(Opt.LOOK)) append("Colour look: ${color.key}. ")
        if (isOn(Opt.TRANSITION)) append("Transition: ${transition.key}. ")
        if (isOn(Opt.SPEED)) append(when (speedMode) {
            SpeedMode.NATURAL -> "Speed: keep every shot at normal speed (speed 1). "
            SpeedMode.SLOWMO -> "Speed: normal, with slow motion (0.5) only on one or two emotional moments. "
            SpeedMode.DYNAMIC -> "Speed: you may speed up boring parts (max 2) and slow down the best moment. "
        })
        if (useAllClips && isOn(Opt.USE_ALL)) append("Use EVERY clip at least once (${clips.size} clips). ")
        if (isOn(Opt.ORDER)) append(when (orderMode) {
            OrderMode.MIX -> "Order: mix the scenes like a film-maker. Open on the main setting, then alternate different " +
                "places (e.g. greenery → train → water → greenery again); never show the same kind of scene back to back, " +
                "and give repetitive footage (long rides, same view) less time. "
            OrderMode.FILMED -> "Order: keep the order in which the clips were filmed. "
            OrderMode.AI -> ""
        })
        if (isOn(Opt.MUSIC) && isOn(Opt.SOUND) && musicSource != MusicSource.ORIGINAL) append(when (soundMode) {
            SoundMode.VOICES -> "Sound: music throughout; set \"sound\":\"on\" only on shots where people talk, sing, cheer or laugh. "
            SoundMode.MUTE -> "Sound: music only, mute every shot (\"sound\":\"off\"). "
            SoundMode.FULL -> "Sound: keep the original sound under the music on every shot. "
        })
        if (isOn(Opt.MUSIC)) append(
            when (musicSource) {
                MusicSource.LIBRARY -> "A free song matching the mood will be added underneath" + (libSong?.let { " (\"${it.name}\")" } ?: "") + ". "
                MusicSource.MOOD -> "Background music matching the mood will be added. "
                MusicSource.MY_SONG -> "The user's own song \"${song?.name ?: ""}\" will play underneath. "
                MusicSource.ORIGINAL -> "No music: keep the clips' own sound (original_volume 1). "
            }
        )
        selectedPreset?.let { append("Style: ${it.name} - ${it.description}. Editing idea: ${it.instructions}. ") }
        if (extra.isNotBlank()) append("User's own instructions (most important): $extra")
    }.let { if (nothingSelected && extra.isNotBlank()) "Make a video exactly as the user describes. Decide everything not stated from the user's words and the footage.\nUser's instructions: $extra" else it }

    fun create(extra: String = "") {
        if (clips.isEmpty() || busy) return
        chat += ChatMessage(true, if (nothingSelected) "📝 Only my words" + (if (extra.isNotBlank()) " · $extra" else "") else listOfNotNull(
            selectedPreset?.name ?: listOfNotNull(
                if (isOn(Opt.MOOD)) "${mood.emoji} ${mood.label}" else null, if (isOn(Opt.OCCASION)) occasion else null,
            ).joinToString(" ").ifBlank { null },
            if (lengthSec > 0 && isOn(Opt.LENGTH)) "${lengthSec}s" else null,
            if (isOn(Opt.FORMAT)) aspect.key else null,
            if (isOn(Opt.MUSIC)) musicSource.label else null,
            extra.ifBlank { null },
        ).joinToString(" · "))
        runEdit(styleRequest(extra), fresh = true, extraText = extra)
    }

    /**
     * Polishing (snapping to shot changes and beats) must not move an edge the user decided:
     * the start or end of a clip, or the edge of a part they removed. Those stay exact.
     */
    private fun pinEdges(before: EditPlan, after: EditPlan): EditPlan {
        if (before.segments.size != after.segments.size) return after
        val durations = clips.associate { it.meta.index to it.meta.durationSec }
        fun fixed(clip: Int, t: Double): Boolean {
            val d = durations[clip] ?: return false
            if (t < 0.05 || t > d - 0.05) return true
            return clipCuts[clip].orEmpty().any { kotlin.math.abs(it.start - t) < 0.05 || kotlin.math.abs(it.end - t) < 0.05 }
        }
        return after.copy(segments = after.segments.zip(before.segments) { a, b ->
            a.copy(start = if (fixed(b.clip, b.start)) b.start else a.start, end = if (fixed(b.clip, b.end)) b.end else a.end)
        })
    }

    /** Footage left after the user's cuts. */
    private fun usableFootage(): Double = clips.sumOf { c ->
        c.meta.durationSec - TimeCuts.merge(clipCuts[c.meta.index].orEmpty()).sumOf { it.end - it.start }
    }

    fun setLength(sec: Int) { lengthSec = sec; askedLengthSec = null }

    fun send(request: String) {
        val text = request.trim()
        if (text.isEmpty() || busy) return
        if (clips.isEmpty()) { chat += ChatMessage(false, "Add some video clips first.", true); return }
        chat += ChatMessage(true, text)
        if (plan == null) runEdit(styleRequest(text), fresh = true, extraText = text) else runEdit(text, fresh = false)
    }

    private fun runEdit(request: String, fresh: Boolean, extraText: String = "") {
        val provider = settings.provider
        val key = settings.key(provider)
        if (key.isBlank()) { showSettings = true; return }
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            try {
                analysisJob?.let { if (it.isActive) { status = "Finishing watching your clips…"; it.join() } }
                val client = clientFor(provider, key, settings.model(provider))
                val aiName = provider.displayName.substringBefore(" (")
                // Exact requests are applied by code: time cuts and object crops.
                val userText = if (fresh) extraText else request
                val parsed = TimeCuts.parse(userText, clips.associate { it.meta.index to it.meta.durationSec })
                parsed.cuts.forEach { (c, l) -> setCuts(c, clipCuts[c].orEmpty() + l) }
                if (parsed.understood.isNotEmpty()) chat += ChatMessage(false, "✂️ Understood: " + parsed.understood.joinToString("; ") + ".")
                TimeCuts.cropTarget(userText)?.let { target ->
                    status = "Looking for the $target…"
                    chat += ChatMessage(false, "🔲 " + cropToObject(client, provider, target))
                }
                // A length in the user's words beats the Length setting; it stays for later changes.
                TimeCuts.targetLength(userText)?.let { askedLengthSec = it; chat += ChatMessage(false, "⏱ Length: ${round1(it)} s.") }
                val target = askedLengthSec ?: lengthSec.takeIf { it > 0 && isOn(Opt.LENGTH) }?.toDouble()
                val order = if (isOn(Opt.ORDER)) orderMode else OrderMode.AI
                val request = request + cutsNote() +
                    (askedLengthSec?.let { " The final video must be $it s long: use enough footage (longer shots or more moments)." } ?: "")
                var metas = clips.map { it.meta }
                var frames = framesFor(provider)
                if (isLong) {
                    status = "Long footage: $aiName is picking the best parts…"
                    var w = selectWindows(client, request, frames)
                    // For a change request, keep the parts already in the video too.
                    val current = plan
                    if (!fresh && current != null) {
                        val used = current.segments.groupBy({ it.clip }, { Span((it.start - 2).coerceAtLeast(0.0), it.end + 2) })
                        w = mergeWindows(mergeWindows(windows ?: emptyMap(), used), w)
                    }
                    windows = w
                    metas = metasIn(w)
                    if (provider.seesVideo) frames = ClipAnalyzer.sampleFrames(ctx, clips.filter { w.containsKey(it.meta.index) }, w)
                    status = "Cutting the chosen ${round1(w.values.flatten().sumOf { it.length } / 60)} min into a story…"
                } else {
                    status = "Planning the story with $aiName…"
                }
                val userPrompt = PlanCodec.buildUserPrompt(metas, request, if (fresh) null else plan, frames.isNotEmpty())
                var reply = client.complete(PlanCodec.SYSTEM_PROMPT, userPrompt, frames)
                var newPlan = try {
                    PlanCodec.parse(reply, metas)
                } catch (e: PlanException) {
                    status = "Fixing the plan…"
                    reply = client.complete(
                        PlanCodec.SYSTEM_PROMPT,
                        userPrompt + "\n\nYour previous reply was unusable: ${e.message}\nPrevious reply:\n$reply\nReturn a corrected JSON plan only.",
                        frames,
                    )
                    PlanCodec.parse(reply, metas)
                }
                client.activeModel?.let { settings.setModel(provider, it) }

                // The user's picked style wins for a new edit; for a chat change, the AI's reading of the request wins.
                // Clear words in the user's own text ("make it natural", "add music") always win over both.
                val intents = QuickIntents.parse(if (fresh) extraText else request)
                if (fresh) newPlan = newPlan.copy(
                    aspect = if (isOn(Opt.FORMAT)) aspect else newPlan.aspect,
                    mood = if (isOn(Opt.MOOD)) mood else newPlan.mood,
                    color = if (isOn(Opt.LOOK)) color else newPlan.color,
                    transition = if (isOn(Opt.TRANSITION)) transition else newPlan.transition,
                )
                intents.mood?.let { newPlan = newPlan.copy(mood = it) }
                newPlan = newPlan.copy(
                    color = intents.color ?: newPlan.color,
                    transition = intents.transition ?: newPlan.transition,
                    title = if (intents.noTitle) null else newPlan.title,
                )
                aspect = newPlan.aspect; mood = newPlan.mood; color = newPlan.color; transition = newPlan.transition
                val wantMusic = intents.musicOn ?: newPlan.musicOn
                if (isOn(Opt.MUSIC)) when (wantMusic) {
                    true -> if (musicSource == MusicSource.ORIGINAL) musicSource = MusicSource.LIBRARY
                    false -> musicSource = MusicSource.ORIGINAL
                    null -> {}
                } else if (fresh || intents.musicOn != null) {
                    // Music not selected: a song only if the user's own words ask for one (the AI's wish doesn't count).
                    musicSource = if (intents.musicOn == true) (if (musicSource == MusicSource.ORIGINAL) MusicSource.LIBRARY else musicSource) else MusicSource.ORIGINAL
                }
                if (!isOn(Opt.SPEED) && fresh) speedMode = SpeedMode.NATURAL
                if (!isOn(Opt.SOUND) && fresh) soundMode = SoundMode.VOICES
                intents.speed?.let { speedMode = it }
                intents.sound?.let { soundMode = it }
                intents.useAll?.let { useAllClips = it }
                val useAll = useAllClips && (isOn(Opt.USE_ALL) || intents.useAll == true)
                if (musicSource != MusicSource.ORIGINAL) newPlan = newPlan.copy(musicVolume = newPlan.musicVolume.coerceIn(0.5, 1.0))
                // Film-maker rules: speed limits, trimming to the best part, every clip, real order, per-shot sound, reframing.
                newPlan = FilmRules.apply(newPlan, clips.map { it.meta }, insights.toMap(), speedMode, soundMode,
                    musicOn = musicSource != MusicSource.ORIGINAL, useAllClips = useAll, order = order, cuts = clipCuts.toMap(), targetSec = target,
                    keepAiLengths = !isOn(Opt.MOOD) && intents.mood == null)

                // Choose the music first (its beat shapes the cuts).
                if (musicSource == MusicSource.LIBRARY && libSong == null) {
                    libSong = autoPickSong(newPlan)
                    if (libSong == null) {
                        musicSource = MusicSource.MOOD
                        chat += ChatMessage(false, "Couldn't reach the free song library, so I used built-in ${newPlan.mood.label.lowercase()} music instead.")
                    }
                }
                val songNow = when (musicSource) { MusicSource.MY_SONG -> song; MusicSource.LIBRARY -> libSong; else -> null }

                // Polish the cuts (shots, speech, beat).
                val durations = clips.associate { it.meta.index to it.meta.durationSec }
                val grid = when {
                    musicSource == MusicSource.MOOD -> BeatGrid(60.0 / newPlan.mood.bpm, newPlan.mood.beatsPerCut)
                    songNow?.tempo != null -> BeatGrid(songNow.tempo.beatSec, 2)
                    else -> null
                }
                newPlan = pinEdges(newPlan, CutRefiner.refine(newPlan, durations, insights.toMap(), grid))
                plan = newPlan
                chat += ChatMessage(false, "${newPlan.summary} (${round1(newPlan.totalSec)} s, ${newPlan.segments.size} shots, " +
                    "${newPlan.effectiveTransition.label.lowercase()} transitions, ${newPlan.color.label.lowercase()} look)")

                val music: MusicMux.Source? = when (musicSource) {
                    MusicSource.ORIGINAL -> null
                    MusicSource.MOOD -> {
                        status = "Composing ${newPlan.mood.label.lowercase()} music…"
                        // Up to ~95 s is composed; longer videos loop it.
                        val f = File(ctx.cacheDir, "music_${newPlan.mood.key}.wav")
                        withContext(Dispatchers.Default) { MusicSynth.render(newPlan.mood, minOf(newPlan.totalSec + 2, 95.0), f) }
                        MusicMux.Source.File(Uri.fromFile(f), 0.0)
                    }
                    MusicSource.MY_SONG, MusicSource.LIBRARY -> songNow?.let { s ->
                        val start = s.envelope?.let { Beat.bestStart(it, 0.02, minOf(newPlan.totalSec, 30.0), s.tempo) } ?: 0.0
                        MusicMux.Source.File(s.uri, start)
                    }
                }

                var usedSafe = false
                suspend fun renderPlan(p: EditPlan, m: MusicMux.Source?): File {
                    status = "Rendering on your phone…"
                    output = null
                    progress = 0
                    return Renderer.render(
                        ctx, p, clips.toList(), m,
                        forceSafeMode = settings.safeRender,
                        onStatus = { st ->
                            status = st
                            if ("safe mode" in st) usedSafe = true
                            if (st.startsWith("Couldn't") || st.startsWith("Done.")) chat += ChatMessage(false, st, st.startsWith("Couldn't"))
                        },
                    ) { progress = it }
                }
                suspend fun inspect(p: EditPlan, f: File, m: MusicMux.Source?): QaReport {
                    status = "Checking the result…"; progress = -1
                    return QualityCheck.check(ctx, f, p, clips.toList(), insights.toMap(),
                        expectMusic = m != null, expectSound = p.originalVolume > 0.02 && clips.any { it.meta.hasAudio },
                        targetSec = target, usableSec = usableFootage())
                }

                var video = renderPlan(newPlan, music)
                var musicNow = music
                var report = inspect(newPlan, video, musicNow)
                val fixedNotes = mutableListOf<String>()

                // One automatic fix round: the app corrects what its own check found.
                if (!report.ok) {
                    chat += ChatMessage(false, "🔎 Quality check found: " + report.issues.values.joinToString("; ") + ". Fixing it…")
                    var fixPlan = newPlan
                    var needRender = false
                    if (QaIssue.COLOUR in report.issues && fixPlan.color != ColorLook.NATURAL) {
                        fixPlan = fixPlan.copy(color = ColorLook.NATURAL); color = ColorLook.NATURAL
                        fixedNotes += "switched to the natural look (the ${newPlan.color.label} look damaged colours on this footage)"
                        needRender = true
                    }
                    if (QaIssue.MUSIC_MISSING in report.issues && musicNow != null) {
                        fixPlan = fixPlan.copy(musicVolume = 1.0)
                        if (musicSource == MusicSource.LIBRARY) {
                            // The song may start with a long quiet intro: try a different one.
                            val alt = libResults.firstOrNull { it.title !in (libSong?.name ?: "") }
                            if (alt != null) runCatching { loadLibraryTrack(alt) }.getOrNull()?.let { s ->
                                libSong = s
                                musicNow = MusicMux.Source.File(s.uri, s.envelope?.let { Beat.bestStart(it, 0.02, minOf(fixPlan.totalSec, 30.0), s.tempo) } ?: 0.0)
                            }
                        }
                        fixedNotes += "made the music louder and fuller"
                        needRender = true
                    }
                    if (report.storyIssues.isNotEmpty()) {
                        status = "Asking $aiName to replace weak shots…"
                        val fixPrompt = PlanCodec.buildUserPrompt(metas, report.notesForAi(fixPlan), fixPlan, frames.isNotEmpty())
                        val revised = runCatching {
                            PlanCodec.parse(client.complete(PlanCodec.SYSTEM_PROMPT, fixPrompt, frames), metas)
                        }.getOrNull()
                        if (revised != null) {
                            val ruled = FilmRules.apply(
                                    revised.copy(aspect = fixPlan.aspect, mood = fixPlan.mood, color = fixPlan.color, transition = fixPlan.transition,
                                        musicVolume = fixPlan.musicVolume, title = fixPlan.title),
                                    clips.map { it.meta }, insights.toMap(), speedMode, soundMode,
                                    musicOn = musicSource != MusicSource.ORIGINAL, useAllClips = useAll, order = order, cuts = clipCuts.toMap(), targetSec = target,
                    keepAiLengths = !isOn(Opt.MOOD) && intents.mood == null,
                                )
                            fixPlan = pinEdges(ruled, CutRefiner.refine(ruled, durations, insights.toMap(), grid))
                            fixedNotes += "replaced dull or broken shots"
                            needRender = true
                        }
                    }
                    if (needRender) {
                        plan = fixPlan
                        video = renderPlan(fixPlan, musicNow)
                        report = inspect(fixPlan, video, musicNow)
                    } else if (QaIssue.ROTATION in report.issues) {
                        // Cheap fix, no re-render: just set the rotation flag.
                        val upright = File(video.parentFile, "upright_${System.currentTimeMillis()}.mp4")
                        runCatching { withContext(Dispatchers.IO) { QualityCheck.fixRotation(video, report.rotationFix, upright) } }
                            .onSuccess { video.delete(); video = upright; fixedNotes += "turned the video upright"; report = inspect(newPlan, video, musicNow) }
                    }
                    if (needRender && QaIssue.ROTATION in report.issues) {
                        val upright = File(video.parentFile, "upright_${System.currentTimeMillis()}.mp4")
                        runCatching { withContext(Dispatchers.IO) { QualityCheck.fixRotation(video, report.rotationFix, upright) } }
                            .onSuccess { video.delete(); video = upright; fixedNotes += "turned the video upright"; report = inspect(plan ?: newPlan, video, musicNow) }
                    }
                }
                output = video
                qa = report
                if (usedSafe) settings.safeRender = true // this phone needs it; skip the fast path next time
                chat += ChatMessage(false,
                    if (report.ok) "✅ Checked the result: " + report.items.joinToString(", ") { it.label.lowercase() } + "." +
                        (if (fixedNotes.isNotEmpty()) " Fixed: " + fixedNotes.joinToString("; ") + "." else "")
                    else "⚠️ Still not perfect: " + report.issues.values.joinToString("; ") +
                        (if (fixedNotes.isNotEmpty()) ". Fixed: " + fixedNotes.joinToString("; ") else "") +
                        ". Tell me what to change, or try another look/song.",
                    isError = !report.ok)
                (if (musicSource == MusicSource.LIBRARY) libSong else songNow)?.credit?.let { if (musicNow != null) chat += ChatMessage(false, "🎵 $it. The credit is added to the text when you share.") }
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat += ChatMessage(false, e.message ?: "Something went wrong.", true)
                status = null
            } finally {
                busy = false
                progress = -1
            }
        }
    }

    /** Credit to include when sharing (Creative Commons songs need it). */
    val shareCredit: String?
        get() = when (musicSource) { MusicSource.LIBRARY -> libSong?.credit; MusicSource.MY_SONG -> null; else -> null }

    fun cancel() {
        job?.cancel()
        busy = false; status = "Cancelled."; progress = -1
    }

    fun saveToGallery(onDone: (Uri?) -> Unit) {
        val file = output ?: return
        viewModelScope.launch {
            val uri = runCatching { Renderer.saveToGallery(ctx, file) }.getOrNull()
            status = if (uri != null) "Saved to Movies/PromptCut." else "Couldn't save the video."
            onDone(uri)
        }
    }
}
