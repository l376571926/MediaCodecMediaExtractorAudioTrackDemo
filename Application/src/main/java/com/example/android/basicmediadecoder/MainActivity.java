/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.basicmediadecoder;


import android.Manifest;
import android.animation.TimeAnimator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.example.android.common.media.AudioMediaCodecWrapper;
import com.example.android.common.media.MediaCodecWrapper;

import java.io.IOException;

/**
 * This activity uses a {@link android.view.TextureView} to render the frames of a video decoded using
 * {@link android.media.MediaCodec} API.
 */
public class MainActivity extends AppCompatActivity {

    private TextureView mPlaybackView;
    private TimeAnimator mTimeAnimator = new TimeAnimator();

    // A utility that wraps up the underlying input and output buffer processing operations
    // into an east to use API.
    private MediaCodecWrapper mCodecWrapper;
    private MediaExtractor mExtractor = new MediaExtractor();
    TextView mAttribView = null;
    private AudioMediaCodecWrapper mAudioCodecWrapper;
    private MediaExtractor mAudioMediaExtractor;
    private TimeAnimator mAudioTimeAnimator;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_main);
        mPlaybackView = (TextureView) findViewById(R.id.PlaybackView);
        mAttribView = (TextView) findViewById(R.id.AttribView);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mTimeAnimator != null && mTimeAnimator.isRunning()) {
            mTimeAnimator.end();
        }

        if (mCodecWrapper != null) {
            mCodecWrapper.stopAndRelease();
            mExtractor.release();
        }
    }

    public void play(View view) {
        mAttribView.setVisibility(View.VISIBLE);
        startPlayback();
        try {
            startAudioPlayback();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final String[] MP4_FILE_1 = {
            "dongfengpo1_stereo.mp4"//0东风破
            , "dongfengpo2_mono.mp4"//1东风破——单声道
            , "jiangnanye.mp4"//2江南夜

            , "xiaoyaotan.mp4"//3逍遥叹
            , "liuyuedeyu.mp4"//4六月的雨
            , "sanguolian.mp4"//5三国恋
    };
    private static String mMp4FilePath = Environment.getExternalStorageDirectory().getPath() + "/zmp4mv/" + MP4_FILE_1[4];

    public void startAudioPlayback() throws IOException {
        // Construct a URI that points to the video resource that we want to play
        Uri videoUri = Uri.parse("android.resource://"
                + getPackageName() + "/"
                + R.raw.vid_bigbuckbunny);

        mAudioMediaExtractor = new MediaExtractor();
        // BEGIN_INCLUDE(initialize_extractor)
//        mAudioMediaExtractor.setDataSource(this, videoUri, null);
        mAudioMediaExtractor.setDataSource(mMp4FilePath);
        int nTracks = mAudioMediaExtractor.getTrackCount();

        // Begin by unselecting all of the tracks in the extractor, so we won't see
        // any tracks that we haven't explicitly selected.
        for (int i = 0; i < nTracks; ++i) {
            mAudioMediaExtractor.unselectTrack(i);
        }

        // Find the first video track in the stream. In a real-world application
        // it's possible that the stream would contain multiple tracks, but this
        // sample assumes that we just want to play the first one.
        for (int i = 0; i < nTracks; ++i) {
            // Try to create a video codec for this track. This call will return null if the
            // track is not a video track, or not a recognized video format. Once it returns
            // a valid MediaCodecWrapper, we can break out of the loop.
            mAudioCodecWrapper = AudioMediaCodecWrapper.fromAudioFormat(mAudioMediaExtractor.getTrackFormat(i));
            if (mAudioCodecWrapper != null) {
                mAudioCodecWrapper.prepareAudioTrack(mAudioMediaExtractor.getTrackFormat(i));
                mAudioMediaExtractor.selectTrack(i);
                break;
            }
        }
        if (mAudioCodecWrapper == null) {
            return;
        }
        // END_INCLUDE(initialize_extractor)

        // By using a {@link TimeAnimator}, we can sync our media rendering commands with
        // the system display frame rendering. The animator ticks as the {@link Choreographer}
        // receives VSYNC events.
        mAudioTimeAnimator = new TimeAnimator();
        mAudioTimeAnimator.setTimeListener(new TimeAnimator.TimeListener() {
            private static final String TAG = "onTimeUpdate";

            @Override
            public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
                Log.e(TAG, "onTimeUpdate: " + totalTime + " " + deltaTime);

                int sampleFlags = mAudioMediaExtractor.getSampleFlags();
                boolean isEos = ((sampleFlags & MediaCodec
                        .BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                // BEGIN_INCLUDE(write_sample)
                if (!isEos) {
                    // Try to submit the sample to the codec and if successful advance the
                    // extractor to the next available sample to read.
                    boolean result = mAudioCodecWrapper.writeSample(mAudioMediaExtractor, false,
                            mAudioMediaExtractor.getSampleTime(), sampleFlags);

                    if (result) {
                        // Advancing the extractor is a blocking operation and it MUST be
                        // executed outside the main thread in real applications.
                        mAudioMediaExtractor.advance();
                    }
                }
                // END_INCLUDE(write_sample)

                // Examine the sample at the head of the queue to see if its ready to be
                // rendered and is not zero sized End-of-Stream record.
                MediaCodec.BufferInfo out_bufferInfo = new MediaCodec.BufferInfo();
                mAudioCodecWrapper.peekSample(out_bufferInfo);

                // BEGIN_INCLUDE(render_sample)
                if (out_bufferInfo.size <= 0 && isEos) {
                    mAudioTimeAnimator.end();
                    mAudioCodecWrapper.stopAndRelease();
                    mAudioMediaExtractor.release();
                } else if (out_bufferInfo.presentationTimeUs / 1000 < totalTime) {
                    // Pop the sample off the queue and send it to {@link Surface}
                    mAudioCodecWrapper.popSample(false);
                }
                // END_INCLUDE(render_sample)
            }
        });
        // We're all set. Kick off the animator to process buffers and render video frames as
        // they become available
        mAudioTimeAnimator.start();
    }


    public void startPlayback() {

        // Construct a URI that points to the video resource that we want to play
        Uri videoUri = Uri.parse("android.resource://"
                + getPackageName() + "/"
                + R.raw.vid_bigbuckbunny);

        try {

            // BEGIN_INCLUDE(initialize_extractor)
//            mExtractor.setDataSource(this, videoUri, null);
            mExtractor.setDataSource(mMp4FilePath);
            int nTracks = mExtractor.getTrackCount();

            // Begin by unselecting all of the tracks in the extractor, so we won't see
            // any tracks that we haven't explicitly selected.
            for (int i = 0; i < nTracks; ++i) {
                mExtractor.unselectTrack(i);
            }


            // Find the first video track in the stream. In a real-world application
            // it's possible that the stream would contain multiple tracks, but this
            // sample assumes that we just want to play the first one.
            for (int i = 0; i < nTracks; ++i) {
                // Try to create a video codec for this track. This call will return null if the
                // track is not a video track, or not a recognized video format. Once it returns
                // a valid MediaCodecWrapper, we can break out of the loop.
                mCodecWrapper = MediaCodecWrapper.fromVideoFormat(mExtractor.getTrackFormat(i),
                        new Surface(mPlaybackView.getSurfaceTexture()));
                if (mCodecWrapper != null) {
                    mExtractor.selectTrack(i);
                    break;
                }
            }
            // END_INCLUDE(initialize_extractor)


            // By using a {@link TimeAnimator}, we can sync our media rendering commands with
            // the system display frame rendering. The animator ticks as the {@link Choreographer}
            // receives VSYNC events.
            mTimeAnimator.setTimeListener(new TimeAnimator.TimeListener() {
                @Override
                public void onTimeUpdate(final TimeAnimator animation,
                                         final long totalTime,
                                         final long deltaTime) {

                    int sampleFlags = mExtractor.getSampleFlags();
                    boolean isEos = ((sampleFlags & MediaCodec
                            .BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    // BEGIN_INCLUDE(write_sample)
                    if (!isEos) {
                        // Try to submit the sample to the codec and if successful advance the
                        // extractor to the next available sample to read.
                        boolean result = mCodecWrapper.writeSample(mExtractor, false,
                                mExtractor.getSampleTime(), sampleFlags);

                        if (result) {
                            // Advancing the extractor is a blocking operation and it MUST be
                            // executed outside the main thread in real applications.
                            mExtractor.advance();
                        }
                    }
                    // END_INCLUDE(write_sample)

                    // Examine the sample at the head of the queue to see if its ready to be
                    // rendered and is not zero sized End-of-Stream record.
                    MediaCodec.BufferInfo out_bufferInfo = new MediaCodec.BufferInfo();
                    mCodecWrapper.peekSample(out_bufferInfo);

                    // BEGIN_INCLUDE(render_sample)
                    if (out_bufferInfo.size <= 0 && isEos) {
                        mTimeAnimator.end();
                        mCodecWrapper.stopAndRelease();
                        mExtractor.release();
                    } else if (out_bufferInfo.presentationTimeUs / 1000 < totalTime) {
                        // Pop the sample off the queue and send it to {@link Surface}
                        mCodecWrapper.popSample(true);
                    }
                    // END_INCLUDE(render_sample)

                }
            });

            // We're all set. Kick off the animator to process buffers and render video frames as
            // they become available
            mTimeAnimator.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
