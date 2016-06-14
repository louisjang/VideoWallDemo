package com.windriver.videowalldemo;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class MediaEncoder {
    private static final String TAG = "MediaEncoder";
    private static final boolean DUMP = false;
    private static final String DUMP_PATH = "/mnt/sdcard/dump.mp4";

    private final OutputStream mAccOut;
    private MediaCodec mEncoder;
    private int mWidth;
    private int mHeight;

    public MediaEncoder(OutputStream accOut) {
        mAccOut = accOut;
        try {
            mEncoder = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void setup(int width, int height) {
        mWidth = width;
        mHeight = height;

        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1250000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    public Surface getSurface() {
        return mEncoder.createInputSurface();
    }

    public void start() {
        mEncoder.start();
        mRunning = true;
        mEncodingThread.start();
    }

    public void stop() {
        mRunning = false;
        mEncoder.stop();
        if (mMuxer != null) {
            mMuxer.release();
            mMuxer.stop();
            mMuxer = null;
        }
    }

    private boolean mRunning;

    private MediaMuxer mMuxer;
    private int mTrackIndex;

    Thread mEncodingThread = new Thread(new Runnable() {
        @Override
        public void run() {
            if (DUMP) {
                try {
                    mMuxer = new MediaMuxer(DUMP_PATH,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mTrackIndex = -1;
            }

            boolean formatAcquired = false;
            MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
            while (mRunning) {
                //Log.v(TAG, "dequeue");
                int index = mEncoder.dequeueOutputBuffer(bufInfo, 100*1000);
                if (index >= 0) {
                    if (formatAcquired) {
                        ByteBuffer outputBuffer = mEncoder.getOutputBuffer(index);
                        byte[] outData = new byte[bufInfo.size];
                        outputBuffer.get(outData);
                        //Log.v(TAG, "encoded byte = " + Util.dump(outData, -1));
                        //Log.v(TAG, "writing....");
                        try {
                            mAccOut.write(((outData.length) >> 8) & 0xFF);
                            mAccOut.write(((outData.length)) & 0xFF);
                            mAccOut.write(outData);
                            mAccOut.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        if (mMuxer != null) {
                            mMuxer.writeSampleData(mTrackIndex, outputBuffer, bufInfo);
                        }
                    }
                    mEncoder.releaseOutputBuffer(index, true);
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formatAcquired = true;
                    MediaFormat newFormat = mEncoder.getOutputFormat();
                    Log.d(TAG, "encoder output format changed: " + newFormat);

                    if (DUMP) {
                        mTrackIndex = mMuxer.addTrack(newFormat);
                        mMuxer.start();
                    }
                }
            }
        }
    });

}
