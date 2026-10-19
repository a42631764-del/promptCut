package com.promptcut.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

enum class QaIssue { ROTATION, DURATION, FPS, NO_SOUND, MUSIC_MISSING, COLOUR, BLACK, DULL_OPENING, DULL_SHOTS }

/** One line of the checklist shown to the user. */
data class QaItem(val label: String, val ok: Boolean, val detail: String = "", val fixed: Boolean = false)

data class QaReport(
    val items: List<QaItem>,
    val issues: Map<QaIssue, String>,
    val colourSegments: List<Int> = emptyList(),
    val dullSegments: List<Int> = emptyList(),
    val rotationFix: Int = 0,
) {
    val ok get() = issues.isEmpty()
    /** Problems that need the AI to change the story (not just a technical fix). */
    val storyIssues get() = issues.keys.filter { it in setOf(QaIssue.DULL_OPENING, QaIssue.DULL_SHOTS, QaIssue.BLACK, QaIssue.DURATION) }
    fun notesForAi(plan: EditPlan): String = buildString {
        appendLine("A quality check of the rendered video found these problems. Fix them:")
        issues.forEach { (k, v) -> appendLine("- $k: $v") }
        if (dullSegments.isNotEmpty()) appendLine("Dull segments (replace them with more interesting moments or remove them): " +
            dullSegments.joinToString { i -> plan.segments.getOrNull(i)?.let { "#${i + 1} clip ${it.clip} ${round1(it.start)}-${round1(it.end)} s" } ?: "" })
        appendLine("Keep everything else about the edit the same.")
    }
}

/**
 * The app watches its own result before showing it: is it upright, the right length and frame rate,
 * does it have sound and audible music, are the colours natural compared with the source, and does it
 * avoid opening on a dull shot? Problems feed an automatic fix round.
 */
object QualityCheck {

    suspend fun check(
        context: Context, video: File, plan: EditPlan, clips: List<Clip>, insights: Map<Int, ClipInsight>,
        expectMusic: Boolean, expectSound: Boolean,
        targetSec: Double? = null, usableSec: Double = Double.MAX_VALUE,
    ): QaReport = withContext(Dispatchers.Default) {
        val items = mutableListOf<QaItem>()
        val issues = linkedMapOf<QaIssue, String>()
        var rotationFix = 0

        // ---- format: orientation, length, frame rate
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            val wantPortrait = plan.aspect.height > plan.aspect.width
            val wantLandscape = plan.aspect.width > plan.aspect.height
            val upright = when {
                wantPortrait -> dh > dw
                wantLandscape -> dw > dh
                else -> true
            }
            if (upright) items += QaItem("Upright, ${plan.aspect.key}", true)
            else {
                issues[QaIssue.ROTATION] = "picture is sideways (${dw}x$dh shown, ${plan.aspect.key} wanted)"
                items += QaItem("Upright", false, "picture was sideways")
                rotationFix = (rot + 90) % 360
            }
            val durSec = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000.0
            val tol = maxOf(0.7, plan.totalSec * 0.06)
            // The length the user asked for (when there is enough footage for it), else the planned length.
            val want = targetSec?.takeIf { it <= usableSec - 0.5 }
            val wantTol = want?.let { maxOf(1.5, it * 0.1) }
            when {
                want != null && kotlin.math.abs(durSec - want) > wantTol!! -> {
                    issues[QaIssue.DURATION] = "video is ${round1(durSec)} s but ${round1(want)} s was asked for"
                    items += QaItem("Length you asked for", false, "${round1(durSec)} s instead of ${round1(want)} s")
                }
                kotlin.math.abs(durSec - plan.totalSec) > tol -> {
                    issues[QaIssue.DURATION] = "video is ${round1(durSec)} s but the edit is ${round1(plan.totalSec)} s"
                    items += QaItem("Length", false, "${round1(durSec)} s instead of ${round1(plan.totalSec)} s")
                }
                want != null -> items += QaItem("Length ${round1(durSec)} s (asked ${round1(want)} s)", true)
                else -> items += QaItem("Length ${round1(durSec)} s", true)
            }
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            if (frames != null && durSec > 0) {
                val fps = frames / durSec
                if (fps <= 32) items += QaItem("Smooth ${fps.toInt()} fps", true)
                else { issues[QaIssue.FPS] = "${fps.toInt()} fps"; items += QaItem("Frame rate", false, "${fps.toInt()} fps (too high for sharing)") }
            }
        }

        // ---- sound and music
        val env = AudioTools.envelope(context, Uri.fromFile(video), 0.05, plan.totalSec + 2)
        if (env == null) {
            if (expectSound || expectMusic) { issues[QaIssue.NO_SOUND] = "video has no sound"; items += QaItem("Sound", false, "no sound track") }
        } else if (expectMusic) {
            // Near-silent half-second windows mean the music isn't really there.
            val win = 10
            val windows = env.toList().chunked(win).filter { it.size == win }
            val silent = windows.count { w -> w.average() < 0.006 }
            val share = if (windows.isEmpty()) 1.0 else silent.toDouble() / windows.size
            if (share > 0.2) { issues[QaIssue.MUSIC_MISSING] = "${(share * 100).toInt()}% of the video is silent"; items += QaItem("Music", false, "too quiet or missing in places") }
            else items += QaItem("Music plays throughout", true)
        } else items += QaItem("Sound", true)

