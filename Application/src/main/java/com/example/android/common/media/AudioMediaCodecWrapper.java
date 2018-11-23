package com.example.android.common.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

public class AudioMediaCodecWrapper {
    private MediaCodec mDecoder;
    private final ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private MediaCodec.BufferInfo[] mOutputBufferInfo;
    private final Queue<Integer> mAvailableInputBuffers;
    private final Queue<Integer> mAvailableOutputBuffers;
    private AudioTrack mAudioTrack;

    public AudioMediaCodecWrapper(MediaCodec codec) {
        this.mDecoder = codec;
        codec.start();

        mInputBuffers = codec.getInputBuffers();
        mOutputBuffers = codec.getOutputBuffers();
        mOutputBufferInfo = new MediaCodec.BufferInfo[mOutputBuffers.length];
        mAvailableInputBuffers = new ArrayDeque<>(mOutputBuffers.length);
        mAvailableOutputBuffers = new ArrayDeque<>(mInputBuffers.length);
    }

    public void prepareAudioTrack(MediaFormat mediaFormat) {
        int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        int streamType = AudioManager.STREAM_MUSIC;

        int sampleRateInHz = sampleRate * channelCount;//AudioFormat#SAMPLE_RATE_UNSPECIFIED

        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;//AudioFormat#CHANNEL_OUT_MONO
        if (channelCount >= 2) {
            channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        }

        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int bufferSizeInBytes = 0;
        int mode = AudioTrack.MODE_STREAM;
        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRateInHz
                , channelConfig
                , audioFormat
        );
        bufferSizeInBytes = minBufferSize;
        mAudioTrack = new AudioTrack(
                streamType
                , sampleRateInHz
                , channelConfig
                , audioFormat
                , bufferSizeInBytes
                , mode
        );
        mAudioTrack.play();
        System.out.println();
    }

    public static AudioMediaCodecWrapper fromAudioFormat(MediaFormat trackFormat) throws IOException {
        AudioMediaCodecWrapper result = null;
        MediaCodec audioCodec = null;

        // BEGIN_INCLUDE(create_codec)
        final String mimeType = trackFormat.getString(MediaFormat.KEY_MIME);

        // Check to see if this is actually a video mime type. If it is, then create
        // a codec that can decode this mime type.
        if (mimeType.contains("audio/")) {
            audioCodec = MediaCodec.createDecoderByType(mimeType);
            audioCodec.configure(trackFormat, null, null, 0);

        }

        // If codec creation was successful, then create a wrapper object around the
        // newly created codec.
        if (audioCodec != null) {
            result = new AudioMediaCodecWrapper(audioCodec);
        }
        // END_INCLUDE(create_codec)

        return result;
    }

    private static MediaCodec.CryptoInfo sCryptoInfo = new MediaCodec.CryptoInfo();

    public boolean writeSample(final MediaExtractor extractor,
                               final boolean isSecure,
                               final long presentationTimeUs,
                               int flags) {
        boolean result = false;

        if (!mAvailableInputBuffers.isEmpty()) {
            int index = mAvailableInputBuffers.remove();
            ByteBuffer buffer = mInputBuffers[index];

            // reads the sample from the file using extractor into the buffer
            int size = extractor.readSampleData(buffer, 0);
            if (size <= 0) {
                flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            }

            // Submit the buffer to the codec for decoding. The presentationTimeUs
            // indicates the position (play time) for the current sample.
            if (!isSecure) {
                mDecoder.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
            } else {
                extractor.getSampleCryptoInfo(sCryptoInfo);
                mDecoder.queueSecureInputBuffer(index, 0, sCryptoInfo, presentationTimeUs, flags);
            }

            result = true;
        }
        return result;
    }

    public boolean peekSample(MediaCodec.BufferInfo out_bufferInfo) {
        // dequeue available buffers and synchronize our data structures with the codec.
        update();
        boolean result = false;
        if (!mAvailableOutputBuffers.isEmpty()) {
            int index = mAvailableOutputBuffers.peek();
            MediaCodec.BufferInfo info = mOutputBufferInfo[index];
            // metadata of the sample
            out_bufferInfo.set(
                    info.offset,
                    info.size,
                    info.presentationTimeUs,
                    info.flags);
            result = true;
        }
        return result;
    }

    private void update() {
        // BEGIN_INCLUDE(update_codec_state)
        int index;

        // Get valid input buffers from the codec to fill later in the same order they were
        // made available by the codec.
        while ((index = mDecoder.dequeueInputBuffer(0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            mAvailableInputBuffers.add(index);
        }


        // Likewise with output buffers. If the output buffers have changed, start using the
        // new set of output buffers. If the output format has changed, notify listeners.
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while ((index = mDecoder.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            switch (index) {
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    mOutputBuffers = mDecoder.getOutputBuffers();
                    mOutputBufferInfo = new MediaCodec.BufferInfo[mOutputBuffers.length];
                    mAvailableOutputBuffers.clear();
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
//                    if (mOutputFormatChangedListener != null) {
//                        mHandler.post(new Runnable() {
//                            @Override
//                            public void run() {
//                                mOutputFormatChangedListener
//                                        .outputFormatChanged(MediaCodecWrapper.this,
//                                                mDecoder.getOutputFormat());
//
//                            }
//                        });
//                    }
                    break;
                default:
                    // Making sure the index is valid before adding to output buffers. We've already
                    // handled INFO_TRY_AGAIN_LATER, INFO_OUTPUT_FORMAT_CHANGED &
                    // INFO_OUTPUT_BUFFERS_CHANGED i.e all the other possible return codes but
                    // asserting index value anyways for future-proofing the code.
                    if (index >= 0) {
                        mOutputBufferInfo[index] = info;
                        mAvailableOutputBuffers.add(index);
                    } else {
                        throw new IllegalStateException("Unknown status from dequeueOutputBuffer");
                    }
                    break;
            }

        }
        // END_INCLUDE(update_codec_state)

    }

    public void stopAndRelease() {
        mDecoder.stop();
        mDecoder.release();
        mDecoder = null;
//        mHandler = null;
    }

    public void popSample(boolean render) {
        // dequeue available buffers and synchronize our data structures with the codec.
        update();
        if (!mAvailableOutputBuffers.isEmpty()) {
            int index = mAvailableOutputBuffers.remove();

            ByteBuffer byteBuffer = mOutputBuffers[index];
            MediaCodec.BufferInfo bufferInfo = mOutputBufferInfo[index];
            byte[] bytes = new byte[bufferInfo.size];
            byteBuffer.position(0);
            byteBuffer.get(bytes);
            byteBuffer.clear();
            mAudioTrack.write(bytes, 0, bufferInfo.size);

            // releases the buffer back to the codec
            mDecoder.releaseOutputBuffer(index, render);
        }
    }
}
