package uz.kosmostar.vokall;

import static android.os.Process.THREAD_PRIORITY_URGENT_AUDIO;
import static android.os.Process.setThreadPriority;
import static uz.kosmostar.vokall.Constants.TAG;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import java.util.concurrent.LinkedBlockingQueue;

import java.io.IOException;
import java.io.InputStream;

/**
 * A fire-once class. When created, you must pass a {@link InputStream}. Once {@link #start()} is
 * called, the input stream will be read from until either {@link #stop()} is called or the stream
 * ends.
 */
public class AudioPlayer {
    /**
     * Buffer limit. If we have more than 15 packets waiting,
     * we are lagging behind. Drop them to catch up.
     */
    private static final int MAX_BUFFER_SIZE = 15;

    private final LinkedBlockingQueue<byte[]> mQueue = new LinkedBlockingQueue<>();
    private volatile boolean mAlive;
    private Thread mThread;

    public AudioPlayer() {
    }

    /** Call this when BYTES payload is received */
    public void addAudioData(byte[] data) {
        if (!mAlive) return;

        // Anti-Lag Logic:
        // If the queue is too full, clear it. This causes a tiny skip in audio
        // but ensures we are playing "live" audio, not audio from 1 minute ago.
        if (mQueue.size() > MAX_BUFFER_SIZE) {
            mQueue.clear();
            Log.w(TAG, "Player buffer full - Dropping audio to catch up");
        }
        mQueue.add(data);
    }

    /**
     * @return True if currently playing.
     */
    public boolean isPlaying() {
        return mAlive;
    }

    /**
     * Starts playing the stream.
     */
    public void start() {
        mAlive = true;
        mThread =
                new Thread() {
                    @Override
                    public void run() {
                        setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO);

                        Buffer buffer = new Buffer();
                        AudioTrack audioTrack =
                                new AudioTrack(
                                        AudioManager.STREAM_VOICE_CALL,
                                        buffer.sampleRate,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        buffer.size,
                                        AudioTrack.MODE_STREAM);
                        audioTrack.play();

                try {
                    while (isPlaying()) {
                        // Take data from the queue, blocking until data arrives
                        byte[] data = mQueue.take();
                        audioTrack.write(data, 0, data.length);
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "AudioPlayer interrupted", e);
                } finally {
                    audioTrack.stop();
                    audioTrack.release();
                    onFinish();
                }
            }
        };
        mThread.start();
    }

    public void stop() {
        mAlive = false;
        // Inject a dummy byte to wake up the queue.take() if it's waiting
        mQueue.offer(new byte[0]);
        try {
            if (mThread != null) mThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while joining AudioPlayer thread", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The stream has now ended.
     */
    protected void onFinish() {
    }

    private static class Buffer extends AudioBuffer {
        @Override
        protected boolean validSize(int size) {
            return size != AudioTrack.ERROR && size != AudioTrack.ERROR_BAD_VALUE;
        }

        @Override
        protected int getMinBufferSize(int sampleRate) {
            return AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        }
    }
}