package com.opendashcam;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Builds an MP4 with a static waveform video track and AAC audio transcoded from MP3. */
public final class WaveformMp4Composer {
    private static final String TAG = "WaveformMp4Composer";
    private static final long TIMEOUT_US = 10_000L;
    private static final long MAX_LOOP_MS = 5 * 60 * 1000L;
    private static final int FRAME_RATE = 1;
    private static final int VIDEO_BIT_RATE = 2_000_000;
    private static final int AUDIO_BIT_RATE = 128_000;
    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";

    private WaveformMp4Composer() {
    }

    public static File compose(
            File mp3File,
            Bitmap waveformBitmap,
            WaveformAnalyzer.WaveformAnalysis analysis,
            File outputFile
    ) throws IOException {
        if (outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "Could not delete previous output file");
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(mp3File.getAbsolutePath());
        int sourceTrack = selectAudioTrack(extractor);
        if (sourceTrack < 0) {
            extractor.release();
            throw new IOException("No audio track in source MP3");
        }
        extractor.selectTrack(sourceTrack);
        MediaFormat sourceFormat = extractor.getTrackFormat(sourceTrack);
        String sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME);
        if (sourceMime == null) {
            extractor.release();
            throw new IOException("Unknown source audio mime");
        }

        int sampleRate = analysis.sampleRate > 0
                ? analysis.sampleRate
                : sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = analysis.channelCount > 0
                ? analysis.channelCount
                : sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        long durationUs = analysis.durationUs > 0
                ? analysis.durationUs
                : sourceFormat.getLong(MediaFormat.KEY_DURATION);
        int frameCount = Math.max(1, (int) Math.ceil(durationUs / 1_000_000.0));
        byte[] frameYuv = bitmapToYuv420(
                waveformBitmap,
                WaveformRenderer.VIDEO_WIDTH,
                WaveformRenderer.VIDEO_HEIGHT
        );

        MediaCodec audioDecoder = MediaCodec.createDecoderByType(sourceMime);
        audioDecoder.configure(sourceFormat, null, null, 0);
        audioDecoder.start();

