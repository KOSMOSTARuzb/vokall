package uz.kosmostar.vokall;

import static android.os.Process.THREAD_PRIORITY_AUDIO;
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

/**
 * When created, you must pass a {@link ParcelFileDescriptor}. Once {@link #start()} is called, the
 * file descriptor will be written to until {@link #stop()} is called.
 */
public class AudioRecorder {
  /** The stream to write to. */
  private final OutputStream mOutputStream;

  /**
   * If true, the background thread will continue to loop and record audio. Once false, the thread
   * will shut down.
   */
  private volatile boolean mAlive;

  /** The background thread recording audio for us. */
  private Thread mThread;

    private volatile boolean mMuted = false;

  /**
   * A simple audio recorder.
   *
   * @param file The output stream of the recording.
   */
  public AudioRecorder(ParcelFileDescriptor file) {
    mOutputStream = new ParcelFileDescriptor.AutoCloseOutputStream(file);
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
            setThreadPriority(THREAD_PRIORITY_AUDIO);

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

            // While we're running, we'll read the bytes from the AudioRecord and write them
            // to our output stream.
            try {
              while (isRecording()) {
                int len = record.read(buffer.data, 0, buffer.size);
                if (len >= 0 && len <= buffer.size) {
                    if (mMuted) {
                        java.util.Arrays.fill(buffer.data, 0, len, (byte) 0);
                    }
                  mOutputStream.write(buffer.data, 0, len);
                  mOutputStream.flush();
                } else {
                  Log.w(TAG, "Unexpected length returned: " + len);
                }
              }
            } catch (IOException e) {
              Log.e(TAG, "Exception with recording stream", e);
            } finally {
              stopInternal();
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

  private void stopInternal() {
    mAlive = false;
    try {
      mOutputStream.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to close output stream", e);
    }
  }

  /** Stops recording audio. */
  public void stop() {
    stopInternal();
    try {
      mThread.join();
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
