package uz.kosmostar.vokall;

import static android.os.Process.THREAD_PRIORITY_URGENT_AUDIO;
import static android.os.Process.setThreadPriority;
import static uz.kosmostar.vokall.Constants.TAG;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;


public class AudioRecorder {
    /** Interface to send data back to the Activity */
    public interface AudioDataCallback {
        void onAudioData(byte[] data);
    }

    private final AudioDataCallback mCallback;
    private volatile boolean mAlive;
    private Thread mThread;
    private volatile boolean mMuted = false;

    public AudioRecorder(AudioDataCallback callback) {
        mCallback = callback;
    }

  /** @return True if actively recording. False otherwise. */
  public boolean isRecording() {
    return mAlive;
  }

  /** Starts recording audio. */
  public void start() {
    if (isRecording()) {
      Log.w(TAG, "Already running");
      return;
    }

    mAlive = true;
    mThread =
        new Thread() {
          @Override
          public void run() {
            setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO);

            Buffer buffer = new Buffer();
            @SuppressLint("MissingPermission") AudioRecord record =
                new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    buffer.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffer.size);

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
              Log.w(TAG, "Failed to start recording");
              mAlive = false;
              return;
            }

              int sessionId = record.getAudioSessionId();
              NoiseSuppressor ns = null;
              AcousticEchoCanceler aec = null;

              if (NoiseSuppressor.isAvailable()) {
                  ns = NoiseSuppressor.create(sessionId);
                  if (ns != null) ns.setEnabled(true);
                  Log.d(TAG, "NoiseSuppressor enabled");
              }

              // Optional: Also enable Echo Canceler if you experience feedback
              if (AcousticEchoCanceler.isAvailable()) {
                  aec = AcousticEchoCanceler.create(sessionId);
                  if (aec != null) aec.setEnabled(true);
              }


              record.startRecording();

                try {
                    while (isRecording()) {
                        int len = record.read(buffer.data, 0, buffer.size);
                        if (len > 0) {
                            if (mMuted) {
                                Arrays.fill(buffer.data, 0, len, (byte) 0);
                            }
                            // Copy the valid bytes to a new array to send via callback
                            byte[] dataToSend = Arrays.copyOf(buffer.data, len);
                            mCallback.onAudioData(dataToSend);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception with recording stream", e);
                } finally {
                    try {
                        record.stop();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Failed to stop AudioRecord", e);
                    }
                    if (ns != null) ns.release();
                    if (aec != null) aec.release();
                    record.release();
                }
            }
        };
        mThread.start();
    }

    public void setMuted(boolean muted) {
        this.mMuted = muted;
    }

    /** Stops recording audio. */
    public void stop() {
        mAlive = false;
        try {
            if (mThread != null) mThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while joining AudioRecorder thread", e);
            Thread.currentThread().interrupt();
        }
    }

    private static class Buffer extends AudioBuffer {
        @Override
        protected boolean validSize(int size) {
            return size != AudioRecord.ERROR && size != AudioRecord.ERROR_BAD_VALUE;
        }

        @Override
        protected int getMinBufferSize(int sampleRate) {
            return AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        }
    }
}