        // ---- picture: compare each shot with its source
        val colourSegs = mutableListOf<Int>()
        val problems = mutableListOf<String>()
        var black = false
        val byIndex = clips.associateBy { it.meta.index }
        val targetAspect = plan.aspect.width.toDouble() / plan.aspect.height
        MediaMetadataRetriever().use { outR ->
            outR.setDataSource(video.absolutePath)
            var t = 0.0
            val sampleEvery = maxOf(1, plan.segments.size / 12)
            plan.segments.forEachIndexed { i, seg ->
                val mid = t + seg.outputSec / 2
                t += seg.outputSec
                if (i % sampleEvery != 0) return@forEachIndexed
                val clip = byIndex[seg.clip] ?: return@forEachIndexed
                val outBmp = frame(outR, mid) ?: return@forEachIndexed
                val srcBmp = MediaMetadataRetriever().use { sr ->
                    runCatching { sr.setDataSource(context, clip.uri); frame(sr, seg.start + seg.outputSec / 2 * seg.speed) }.getOrNull()
                } ?: return@forEachIndexed
                val so = statsOf(outBmp, targetAspect)
                // Compare like with like: if the user cropped the clip, measure the source inside that crop only.
                val srcArea = clip.meta.crop?.let { c ->
                    val x = (c.x0 * srcBmp.width).toInt().coerceIn(0, srcBmp.width - 8)
                    val y = (c.y0 * srcBmp.height).toInt().coerceIn(0, srcBmp.height - 8)
                    Bitmap.createBitmap(srcBmp, x, y,
                        (c.width * srcBmp.width).toInt().coerceIn(8, srcBmp.width - x), (c.height * srcBmp.height).toInt().coerceIn(8, srcBmp.height - y))
                } ?: srcBmp
                val ss = statsOf(srcArea, if (clip.meta.crop != null) srcArea.width.toDouble() / srcArea.height else targetAspect)
                outBmp.recycle(); srcBmp.recycle()
                QaMetrics.colourProblem(ss, so, plan.color)?.let { p ->
                    if (p == "black picture") black = true else { colourSegs += i; problems += p }
                }
            }
        }
        if (colourSegs.isNotEmpty()) {
            issues[QaIssue.COLOUR] = problems.distinct().joinToString()
            items += QaItem("Natural colours", false, problems.distinct().joinToString() + " in ${colourSegs.size} shot(s)")
        } else items += QaItem("Natural colours", true)
        if (black) { issues[QaIssue.BLACK] = "a shot is black in the middle"; items += QaItem("No black shots", false) }

        // ---- story: dull shots (plain ground/wall, nothing happening)
        val dull = plan.segments.mapIndexedNotNull { i, seg ->
            val mid = seg.start + (seg.end - seg.start) / 2
            val shot = insights[seg.clip]?.shots?.firstOrNull { mid >= it.start && mid <= it.end }
            if (shot?.interest == 0) i else null
        }
        val anyGoodShot = insights.values.any { ins -> ins.shots.any { it.interest > 0 } }
        if (anyGoodShot && dull.contains(0)) issues[QaIssue.DULL_OPENING] = "the video opens on a dull shot"
        if (anyGoodShot && dull.size > maxOf(1, plan.segments.size / 4)) issues[QaIssue.DULL_SHOTS] = "${dull.size} of ${plan.segments.size} shots are dull"
        items += if (dull.isEmpty() || !anyGoodShot) QaItem("Interesting shots", true)
        else QaItem("Interesting shots", false, if (dull.contains(0)) "opens on a dull shot" else "${dull.size} dull shot(s)")

        QaReport(items, issues, colourSegs, dull, rotationFix)
    }

    private fun frame(r: MediaMetadataRetriever, tSec: Double): Bitmap? = runCatching {
        r.getScaledFrameAtTime((tSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
    }.getOrNull()

    /** Centre-crop to the output's aspect (so source and output show the same area), then measure colour. */
    private fun statsOf(b: Bitmap, aspect: Double): ColorStats {
        var cw = b.width; var ch = b.height
        if (cw.toDouble() / ch > aspect) cw = (ch * aspect).toInt() else ch = (cw / aspect).toInt()
        val crop = Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(crop, 96, (96 * ch / cw).coerceAtLeast(8), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val s = QaMetrics.stats(px, small.width, small.height)
        if (crop !== b) crop.recycle(); small.recycle()
        return s
    }

    /** Fixes a sideways video without re-encoding: copy all tracks and set the rotation flag. */
    fun fixRotation(video: File, degrees: Int, out: File) {
        val ex = MediaExtractor()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.setDataSource(video.absolutePath)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) { ex.selectTrack(i); map[i] = muxer.addTrack(ex.getTrackFormat(i)) }
            muxer.setOrientationHint(degrees)
            muxer.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(map[ex.sampleTrackIndex]!!, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }
}
