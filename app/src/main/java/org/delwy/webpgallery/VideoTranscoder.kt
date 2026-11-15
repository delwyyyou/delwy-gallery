package org.delwy.webpgallery

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.media.AudioFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VideoOptions(
    val quality: Int = 1,
    val maxSide: Int = 0,
    val include: Boolean = false,
)

class TranscodeCanceled : Exception("Отменено")

object VideoTranscoder {

    private val opusRates = setOf(8000, 12000, 16000, 24000, 48000)

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private class AudioResult(val format: MediaFormat, val samples: List<Sample>)


    private fun vp9Format(w: Int, h: Int, bitrate: Int, fps: Int): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }

    private fun findVp9Encoder(w: Int, h: Int, bitrate: Int, fps: Int): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(vp9Format(w, h, bitrate, fps))

    fun isSupported(): Boolean = findVp9Encoder(1280, 720, 2_000_000, 30) != null


    fun process(
        ctx: Context,
        img: GalleryImage,
        opt: VideoOptions,
        onProgress: (Float) -> Unit,
        canceled: () -> Boolean,
    ): Result {
        val cr: ContentResolver = ctx.contentResolver
        val base = img.name.substringBeforeLast('.')
        val dir = if (img.relativePath.startsWith("DCIM") ||
            img.relativePath.startsWith("Movies") ||
            img.relativePath.startsWith("Pictures")
        ) img.relativePath else "Movies/WebP Gallery"

        val taken = img.date
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.webm")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/webm")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val newUri = cr.insert(collection, values) ?: error("insert failed")
        try {
            cr.openFileDescriptor(newUri, "rw")!!.use { pfd ->
                transcode(ctx, img.uri, opt, pfd.fileDescriptor, onProgress, canceled)
            }
            val outSize = cr.openFileDescriptor(newUri, "r")!!.use { it.statSize }
            if (outSize <= 0 || outSize >= img.size) {
                cr.delete(newUri, null, null)
                return Result(img.size, img.size, skipped = true)
            }
            setFileTime(cr, newUri, img.dateModified * 1000)
            cr.update(newUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            ImageProcessor.restoreDates(cr, newUri, taken, img.dateModified)
            return Result(img.size, outSize)
        } catch (e: Throwable) {
            cr.delete(newUri, null, null)
            throw e
        }
    }

    private fun setFileTime(cr: ContentResolver, uri: Uri, modifiedMs: Long) {
        try {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) File(c.getString(0)).setLastModified(modifiedMs)
            }
        } catch (e: Throwable) {
        }
    }

    private fun transcode(
        ctx: Context,
        uri: Uri,
        opt: VideoOptions,
        fd: FileDescriptor,
        onProgress: (Float) -> Unit,
        canceled: () -> Boolean,
    ) {
        val audio = transcodeAudio(ctx, uri, canceled)
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        try {
            transcodeVideo(ctx, uri, opt, audio, muxer, onProgress, canceled)
        } finally {
            runCatching { muxer.release() }
        }
    }

    private fun newExtractor(ctx: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
        } catch (e: Throwable) {
            ex.release()
            throw e
        }
        return ex
    }


    private fun transcodeVideo(
        ctx: Context,
        uri: Uri,
        opt: VideoOptions,
        audio: AudioResult?,
        muxer: MediaMuxer,
        onProgress: (Float) -> Unit,
        canceled: () -> Boolean,
    ) {
        val ex = newExtractor(ctx, uri)
        try {
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { track = i; break }
            }
            if (track < 0) throw IllegalStateException("В файле нет видеодорожки")
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)

            val srcW = inFmt.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (inFmt.containsKey(MediaFormat.KEY_ROTATION))
                inFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
                inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = (inFmt.getNumber(MediaFormat.KEY_FRAME_RATE)?.toInt() ?: 30).coerceIn(10, 60)

            val swap = rotation == 90 || rotation == 270
            var outW = if (swap) srcH else srcW
            var outH = if (swap) srcW else srcH
            if (opt.maxSide > 0 && maxOf(outW, outH) > opt.maxSide) {
                val s = opt.maxSide.toDouble() / maxOf(outW, outH)
                outW = (outW * s).toInt()
                outH = (outH * s).toInt()
            }
            outW = (outW and 1.inv()).coerceAtLeast(2)
            outH = (outH and 1.inv()).coerceAtLeast(2)

            val bpp = when (opt.quality) { 0 -> 0.035; 2 -> 0.10; else -> 0.06 }
            val bitrate = (outW.toLong() * outH * fps * bpp).toLong()
                .coerceIn(300_000L, 40_000_000L).toInt()

            val encName = findVp9Encoder(outW, outH, bitrate, fps)
                ?: throw IllegalStateException("На устройстве нет VP9-кодера")

            val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            val enc = try {
                MediaCodec.createByCodecName(encName)
            } catch (e: Throwable) {
                dec.release()
                throw e
            }
            try {
                inFmt.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                dec.configure(inFmt, null, null, 0)
                dec.start()
                enc.configure(vp9Format(outW, outH, bitrate, fps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()

                val dInfo = MediaCodec.BufferInfo()
                val eInfo = MediaCodec.BufferInfo()
                val conv = FrameConverter(rotation, outW, outH)
                val sink = WebmSink(muxer, audio)
                var exDone = false
                var encInEos = false
                var encDone = false
                var encIn = -1

                while (!encDone) {
                    if (canceled()) throw TranscodeCanceled()
                    var progressed = false

                    if (!exDone) {
                        val i = dec.dequeueInputBuffer(0)
                        if (i >= 0) {
                            val ib = dec.getInputBuffer(i)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                exDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                            progressed = true
                        }
                    }

                    if (!encInEos) {
                        if (encIn < 0) encIn = enc.dequeueInputBuffer(0)
                        if (encIn >= 0) {
                            val o = dec.dequeueOutputBuffer(dInfo, 0)
                            if (o >= 0) {
                                val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                if (dInfo.size > 0) {
                                    val src = dec.getOutputImage(o)
                                        ?: throw IllegalStateException("Декодер не отдаёт кадры в YUV")
                                    try {
                                        val dst = enc.getInputImage(encIn)
                                            ?: throw IllegalStateException("Кодер не принимает YUV")
                                        conv.convert(src, dst)
                                        enc.queueInputBuffer(
                                            encIn, 0, outW * outH * 3 / 2,
                                            dInfo.presentationTimeUs, flags
                                        )
                                    } finally {
                                        src.close()
                                    }
                                    encIn = -1
                                    if (eos) encInEos = true
                                } else if (eos) {
                                    enc.queueInputBuffer(
                                        encIn, 0, 0, dInfo.presentationTimeUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    encIn = -1
                                    encInEos = true
                                }
                                dec.releaseOutputBuffer(o, false)
                                progressed = true
                            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                progressed = true
                            }
                        }
                    }

                    val eo = enc.dequeueOutputBuffer(eInfo, 0)
                    when {
                        eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            sink.onVideoFormat(enc.outputFormat)
                            progressed = true
                        }
                        eo >= 0 -> {
                            val b = enc.getOutputBuffer(eo)!!
                            if (eInfo.size > 0 && eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (!sink.started) sink.onVideoFormat(enc.outputFormat)
                                b.position(eInfo.offset)
                                b.limit(eInfo.offset + eInfo.size)
                                sink.writeVideo(b, eInfo)
                                if (durationUs > 0) {
                                    onProgress((eInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                            if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                            enc.releaseOutputBuffer(eo, false)
                            progressed = true
                        }
                    }

                    if (!progressed) Thread.sleep(2)
                }

                sink.finish()
                muxer.stop()
            } finally {
                runCatching { dec.stop() }
                dec.release()
                runCatching { enc.stop() }
                enc.release()
            }
        } finally {
            ex.release()
        }
    }


    private class WebmSink(private val muxer: MediaMuxer, private val audio: AudioResult?) {
        var started = false
            private set
        private var vTrack = -1
        private var aTrack = -1
        private var ai = 0

        fun onVideoFormat(f: MediaFormat) {
            if (started) return
            vTrack = muxer.addTrack(f)
            if (audio != null) aTrack = muxer.addTrack(audio.format)
            muxer.start()
            started = true
        }

        fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            flushAudio(info.presentationTimeUs)
            muxer.writeSampleData(vTrack, buf, info)
        }

        fun finish() = flushAudio(Long.MAX_VALUE)

        private fun flushAudio(untilUs: Long) {
            val a = audio ?: return
            while (ai < a.samples.size && a.samples[ai].ptsUs <= untilUs) {
                val s = a.samples[ai++]
                val bi = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), bi)
            }
        }
    }


    private class PlaneMap(
        private val srcW: Int,
        private val srcH: Int,
        private val outW: Int,
        private val outH: Int,
        rot: Int,
    ) {
        private val swapped = rot == 90 || rot == 270
        private val identity = rot == 0 && srcW == outW && srcH == outH
        private val xs: IntArray
        private val ys: IntArray

        private fun cl(v: Double, max: Int) = v.toInt().coerceIn(0, max - 1)

        init {
            fun a(ox: Int) = (ox + 0.5) / outW
            fun b(oy: Int) = (oy + 0.5) / outH
            when (rot) {
                90 -> {
                    xs = IntArray(outH) { cl(b(it) * srcW, srcW) }
                    ys = IntArray(outW) { cl((1 - a(it)) * srcH, srcH) }
                }
                270 -> {
                    xs = IntArray(outH) { cl((1 - b(it)) * srcW, srcW) }
                    ys = IntArray(outW) { cl(a(it) * srcH, srcH) }
                }
                180 -> {
                    xs = IntArray(outW) { cl((1 - a(it)) * srcW, srcW) }
                    ys = IntArray(outH) { cl((1 - b(it)) * srcH, srcH) }
                }
                else -> {
                    xs = IntArray(outW) { cl(a(it) * srcW, srcW) }
                    ys = IntArray(outH) { cl(b(it) * srcH, srcH) }
                }
            }
        }

        fun transform(src: ByteArray, dst: ByteArray) {
            if (identity) {
                System.arraycopy(src, 0, dst, 0, minOf(src.size, dst.size))
                return
            }
            if (!swapped) {
                for (oy in 0 until outH) {
                    val row = ys[oy] * srcW
                    val o = oy * outW
                    for (ox in 0 until outW) dst[o + ox] = src[row + xs[ox]]
                }
            } else {
                for (oy in 0 until outH) {
                    val sx = xs[oy]
                    val o = oy * outW
                    for (ox in 0 until outW) dst[o + ox] = src[ys[ox] * srcW + sx]
                }
            }
        }
    }

    private class FrameConverter(
        private val rotation: Int,
        private val outW: Int,
        private val outH: Int,
    ) {
        private var cw = -1
        private var chh = -1
        private var mapY: PlaneMap? = null
        private var mapC: PlaneMap? = null
        private var srcY = ByteArray(0)
        private var srcU = ByteArray(0)
        private var srcV = ByteArray(0)
        private val dstY = ByteArray(outW * outH)
        private val dstU = ByteArray((outW / 2) * (outH / 2))
        private val dstV = ByteArray((outW / 2) * (outH / 2))

        fun convert(src: Image, dst: Image) {
            if (src.format != ImageFormat.YUV_420_888) {
                throw IllegalStateException("Формат видео не поддерживается (10-бит/HDR)")
            }
            val crop = src.cropRect
            val w = crop.width() and 1.inv()
            val h = crop.height() and 1.inv()
            if (w != cw || h != chh) {
                cw = w
                chh = h
                mapY = PlaneMap(w, h, outW, outH, rotation)
                mapC = PlaneMap(w / 2, h / 2, outW / 2, outH / 2, rotation)
                srcY = ByteArray(w * h)
                srcU = ByteArray((w / 2) * (h / 2))
                srcV = ByteArray((w / 2) * (h / 2))
            }
            val p = src.planes
            readPlane(p[0], crop.left, crop.top, w, h, srcY)
            readPlane(p[1], crop.left / 2, crop.top / 2, w / 2, h / 2, srcU)
            readPlane(p[2], crop.left / 2, crop.top / 2, w / 2, h / 2, srcV)
            mapY!!.transform(srcY, dstY)
            mapC!!.transform(srcU, dstU)
            mapC!!.transform(srcV, dstV)
            val d = dst.planes
            writePlane(d[0], dstY, outW, outH)
            writePlane(d[1], dstU, outW / 2, outH / 2)
            writePlane(d[2], dstV, outW / 2, outH / 2)
        }

        private fun readPlane(p: Image.Plane, x0: Int, y0: Int, w: Int, h: Int, dst: ByteArray) {
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            if (ps == 1) {
                for (r in 0 until h) {
                    buf.position((y0 + r) * rs + x0)
                    buf.get(dst, r * w, w)
                }
            } else {
                for (r in 0 until h) {
                    val base = (y0 + r) * rs + x0 * ps
                    for (c in 0 until w) dst[r * w + c] = buf.get(base + c * ps)
                }
            }
        }

        private fun writePlane(p: Image.Plane, data: ByteArray, w: Int, h: Int) {
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            if (ps == 1) {
                for (r in 0 until h) {
                    buf.position(r * rs)
                    buf.put(data, r * w, w)
                }
            } else {
                for (r in 0 until h) {
                    val base = r * rs
                    for (c in 0 until w) buf.put(base + c * ps, data[r * w + c])
                }
            }
        }
    }


    private class ShortQueue {
        private val chunks = ArrayDeque<ShortArray>()
        private var offset = 0
        var size = 0
            private set

        fun push(a: ShortArray) {
            if (a.isNotEmpty()) {
                chunks.addLast(a)
                size += a.size
            }
        }

        fun read(dst: ShortArray, n: Int) {
            var left = n
            var w = 0
            while (left > 0) {
                val c = chunks.first()
                val take = minOf(left, c.size - offset)
                System.arraycopy(c, offset, dst, w, take)
                w += take
                left -= take
                offset += take
                size -= take
                if (offset == c.size) {
                    chunks.removeFirst()
                    offset = 0
                }
            }
        }
    }

    private class Resampler(inRate: Int, outRate: Int, private val ch: Int) {
        private val step = inRate.toDouble() / outRate
        private var pos = 0.0
        private val last = ShortArray(ch)

        fun process(input: ShortArray): ShortArray {
            val frames = input.size / ch
            if (frames == 0) return input
            val out = ShortArray(((frames / step).toInt() + 4) * ch)
            var o = 0
            while (pos.toInt() + 1 <= frames) {
                val i0 = pos.toInt()
                val frac = pos - i0
                for (c in 0 until ch) {
                    val s0 = if (i0 == 0) last[c].toInt() else input[(i0 - 1) * ch + c].toInt()
                    val s1 = input[i0 * ch + c].toInt()
                    out[o++] = (s0 + (s1 - s0) * frac).toInt().toShort()
                }
                pos += step
            }
            pos -= frames
            for (c in 0 until ch) last[c] = input[(frames - 1) * ch + c]
            return out.copyOf(o)
        }
    }

    private fun transcodeAudio(ctx: Context, uri: Uri, canceled: () -> Boolean): AudioResult? {
        val ex = newExtractor(ctx, uri)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) {
            ex.release()
            return null
        }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        var enc: MediaCodec? = null
        try {
            dec.configure(inFmt, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            val pcm = ShortQueue()
            val samples = ArrayList<Sample>()
            var outFormat: MediaFormat? = null
            var resampler: Resampler? = null
            var channels = 2
            var outRate = 48000
            var pcmFloat = false
            var exDone = false
            var decDone = false
            var encEos = false
            var encDone = false
            var firstPts = -1L
            var fedFrames = 0L

            fun setup(f: MediaFormat) {
                val inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (channels > 2) throw IllegalStateException("Больше двух аудиоканалов не поддерживается")
                pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                outRate = if (inRate in opusRates) inRate else 48000
                resampler = if (outRate != inRate) Resampler(inRate, outRate, channels) else null
                val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, outRate, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 64_000 else 96_000)
                }
                val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(ef)
                    ?: throw IllegalStateException("На устройстве нет Opus-кодера для звука")
                enc = MediaCodec.createByCodecName(name).also {
                    it.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                }
            }

            while (!encDone) {
                if (canceled()) throw TranscodeCanceled()
                if (decDone && enc == null) return null // звуковых кадров не оказалось
                var progressed = false

                if (!exDone) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val ib = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            exDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        progressed = true
                    }
                }

                if (!decDone && pcm.size < 200_000) {
                    val o = dec.dequeueOutputBuffer(info, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (enc == null) setup(dec.outputFormat)
                        progressed = true
                    } else if (o >= 0) {
                        if (enc == null) setup(dec.outputFormat)
                        if (info.size > 0) {
                            if (firstPts < 0) firstPts = info.presentationTimeUs
                            val bb = dec.getOutputBuffer(o)!!
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            bb.order(ByteOrder.nativeOrder())
                            val shorts: ShortArray = if (pcmFloat) {
                                val fb = bb.asFloatBuffer()
                                ShortArray(fb.remaining()).also { a ->
                                    for (k in a.indices) {
                                        a[k] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                    }
                                }
                            } else {
                                val sb = bb.asShortBuffer()
                                ShortArray(sb.remaining()).also { sb.get(it) }
                            }
                            pcm.push(resampler?.process(shorts) ?: shorts)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decDone = true
                        dec.releaseOutputBuffer(o, false)
                        progressed = true
                    }
                }

                val e = enc
                if (e != null && !encEos) {
                    val avail = pcm.size / channels * channels
                    if (avail > 0 || decDone) {
                        val i = e.dequeueInputBuffer(0)
                        if (i >= 0) {
                            val buf = e.getInputBuffer(i)!!
                            buf.clear()
                            val pts = maxOf(firstPts, 0L) + fedFrames * 1_000_000L / outRate
                            if (avail == 0) {
                                e.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encEos = true
                            } else {
                                val maxS = (buf.capacity() / 2 / channels) * channels
                                val n = minOf(avail, maxS)
                                val tmp = ShortArray(n)
                                pcm.read(tmp, n)
                                buf.order(ByteOrder.nativeOrder())
                                buf.asShortBuffer().put(tmp)
                                e.queueInputBuffer(i, 0, n * 2, pts, 0)
                                fedFrames += n / channels
                            }
                            progressed = true
                        }
                    }
                }

                if (e != null) {
                    val o = e.dequeueOutputBuffer(info, 0)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outFormat = e.outputFormat
                            progressed = true
                        }
                        o >= 0 -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val b = e.getOutputBuffer(o)!!
                                b.position(info.offset)
                                b.limit(info.offset + info.size)
                                val arr = ByteArray(info.size)
                                b.get(arr)
                                samples += Sample(arr, info.presentationTimeUs, info.flags)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                            e.releaseOutputBuffer(o, false)
                            progressed = true
                        }
                    }
                }

                if (!progressed) Thread.sleep(1)
            }
            val f = outFormat ?: throw IllegalStateException("Нет формата аудио")
            return AudioResult(f, samples)
        } finally {
            runCatching { dec.stop() }
            dec.release()
            enc?.let {
                runCatching { it.stop() }
                it.release()
            }
            ex.release()
        }
    }
}
