package com.spasfonk.obsidianrecorder.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object AudioSplitter {

    fun splitBySize(sourceFile: File, targetPartBytes: Long, outputDir: File): List<File> {
        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }
        require(audioTrackIndex >= 0 && audioFormat != null) { "Aucune piste audio trouvée" }
        extractor.selectTrack(audioTrackIndex)

        val outputFiles = mutableListOf<File>()
        val bufferSize = 256 * 1024
        val byteBuffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        var partIndex = 1
        var currentMuxer: MediaMuxer? = null
        var currentTrack = -1
        var bytesWrittenInPart = 0L
        var samplesWrittenInPart = 0
        var basePresentationTimeUs = -1L
        var pendingSwitch = false

        fun closeCurrentPartSafely() {
            try {
                if (samplesWrittenInPart > 0) currentMuxer?.stop()
            } catch (e: Exception) {
                // Aucun sample n'a pu être finalisé correctement pour ce fragment.
            }
            try {
                currentMuxer?.release()
            } catch (_: Exception) { }
        }

        fun openNewPart(): File {
            val baseName = sourceFile.nameWithoutExtension
            val file = File(outputDir, "${baseName}_part${partIndex}.m4a")
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            currentTrack = muxer.addTrack(audioFormat)
            muxer.start()
            currentMuxer = muxer
            bytesWrittenInPart = 0L
            samplesWrittenInPart = 0
            basePresentationTimeUs = -1L
            outputFiles.add(file)
            partIndex++
            return file
        }

        openNewPart()

        while (true) {
            byteBuffer.clear()
            val sampleSize = extractor.readSampleData(byteBuffer, 0)
            if (sampleSize < 0) break

            val sampleTimeUs = extractor.sampleTime
            val sampleFlags = extractor.sampleFlags
            val isSync = (sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0

            if (bytesWrittenInPart + sampleSize > targetPartBytes && samplesWrittenInPart > 0) {
                pendingSwitch = true
            }

            if (pendingSwitch && isSync) {
                closeCurrentPartSafely()
                openNewPart()
                pendingSwitch = false
                basePresentationTimeUs = sampleTimeUs
            }

            if (basePresentationTimeUs < 0) basePresentationTimeUs = sampleTimeUs

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = sampleTimeUs - basePresentationTimeUs
            bufferInfo.flags = sampleFlags

            currentMuxer?.writeSampleData(currentTrack, byteBuffer, bufferInfo)
            bytesWrittenInPart += sampleSize
            samplesWrittenInPart += 1
            extractor.advance()
        }

        closeCurrentPartSafely()
        extractor.release()

        return outputFiles
    }
}
