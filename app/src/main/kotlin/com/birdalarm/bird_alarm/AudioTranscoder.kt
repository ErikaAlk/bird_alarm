package com.birdalarm.bird_alarm

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 下载的鸟鸣转成 AAC（m4a）并放大 [gain] 倍：xeno-canto 的录音普遍偏小声，当闹钟不够响。
 * 很耗时，**必须在后台线程调**（Flutter 版曾在主线程转码，下载时整个界面卡死）。
 */
object AudioTranscoder {
    fun transcode(inputPath: String, outputPath: String, gain: Float): String {
        val decoded = decodeToPcm(inputPath, gain)
        encodeAac(decoded.pcm, decoded.sampleRate, decoded.channelCount, outputPath)
        return outputPath
    }

    private data class DecodedAudio(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channelCount: Int
    )

    private fun decodeToPcm(inputPath: String, gain: Float): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = index
                format = candidate
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Missing audio mime")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val decoder = MediaCodec.createDecoderByType(mime)
        val bufferInfo = MediaCodec.BufferInfo()
        val output = ByteArrayOutputStream()
        decoder.configure(format, null, null, 0)
        decoder.start()

        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    val sampleSize =
                        if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            amplifyPcm16(chunk, gain)
                            output.write(chunk)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()
        return DecodedAudio(output.toByteArray(), sampleRate, channelCount)
    }

    private fun amplifyPcm16(bytes: ByteArray, gain: Float) {
        var index = 0
        while (index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xff
            val high = bytes[index + 1].toInt()
            val sample = (high shl 8) or low
            val amplified = max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), (sample * gain).toInt()))
            bytes[index] = (amplified and 0xff).toByte()
            bytes[index + 1] = ((amplified shr 8) and 0xff).toByte()
            index += 2
        }
    }

    private fun encodeAac(pcm: ByteArray, sampleRate: Int, channelCount: Int, outputPath: String) {
        File(outputPath).parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerFrame = max(1, channelCount * 2)
        var trackIndex = -1
        var muxerStarted = false
        var inputOffset = 0
        var inputDone = false

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        while (true) {
            if (!inputDone) {
                val inputBufferIndex = encoder.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    val remaining = pcm.size - inputOffset
                    if (remaining <= 0) {
                        val presentationTimeUs =
                            (inputOffset / bytesPerFrame) * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val size = min(inputBuffer?.capacity() ?: 0, remaining)
                        inputBuffer?.put(pcm, inputOffset, size)
                        val presentationTimeUs =
                            (inputOffset / bytesPerFrame) * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0)
                        inputOffset += size
                    }
                }
            }

            when (val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) continue
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer: ByteBuffer? = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            }
                        }
                        val done =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (done) break
                    }
                }
            }
        }

        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }
}
