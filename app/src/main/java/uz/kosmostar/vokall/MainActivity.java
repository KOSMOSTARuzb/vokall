package uz.kosmostar.vokall;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.transition.AutoTransition;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.material.card.MaterialCardView;
import uz.kosmostar.vokall.BuildConfig;

import java.io.IOException;
import java.util.Random;

/**
 * Our WalkieTalkie Activity. This Activity has 3 {@link State}s.
 *
 * <p>{@link State#UNKNOWN}: We cannot do anything while we're in this state. The app is likely in
 * the background.
 *
 * <p>{@link State#SEARCHING}: Our default state (after we've connected). We constantly listen for a
 * device to advertise near us, while simultaneously advertising ourselves.
 *
 * <p>{@link State#CONNECTED}: We've connected to another device and can now talk to them by holding
 * down the volume keys and speaking into the phone. Advertising and discovery have both stopped.
 */
public class MainActivity extends ConnectionsActivity {
    /** If true, debug logs are shown on the device. */
    private boolean DEBUG = BuildConfig.DEBUG;

    /**
     * The connection strategy we'll use for Nearby Connections. In this case, we've decided on
     * P2P_STAR, which is a combination of Bluetooth Classic and WiFi Hotspots.
     */
    private static final Strategy STRATEGY = Strategy.P2P_STAR;

    /** Length of state change animations. */
    private static final long ANIMATION_DURATION = 600;

    /**
     * A set of background colors. We'll hash the authentication token we get from connecting to a
     * device to pick a color randomly from this list. Devices with the same background color are
     * talking to each other securely (with 1/COLORS.length chance of collision with another pair of
     * devices).
     */
    @ColorInt
    private static final int[] COLORS =
            new int[] {
                    R.color.color1,
                    R.color.color2,
                    R.color.color3,
                    R.color.color4,
                    R.color.color5,
                    R.color.color6,
                    R.color.color7,
            };

    /**
     * This service id lets us find other nearby devices that are interested in the same thing. Our
     * sample does exactly one thing, so we hardcode the ID.
     */
    private static final String SERVICE_ID =
            "uz.kosmostar.vokall.automatic.SERVICE_ID";

    /**
     * The state of the app. As the app changes states, the UI will update and advertising/discovery
     * will start/stop.
     */
    private State mState = State.UNKNOWN;

    /** A random UID used as this device's endpoint name. */
    private String mName;

    /**
     * The background color of the 'CONNECTED' state. This is randomly chosen from the {@link #COLORS}
     * list, based off the authentication token.
     */
    @ColorInt private int mConnectedColor = COLORS[0];

    /** Displays the previous state during animation transitions. */
    private TextView mPreviousStateView;

    /** Displays the current state. */
    private TextView mCurrentStateView;

    /** An animator that controls the animation from previous state to current state. */
    @Nullable private Animator mCurrentAnimator;

    /** A running log of debug messages. Only visible when DEBUG=true. */
    private com.google.android.material.card.MaterialCardView mDebugCardView;
    private TextView mDebugLogView;


    private com.google.android.material.card.MaterialCardView mStatusCard;
    private android.widget.ImageView mStatusIcon;

    // Inside MainActivity.java
    private boolean mIsMuted = false;
    private boolean mIsSpeakerPhoneOn = true; // Default to speaker


    /** For recording audio as the user speaks. */
    @Nullable private AudioRecorder mRecorder;

    /** For playing audio from other users nearby. */
    @Nullable private AudioPlayer mAudioPlayer;

    /** The phone's original media volume. */
    private int mOriginalVolume;
    private int mOriginalMode = AudioManager.MODE_NORMAL;
    private int mStatusClickCount = 0;
    private long mLastStatusClickTime = 0;

