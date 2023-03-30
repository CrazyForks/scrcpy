package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public final class AudioCapture {

    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    public static final int CHANNELS = 2;
    public static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int BYTES_PER_SAMPLE = 2;

    private AudioRecord recorder;

    private final AudioTimestamp timestamp = new AudioTimestamp();
    private long previousPts = 0;
    private long nextPts = 0;

    public static int millisToBytes(int millis) {
        return SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * millis / 1000;
    }

    private static AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(FORMAT);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(CHANNEL_CONFIG);
        return builder.build();
    }

    private static Method setBuilderContext;

    @TargetApi(23)
    private static void setBuilderContext(AudioRecord.Builder builder, Context context) {
        try {
            if (setBuilderContext == null) {
                setBuilderContext = AudioRecord.Builder.class.getMethod("setContext", Context.class);
            }
            setBuilderContext.invoke(builder, context);
        } catch (Exception e) {
            Ln.e("Could not call AudioRecord.Builder.setContext() method");
            //throw new RuntimeException(e);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @SuppressLint({"WrongConstant", "MissingPermission"})
    private static AudioRecord createAudioRecord() {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= 31) {
            // On older APIs, Workarounds.fillAppInfo() must be called beforehand
            setBuilderContext(builder, FakeContext.get());
        }
        builder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
        builder.setAudioFormat(createAudioFormat());
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, FORMAT);
        // This buffer size does not impact latency
        builder.setBufferSizeInBytes(8 * minBufferSize);
        return builder.build();
    }

    private static void startWorkaroundAndroid11() {
        // Android 11 requires Apps to be at foreground to record audio.
        // Normally, each App has its own user ID, so Android checks whether the requesting App has the user ID that's at the foreground.
        // But scrcpy server is NOT an App, it's a Java application started from Android shell, so it has the same user ID (2000) with Android
        // shell ("com.android.shell").
        // If there is an Activity from Android shell running at foreground, then the permission system will believe scrcpy is also in the
        // foreground.
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(FakeContext.PACKAGE_NAME, "com.android.shell.HeapDumpActivity"));
        ServiceManager.getActivityManager().startActivityAsUserWithFeature(intent);
    }

    private static void stopWorkaroundAndroid11() {
        ServiceManager.getActivityManager().forceStopPackage(FakeContext.PACKAGE_NAME);
    }

    private void tryStartRecording(int attempts, int delayMs) throws AudioCaptureForegroundException {
        while (attempts-- > 0) {
            // Wait for activity to start
            SystemClock.sleep(delayMs);
            try {
                startRecording();
                return; // it worked
            } catch (UnsupportedOperationException e) {
                if (attempts == 0) {
                    Ln.e("Failed to start audio capture");
                    Ln.e("On Android 11, audio capture must be started in the foreground, make sure that the device is unlocked when starting " +
                            "scrcpy.");
                    throw new AudioCaptureForegroundException();
                } else {
                    Ln.d("Failed to start audio capture, retrying...");
                }
            }
        }
    }

    private void startRecording() {
        recorder = createAudioRecord();
        recorder.startRecording();
    }

    public void start() throws AudioCaptureForegroundException {
        if (Build.VERSION.SDK_INT == 30) {
            startWorkaroundAndroid11();
            try {
                tryStartRecording(3, 100);
            } finally {
                stopWorkaroundAndroid11();
            }
        } else {
            startRecording();
        }
    }

    public void stop() {
        if (recorder != null) {
            // Will call .stop() if necessary, without throwing an IllegalStateException
            recorder.release();
        }
    }

    private static Method getTimestampMethod;

    private static int getRecorderTimestamp(AudioRecord recorder, AudioTimestamp timestamp) {
        try {
            if (getTimestampMethod == null) {
                getTimestampMethod = AudioRecord.class.getMethod("getTimestamp", AudioTimestamp.class, int.class);
            }
            return (int) getTimestampMethod.invoke(recorder, timestamp, 0);
        } catch (Exception e) {
            Ln.e("Could not call AudioRecord.getTimestamp() method");
            return AudioRecord.ERROR;
        }
    }

    @TargetApi(24)
    public int read(ByteBuffer directBuffer, int size, MediaCodec.BufferInfo outBufferInfo) {
        int r = recorder.read(directBuffer, size);
        if (r <= 0) {
            return r;
        }

        long pts;

        int ret = getRecorderTimestamp(recorder, timestamp);
        if (ret == AudioRecord.SUCCESS) {
            pts = timestamp.nanoTime / 1000;
        } else {
            if (nextPts == 0) {
                Ln.w("Could not get any audio timestamp");
            }
            // compute from previous timestamp and packet size
            pts = nextPts;
        }

        long durationUs = r * 1000000 / (CHANNELS * BYTES_PER_SAMPLE * SAMPLE_RATE);
        nextPts = pts + durationUs;

        if (previousPts != 0 && pts < previousPts) {
            // Audio PTS may come from two sources:
            //  - recorder.getTimestamp() if the call works;
            //  - an estimation from the previous PTS and the packet size as a fallback.
            //
            // Therefore, the property that PTS are monotonically increasing is no guaranteed in corner cases, so enforce it.
            pts = previousPts + 1;
        }
        previousPts = pts;

        outBufferInfo.set(0, r, pts, 0);
        return r;
    }
}