        MediaFormat audioEncoderFormat = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount);
        audioEncoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioEncoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        audioEncoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioEncoder.configure(audioEncoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        MediaFormat videoFormat = MediaFormat.createVideoFormat(
                VIDEO_MIME,
                WaveformRenderer.VIDEO_WIDTH,
                WaveformRenderer.VIDEO_HEIGHT
        );
        videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        );
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoEncoder.start();

        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        boolean extractorDone = false;
        boolean decoderDone = false;
        boolean audioEncoderDone = false;
        boolean videoInputDone = false;
        boolean videoEncoderDone = false;
        int videoFrameIndex = 0;
        int audioTrackIndex = -1;
        int videoTrackIndex = -1;
        boolean muxerStarted = false;
        long loopStartedAt = System.currentTimeMillis();

        try {
            while (!audioEncoderDone || !videoEncoderDone) {
                if (System.currentTimeMillis() - loopStartedAt > MAX_LOOP_MS) {
                    throw new IOException("Waveform MP4 export timed out");
                }
                if (!extractorDone) {
                    extractorDone = feedDecoder(extractor, audioDecoder);
                }

                if (!decoderDone) {
                    decoderDone = pumpDecoderToEncoder(audioDecoder, audioEncoder, bufferInfo);
                }

                if (!videoInputDone) {
                    if (queueVideoFrame(videoEncoder, frameYuv, videoFrameIndex, frameCount)) {
                        if (videoFrameIndex >= frameCount - 1) {
                            videoInputDone = true;
                        } else {
                            videoFrameIndex++;
                        }
                    }
                }

                MuxerState muxerState = drainEncoderOutputs(
                        audioEncoder,
                        videoEncoder,
                        muxer,
                        bufferInfo,
                        audioTrackIndex,
                        videoTrackIndex,
                        muxerStarted
                );
                audioTrackIndex = muxerState.audioTrackIndex;
                videoTrackIndex = muxerState.videoTrackIndex;
                muxerStarted = muxerState.muxerStarted;

                if (muxerState.audioEncoderDone) {
                    audioEncoderDone = true;
                }
                if (muxerState.videoEncoderDone) {
                    videoEncoderDone = true;
                }
            }
        } finally {
            extractor.release();
            audioDecoder.stop();
            audioDecoder.release();
            audioEncoder.stop();
            audioEncoder.release();
            videoEncoder.stop();
            videoEncoder.release();
            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
        }

        if (!outputFile.exists() || outputFile.length() == 0) {
            throw new IOException("Waveform MP4 export produced empty file");
        }
        return outputFile;
    }

    private static boolean feedDecoder(MediaExtractor extractor, MediaCodec audioDecoder) {
        int inputIndex = audioDecoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex < 0) {
            return false;
        }
        ByteBuffer inputBuffer = audioDecoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            return false;
        }
        int sampleSize = extractor.readSampleData(inputBuffer, 0);
        if (sampleSize < 0) {
            audioDecoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
            );
            return true;
        }
        audioDecoder.queueInputBuffer(
                inputIndex,
                0,
                sampleSize,
                extractor.getSampleTime(),
                0
        );
        extractor.advance();
        return false;
    }

    private static boolean pumpDecoderToEncoder(
            MediaCodec audioDecoder,
            MediaCodec audioEncoder,
            MediaCodec.BufferInfo bufferInfo
    ) {
        int outputIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 0);
        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return false;
        }
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            return false;
        }
        if (outputIndex < 0) {
            return false;
        }

        boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        ByteBuffer decodedBuffer = audioDecoder.getOutputBuffer(outputIndex);
        if (decodedBuffer != null && bufferInfo.size > 0) {
            queuePcmToEncoder(audioEncoder, decodedBuffer, bufferInfo);
        }
        audioDecoder.releaseOutputBuffer(outputIndex, false);
        if (eos) {
            queueEncoderEos(audioEncoder);
            return true;
        }
        return false;
    }

    private static boolean queueVideoFrame(
            MediaCodec videoEncoder,
            byte[] frameYuv,
            int frameIndex,
            int frameCount
    ) {
        if (frameIndex >= frameCount) {
            return false;
        }
        int inputIndex = videoEncoder.dequeueInputBuffer(0);
        if (inputIndex < 0) {
            return false;
        }
        ByteBuffer inputBuffer = videoEncoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            return false;
        }
        inputBuffer.clear();
        inputBuffer.put(frameYuv);
        int flags = frameIndex == frameCount - 1 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
        videoEncoder.queueInputBuffer(
                inputIndex,
                0,
                frameYuv.length,
                frameIndex * 1_000_000L,
                flags
        );
        return true;
    }

    private static final class MuxerState {
        int audioTrackIndex;
        int videoTrackIndex;
        boolean muxerStarted;
        boolean audioEncoderDone;
        boolean videoEncoderDone;

        MuxerState(int audioTrackIndex, int videoTrackIndex, boolean muxerStarted,
                   boolean audioEncoderDone, boolean videoEncoderDone) {
            this.audioTrackIndex = audioTrackIndex;
            this.videoTrackIndex = videoTrackIndex;
            this.muxerStarted = muxerStarted;
            this.audioEncoderDone = audioEncoderDone;
            this.videoEncoderDone = videoEncoderDone;
        }
    }

    private static MuxerState drainEncoderOutputs(
            MediaCodec audioEncoder,
            MediaCodec videoEncoder,
            MediaMuxer muxer,
            MediaCodec.BufferInfo bufferInfo,
            int audioTrackIndex,
            int videoTrackIndex,
            boolean muxerStarted
    ) throws IOException {
        boolean audioDone = false;
        boolean videoDone = false;

        while (true) {
            int audioOutputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (audioOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (audioTrackIndex < 0) {
                    audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                }
            } else if (audioOutputIndex >= 0) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    audioDone = true;
                }
                if (!muxerStarted && audioTrackIndex >= 0 && videoTrackIndex >= 0) {
                    muxer.start();
                    muxerStarted = true;
                }
                if (muxerStarted && bufferInfo.size > 0
                        && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer encodedData = audioEncoder.getOutputBuffer(audioOutputIndex);
                    if (encodedData != null) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                    }
                }
                audioEncoder.releaseOutputBuffer(audioOutputIndex, false);
            } else if (audioOutputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no-op
            } else {
                break;
            }
        }

        while (true) {
            int videoOutputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (videoOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (videoTrackIndex < 0) {
                    videoTrackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                }
            } else if (videoOutputIndex >= 0) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    videoDone = true;
                }
                if (!muxerStarted && audioTrackIndex >= 0 && videoTrackIndex >= 0) {
                    muxer.start();
                    muxerStarted = true;
                }
                if (muxerStarted && bufferInfo.size > 0
                        && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer encodedData = videoEncoder.getOutputBuffer(videoOutputIndex);
                    if (encodedData != null) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    }
                }
                videoEncoder.releaseOutputBuffer(videoOutputIndex, false);
            } else if (videoOutputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no-op
            } else {
                break;
            }
        }

        return new MuxerState(
                audioTrackIndex,
                videoTrackIndex,
                muxerStarted,
                audioDone,
                videoDone
        );
    }

    private static void queuePcmToEncoder(
            MediaCodec audioEncoder,
            ByteBuffer decodedBuffer,
            MediaCodec.BufferInfo bufferInfo
    ) {
        int encoderInputIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
        while (encoderInputIndex < 0) {
            encoderInputIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
        }
        ByteBuffer encoderInputBuffer = audioEncoder.getInputBuffer(encoderInputIndex);
        if (encoderInputBuffer == null) {
            return;
        }
        decodedBuffer.position(bufferInfo.offset);
        decodedBuffer.limit(bufferInfo.offset + bufferInfo.size);
        encoderInputBuffer.clear();
        encoderInputBuffer.put(decodedBuffer);
        audioEncoder.queueInputBuffer(
                encoderInputIndex,
                0,
                bufferInfo.size,
                bufferInfo.presentationTimeUs,
                bufferInfo.flags
        );
    }

    private static void queueEncoderEos(MediaCodec audioEncoder) {
        int encoderInputIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
        while (encoderInputIndex < 0) {
            encoderInputIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US);
        }
        audioEncoder.queueInputBuffer(
                encoderInputIndex,
                0,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
        );
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] bitmapToYuv420(Bitmap bitmap, int width, int height) {
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] yuv = new byte[width * height * 3 / 2];
        encodeYuv420Sp(yuv, argb, width, height);
        return yuv;
    }

    private static void encodeYuv420Sp(byte[] yuv, int[] argb, int width, int height) {
        int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pixel = argb[j * width + i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                yuv[yIndex++] = (byte) clamp(y);
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = (byte) clamp(v);
                    yuv[uvIndex++] = (byte) clamp(u);
                }
            }
        }
    }

    private static int clamp(int value) {
        return Math.max(16, Math.min(235, value));
    }
}