    private final androidx.activity.OnBackPressedCallback mBackCallback = new androidx.activity.OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (getState() == State.CONNECTED) {
                setState(State.SEARCHING);
            } else {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.state_unknown));
        }

        mPreviousStateView = (TextView) findViewById(R.id.previous_state);
        mCurrentStateView = (TextView) findViewById(R.id.current_state);

        mStatusCard = findViewById(R.id.status_card);
        mStatusIcon = findViewById(R.id.status_icon);

        mDebugLogView = (TextView) findViewById(R.id.debug_log);
        mDebugCardView = findViewById(R.id.debug_card);
        mDebugCardView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
        mDebugLogView.setMovementMethod(new ScrollingMovementMethod());

        mName = generateRandomName();

        ((TextView) findViewById(R.id.name)).setText(mName);
        Button muteBtn = findViewById(R.id.btn_mute);
        muteBtn.setOnClickListener(v -> onMuteClicked(muteBtn));

        Button audioBtn = findViewById(R.id.btn_audio_device);
        audioBtn.setOnClickListener(v -> onToggleSpeakerClicked(audioBtn));
        mStatusCard.setOnClickListener(v -> onStatusClick(mDebugCardView));

        getOnBackPressedDispatcher().addCallback(this, mBackCallback);
    }

    public void onStatusClick(View view) {
        long currentTime = System.currentTimeMillis();

        if (currentTime - mLastStatusClickTime < 1000) {
            mStatusClickCount++;
        } else {
            mStatusClickCount = 1;
        }

        mLastStatusClickTime = currentTime;

        if (mStatusClickCount >= 10) {
            mStatusClickCount = 0;
            DEBUG = !DEBUG;
            setControlBarVisible(mDebugCardView, DEBUG);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(!hasPermissions(this, getRequiredPermissions()))return;

        // Set the media volume to max.
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (mState == State.UNKNOWN) {
            mOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            mOriginalMode = audioManager.getMode();
        }
        audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL, audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);

        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        audioManager.setSpeakerphoneOn(mIsSpeakerPhoneOn);

        setState(State.SEARCHING);
    }

    @Override
    protected void onStop() {
        // Restore the original volume.
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, mOriginalVolume, 0);
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
        audioManager.setMode(mOriginalMode);

        // Stop all audio-related threads
        if (isRecording()) {
            stopRecording();
        }
        if (isPlaying()) {
            stopPlaying();
        }

        // After our Activity stops, we disconnect from Nearby Connections.
        setState(State.UNKNOWN);

        if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.cancel();
        }

        super.onStop();
    }

    @Override
    protected void onEndpointDiscovered(Endpoint endpoint) {
        // We found an advertiser!
        stopDiscovering();
        connectToEndpoint(endpoint);
    }

    @Override
    protected void onConnectionInitiated(Endpoint endpoint, ConnectionInfo connectionInfo) {
        // A connection to another device has been initiated! We'll use the auth token, which is the
        // same on both devices, to pick a color to use when we're connected. This way, users can
        // visually see which device they connected with.
        mConnectedColor = COLORS[connectionInfo.getAuthenticationToken().hashCode() % COLORS.length];

        // We accept the connection immediately.
        acceptConnection(endpoint);
    }

    @Override
    protected void onEndpointConnected(Endpoint endpoint) {
        Toast.makeText(
                        this, getString(R.string.toast_connected, endpoint.getName()), Toast.LENGTH_SHORT)
                .show();
        setState(State.CONNECTED);
    }

    @Override
    protected void onEndpointDisconnected(Endpoint endpoint) {
        Toast.makeText(
                        this, getString(R.string.toast_disconnected, endpoint.getName()), Toast.LENGTH_SHORT)
                .show();
        stopRecording();
        setState(State.SEARCHING);
    }

    @Override
    protected void onConnectionFailed(Endpoint endpoint) {
        // Let's try someone else.
        if (getState() == State.SEARCHING) {
            startDiscovering();
        }
    }

    /**
     * The state has changed. I wonder what we'll be doing now.
     *
     * @param state The new state.
     */
    private void setState(State state) {
        if (mState == state) {
            logW("State set to " + state + " but already in that state");
            return;
        }

        logD("State set to " + state);
        State oldState = mState;
        mState = state;
        onStateChanged(oldState, state);
    }

    /** @return The current state. */
    private State getState() {
        return mState;
    }

    /**
     * State has changed.
     *
     * @param oldState The previous state we were in. Clean up anything related to this state.
     * @param newState The new state we're now in. Prepare the UI for this state.
     */
    private void onStateChanged(State oldState, State newState) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.cancel();
        }
        mBackCallback.setEnabled(true);

        MaterialCardView controlBar = findViewById(R.id.control_bar);

        // Update Nearby Connections to the new state.
        switch (newState) {
            case SEARCHING:
                audioManager.setMode(AudioManager.MODE_NORMAL);
                setControlBarVisible(controlBar, false);
                disconnectFromAllEndpoints();
                startDiscovering();
                startAdvertising();
                break;
            case CONNECTED:
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                setControlBarVisible(controlBar, true);
                stopDiscovering();
                stopAdvertising();
                startRecording();
                break;
            case UNKNOWN:
                audioManager.setMode(mOriginalMode);
                setControlBarVisible(controlBar, false);
                stopAllEndpoints();
                stopRecording();
                break;
            default:
                // no-op
                break;
        }

        // Update the UI.
        switch (oldState) {
            case UNKNOWN:
                // Unknown is our initial state. Whatever state we move to,
                // we're transitioning forwards.
                transitionForward(oldState, newState);
                break;
            case SEARCHING:
                switch (newState) {
                    case UNKNOWN:
                        transitionBackward(oldState, newState);
                        break;
                    case CONNECTED:
                        transitionForward(oldState, newState);
                        break;
                    default:
                        // no-op
                        break;
                }
                break;
            case CONNECTED:
                // Connected is our final state. Whatever new state we move to,
                // we're transitioning backwards.
                transitionBackward(oldState, newState);
                break;
        }
    }

    /** Transitions from the old state to the new state with an animation implying moving forward. */
    @UiThread
    private void transitionForward(State oldState, final State newState) {
        mPreviousStateView.setVisibility(View.VISIBLE);
        mCurrentStateView.setVisibility(View.VISIBLE);

        updateTextView(mPreviousStateView, oldState);
        updateTextView(mCurrentStateView, newState);

        if (ViewCompat.isLaidOut(mCurrentStateView)) {
            mCurrentAnimator = createAnimator(false /* reverse */);
            mCurrentAnimator.addListener(
                    new AnimatorListener() {
                        @Override
                        public void onAnimationEnd(Animator animator) {
                            updateTextView(mCurrentStateView, newState);
                        }
                    });
            mCurrentAnimator.start();
        }
    }

    /** Transitions from the old state to the new state with an animation implying moving backward. */
    @UiThread
    private void transitionBackward(State oldState, final State newState) {
        mPreviousStateView.setVisibility(View.VISIBLE);
        mCurrentStateView.setVisibility(View.VISIBLE);

        updateTextView(mCurrentStateView, oldState);
        updateTextView(mPreviousStateView, newState);

        if (ViewCompat.isLaidOut(mCurrentStateView)) {
            mCurrentAnimator = createAnimator(true /* reverse */);
            mCurrentAnimator.addListener(
                    new AnimatorListener() {
                        @Override
                        public void onAnimationEnd(Animator animator) {
                            updateTextView(mCurrentStateView, newState);
                        }
                    });
            mCurrentAnimator.start();
        }
    }

    private void setControlBarVisible(View view, boolean visible) {
        ViewGroup parent = (ViewGroup) view.getParent();

        TransitionSet set = new TransitionSet()
                .addTransition(new Fade())
                .addTransition(new ChangeBounds())
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .setDuration(400);

        TransitionManager.beginDelayedTransition(parent, set);
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private Animator createAnimator(boolean reverse) {
        Animator animator;
        if (Build.VERSION.SDK_INT >= 21) {
            int cx = mCurrentStateView.getMeasuredWidth() / 2;
            int cy = mCurrentStateView.getMeasuredHeight() / 2;
            int initialRadius = 0;
            int finalRadius = Math.max(mCurrentStateView.getWidth(), mCurrentStateView.getHeight());
            if (reverse) {
                int temp = initialRadius;
                initialRadius = finalRadius;
                finalRadius = temp;
            }
            animator =
                    ViewAnimationUtils.createCircularReveal(
                            mCurrentStateView, cx, cy, initialRadius, finalRadius);
        } else {
            float initialAlpha = 0f;
            float finalAlpha = 1f;
            if (reverse) {
                float temp = initialAlpha;
                initialAlpha = finalAlpha;
                finalAlpha = temp;
            }
            mCurrentStateView.setAlpha(initialAlpha);
            animator = ObjectAnimator.ofFloat(mCurrentStateView, "alpha", finalAlpha);
        }
        animator.addListener(
                new AnimatorListener() {
                    @Override
                    public void onAnimationCancel(Animator animator) {
                        mPreviousStateView.setVisibility(View.GONE);
                        mCurrentStateView.setAlpha(1);
                    }

                    @Override
                    public void onAnimationEnd(Animator animator) {
                        mPreviousStateView.setVisibility(View.GONE);
                        mCurrentStateView.setAlpha(1);
                    }
                });
        animator.setDuration(ANIMATION_DURATION);
        return animator;
    }

    /** Updates the {@link TextView} with the correct color/text for the given {@link State}. */
    @UiThread
    private void updateTextView(TextView textView, State state) {
        // Note: 'textView' argument is mostly kept for compatibility with your existing transition logic
        // but we will primarily update mStatusCard and mStatusIcon

        int color;
        int iconRes;
        String statusText;

        switch (state) {
            case SEARCHING:
                color = ContextCompat.getColor(this, R.color.state_searching);
                iconRes = R.drawable.wifi_find_24px;
                statusText = getString(R.string.status_searching);
                break;
            case CONNECTED:
                color = mConnectedColor;
                iconRes = R.drawable.wifi_calling_bar_3_24px;
                statusText = getString(R.string.status_connected);
                break;
            default:
                color = ContextCompat.getColor(this, R.color.state_unknown);
                iconRes = R.drawable.android_wifi_3_bar_question_24px;
                statusText = getString(R.string.status_unknown);
                break;
        }

        mStatusCard.setCardBackgroundColor(color);
        mStatusIcon.setImageResource(iconRes);
        mCurrentStateView.setText(statusText);

        // Set status bar color to match card for immersive feel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(color);
        }
    }

    /** {@see ConnectionsActivity#onReceive(Endpoint, Payload)} */
    @Override
    protected void onReceive(Endpoint endpoint, Payload payload) {
        if (payload.getType() == Payload.Type.STREAM) {
            if (mAudioPlayer != null) {
                mAudioPlayer.stop();
                mAudioPlayer = null;
            }

            AudioPlayer player =
                    new AudioPlayer(payload.asStream().asInputStream()) {
                        @WorkerThread
                        @Override
                        protected void onFinish() {
                            runOnUiThread(
                                    new Runnable() {
                                        @UiThread
                                        @Override
                                        public void run() {
                                            mAudioPlayer = null;
                                        }
                                    });
                        }
                    };
            mAudioPlayer = player;
            player.start();
        }
    }

    /** Stops all currently streaming audio tracks. */
    private void stopPlaying() {
        logV("stopPlaying()");
        if (mAudioPlayer != null) {
            mAudioPlayer.stop();
            mAudioPlayer = null;
        }
    }

    /** @return True if currently playing. */
    private boolean isPlaying() {
        return mAudioPlayer != null;
    }

    /** Toggles the Mute state */
    public void onMuteClicked(View view) {
        com.google.android.material.button.MaterialButton btn = (com.google.android.material.button.MaterialButton) view;
        mIsMuted = !mIsMuted;
        btn.setText(mIsMuted ? "Unmute" : "Mute");
        btn.setIconResource(mIsMuted ? R.drawable.mic_off_24px : R.drawable.mic_24px);
        if (mRecorder != null) mRecorder.setMuted(mIsMuted);
    }

    /** Toggles between Speaker and Earpiece */
    public void onToggleSpeakerClicked(View view) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mIsSpeakerPhoneOn = !mIsSpeakerPhoneOn;

        com.google.android.material.button.MaterialButton btn = (com.google.android.material.button.MaterialButton) view;

        if (mIsSpeakerPhoneOn) {
            audioManager.setSpeakerphoneOn(true);
            btn.setText("Speaker");
            btn.setIconResource(R.drawable.volume_up_24px);
        } else {
            audioManager.setSpeakerphoneOn(false);
            btn.setText("Earpiece");
            btn.setIconResource(R.drawable.phone_in_talk_24px);
        }
    }

    /** Starts recording sound from the microphone and streaming it to all connected devices. */
    private void startRecording() {
        logV("startRecording()");
        try {
            ParcelFileDescriptor[] payloadPipe = ParcelFileDescriptor.createPipe();

            // Send the first half of the payload (the read side) to Nearby Connections.
            send(Payload.fromStream(payloadPipe[0]));

            // Use the second half of the payload (the write side) in AudioRecorder.
            mRecorder = new AudioRecorder(payloadPipe[1]);
            mRecorder.setMuted(mIsMuted);
            mRecorder.start();
        } catch (IOException e) {
            logE("startRecording() failed", e);
        }
    }

    /** Stops streaming sound from the microphone. */
    private void stopRecording() {
        logV("stopRecording()");
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder = null;
        }
    }

    /** @return True if currently streaming from the microphone. */
    private boolean isRecording() {
        return mRecorder != null && mRecorder.isRecording();
    }

    /** {@see ConnectionsActivity#getRequiredPermissions()} */
    @Override
    protected String[] getRequiredPermissions() {
        return join(
                super.getRequiredPermissions(),
                Manifest.permission.RECORD_AUDIO);
    }

    /** Joins 2 arrays together. */
    private static String[] join(String[] a, String... b) {
        String[] join = new String[a.length + b.length];
        System.arraycopy(a, 0, join, 0, a.length);
        System.arraycopy(b, 0, join, a.length, b.length);
        return join;
    }

    /**
     * Queries the phone's contacts for their own profile, and returns their name. Used when
     * connecting to another device.
     */
    @Override
    protected String getName() {
        return mName;
    }

    /** {@see ConnectionsActivity#getServiceId()} */
    @Override
    public String getServiceId() {
        return SERVICE_ID;
    }

    /** {@see ConnectionsActivity#getStrategy()} */
    @Override
    public Strategy getStrategy() {
        return STRATEGY;
    }

    @Override
    protected void logV(String msg) {
        super.logV(msg);
        appendToLogs(toColor(msg, getResources().getColor(R.color.log_verbose)));
    }

    @Override
    protected void logD(String msg) {
        super.logD(msg);
        appendToLogs(toColor(msg, getResources().getColor(R.color.log_debug)));
    }

    @Override
    protected void logW(String msg) {
        super.logW(msg);
        appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
    }

    @Override
    protected void logW(String msg, Throwable e) {
        super.logW(msg, e);
        appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
    }

    @Override
    protected void logE(String msg, Throwable e) {
        super.logE(msg, e);
        appendToLogs(toColor(msg, getResources().getColor(R.color.log_error)));
    }

    private void appendToLogs(CharSequence msg) {
        if (!DEBUG) return;

        // 1. Performance check: Only trim when the log gets genuinely long.
        // 500 characters is too small (it will trigger every 2-3 lines).
        // Let's use 2000 characters as a threshold.
        if (mDebugLogView.length() > 2000) {
            // Trim the top: keep the last 1000 chars to avoid constant trimming
            CharSequence currentText = mDebugLogView.getText();
            mDebugLogView.setText(currentText.subSequence(currentText.length() - 1000, currentText.length()));
        }

        // 2. Prepare the new line
        CharSequence time = DateFormat.format("mm:ss", System.currentTimeMillis());
        // We use TextUtils.concat to keep the colors of 'msg'
        CharSequence newLine = TextUtils.concat("\n", time, ": ", msg);

        // 3. IMPORTANT: Use append(), not setText().
        // This is much lighter on the CPU.
        mDebugLogView.append(newLine);
    }

    private static CharSequence toColor(String msg, int color) {
        SpannableString spannable = new SpannableString(msg);
        spannable.setSpan(new ForegroundColorSpan(color), 0, msg.length(), 0);
        return spannable;
    }

    private static String generateRandomName() {
        String name = "";
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            name += random.nextInt(10);
        }
        return name;
    }

    /**
     * Provides an implementation of Animator.AnimatorListener so that we only have to override the
     * method(s) we're interested in.
     */
    private abstract static class AnimatorListener implements Animator.AnimatorListener {
        @Override
        public void onAnimationStart(Animator animator) {}

        @Override
        public void onAnimationEnd(Animator animator) {}

        @Override
        public void onAnimationCancel(Animator animator) {}

        @Override
        public void onAnimationRepeat(Animator animator) {}
    }

    /** States that the UI goes through. */
    public enum State {
        UNKNOWN,
        SEARCHING,
        CONNECTED
    }
}
