package com.enzo.podsguard;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PodsGuardListener extends NotificationListenerService {
    private static final long RECENT_PLAYING_WINDOW_MS = 3200L;
    private static final long REFRESH_INTERVAL_MS = 1500L;
    private static final long RESUME_THROTTLE_MS = 180L;

    private static volatile PodsGuardListener instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, SessionRecord> sessions = new HashMap<>();

    private MediaSessionManager sessionManager;
    private ComponentName listenerComponent;
    private AudioManager audioManager;

    private long lastAnyPlayingAt = 0L;
    private long lastRefreshAt = 0L;
    private long lastGlobalPlayAt = 0L;
    private boolean globalResumePending = false;

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsListener =
            this::attachSessions;

    static void requestRefresh(Context context) {
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(context, PodsGuardListener.class));
        } catch (Exception ignored) {
        }

        PodsGuardListener current = instance;
        if (current != null) current.refreshSessions();
    }

    static void watchdogTick(Context context) {
        PodsGuardListener current = instance;
        if (current == null) {
            requestRefresh(context);
            return;
        }
        current.runWatchdog();
    }

    static void pauseCurrentPlayerOnce() {
        PodsGuardListener current = instance;
        if (current != null) current.pauseBestController();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        listenerComponent = new ComponentName(this, PodsGuardListener.class);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;

        try {
            sessionManager.addOnActiveSessionsChangedListener(
                    activeSessionsListener, listenerComponent, handler);
        } catch (SecurityException ignored) {
            return;
        }

        refreshSessions();
        if (Prefs.get(this).getBoolean(Prefs.ENABLED, false)) {
            GuardService.start(this);
        }
    }

    @Override
    public void onListenerDisconnected() {
        clearSessions();
        try {
            sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener);
        } catch (Exception ignored) {
        }
        if (instance == this) instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        clearSessions();
        try {
            sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener);
        } catch (Exception ignored) {
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handler.postDelayed(this::refreshSessions, 80L);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        handler.postDelayed(this::refreshSessions, 80L);
    }

    private void refreshSessions() {
        lastRefreshAt = System.currentTimeMillis();
        try {
            List<MediaController> controllers = sessionManager.getActiveSessions(listenerComponent);
            attachSessions(controllers);
        } catch (SecurityException ignored) {
        }
    }

    private void attachSessions(List<MediaController> controllers) {
        if (controllers == null) controllers = new ArrayList<>();

        long now = System.currentTimeMillis();
        Set<MediaSession.Token> alive = new HashSet<>();

        for (MediaController controller : controllers) {
            if (controller == null) continue;

            MediaSession.Token token = controller.getSessionToken();
            alive.add(token);

            SessionRecord existing = sessions.get(token);
            if (existing != null) {
                observeCurrentState(existing, now, false);
                continue;
            }

            SessionRecord record = new SessionRecord(controller);
            PlaybackState initial = safePlaybackState(controller);
            record.lastState = stateOf(initial);
            if (isPlayingLike(record.lastState)) {
                record.lastSeenPlayingAt = now;
                lastAnyPlayingAt = now;
            }
            controller.registerCallback(record.callback, handler);
            sessions.put(token, record);
        }

        List<MediaSession.Token> remove = new ArrayList<>();
        for (Map.Entry<MediaSession.Token, SessionRecord> entry : sessions.entrySet()) {
            if (!alive.contains(entry.getKey())) {
                try {
                    entry.getValue().controller.unregisterCallback(entry.getValue().callback);
                } catch (Exception ignored) {
                }
                remove.add(entry.getKey());
            }
        }
        for (MediaSession.Token token : remove) sessions.remove(token);
    }

    private void clearSessions() {
        for (SessionRecord record : sessions.values()) {
            try {
                record.controller.unregisterCallback(record.callback);
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }

    private void handlePlaybackState(SessionRecord record, PlaybackState state) {
        long now = System.currentTimeMillis();
        int newState = stateOf(state);
        int oldState = record.lastState;
        record.lastState = newState;

        if (isPlayingLike(newState)) {
            record.lastSeenPlayingAt = now;
            lastAnyPlayingAt = now;
            record.resumePending = false;
            return;
        }

        boolean directTransition = isPlayingLike(oldState) && isPauseLike(newState);
        boolean veryRecentPlayback = now - record.lastSeenPlayingAt <= RECENT_PLAYING_WINDOW_MS;

        if (isPauseLike(newState) && (directTransition || veryRecentPlayback)) {
            scheduleResume(record, state, false);
        }
    }

    private void runWatchdog() {
        SharedPreferences prefs = Prefs.get(this);
        if (!prefs.getBoolean(Prefs.ENABLED, false)) return;

        long now = System.currentTimeMillis();
        if (now - lastRefreshAt >= REFRESH_INTERVAL_MS) {
            refreshSessions();
        }

        if (audioManager != null && audioManager.isMusicActive()) {
            lastAnyPlayingAt = now;
        }

        boolean foundPlaying = false;
        boolean foundRecentPausedSession = false;

        for (SessionRecord record : new ArrayList<>(sessions.values())) {
            PlaybackState state = safePlaybackState(record.controller);
            int currentState = stateOf(state);

            if (isPlayingLike(currentState)) {
                foundPlaying = true;
                record.lastState = currentState;
                record.lastSeenPlayingAt = now;
                lastAnyPlayingAt = now;
                record.resumePending = false;
                continue;
            }

            boolean recentlyPlayed = now - record.lastSeenPlayingAt <= RECENT_PLAYING_WINDOW_MS;
            boolean globallyRecent = now - lastAnyPlayingAt <= 1100L;
            if (isPauseLike(currentState) && (recentlyPlayed || globallyRecent)) {
                foundRecentPausedSession = true;
                scheduleResume(record, state, true);
            }
        }

        if (!foundPlaying
                && !foundRecentPausedSession
                && now - lastAnyPlayingAt <= 900L
                && !globalResumePending) {
            scheduleGlobalPlay();
        }
    }

    private void observeCurrentState(SessionRecord record, long now, boolean fromWatchdog) {
        PlaybackState state = safePlaybackState(record.controller);
        int currentState = stateOf(state);
        if (isPlayingLike(currentState)) {
            record.lastState = currentState;
            record.lastSeenPlayingAt = now;
            lastAnyPlayingAt = now;
            record.resumePending = false;
        } else if (fromWatchdog && isPauseLike(currentState)
                && now - record.lastSeenPlayingAt <= RECENT_PLAYING_WINDOW_MS) {
            scheduleResume(record, state, true);
        }
    }

    private void scheduleResume(SessionRecord record, PlaybackState stateAtPause, boolean watchdog) {
        SharedPreferences prefs = Prefs.get(this);
        if (!canProtectNow(prefs)) return;
        if (record.resumePending) return;

        long now = System.currentTimeMillis();
        if (now - record.lastAutoResumeAt < RESUME_THROTTLE_MS) return;

        int delayMs = prefs.getInt(Prefs.DELAY_MS, 120);
        if (watchdog) delayMs = Math.min(delayMs, 120);
        delayMs = Math.max(40, Math.min(delayMs, 1500));

        record.resumePending = true;
        long pausedPosition = stateAtPause == null ? -1L : stateAtPause.getPosition();

        handler.postDelayed(() -> {
            record.resumePending = false;
            SharedPreferences latest = Prefs.get(this);
            if (!canProtectNow(latest)) return;

            PlaybackState current = safePlaybackState(record.controller);
            int currentState = stateOf(current);
            if (isPlayingLike(currentState)) return;
            if (!isPauseLike(currentState)) return;

            if (pausedPosition >= 0L && current != null && current.getPosition() >= 0L
                    && Math.abs(current.getPosition() - pausedPosition) > 2500L) {
                return;
            }

            performPlay(record, latest);
        }, delayMs);
    }

    private void performPlay(SessionRecord record, SharedPreferences prefs) {
        long now = System.currentTimeMillis();
        record.lastAutoResumeAt = now;

        try {
            // Do not require the player to advertise ACTION_PLAY: several popular players
            // still accept play() even when their PlaybackState action mask is incomplete.
            record.controller.getTransportControls().play();
        } catch (Exception ignored) {
        }

        incrementResumeCounter(prefs, now);

        // Some OEM/player combinations ignore the first transport command after an AVRCP pause.
        // Verify and use a real MEDIA_PLAY key as a fallback, then retry transport controls once.
        handler.postDelayed(() -> {
            if (!canProtectNow(Prefs.get(this))) return;
            PlaybackState state = safePlaybackState(record.controller);
            if (isPauseLike(stateOf(state))) {
                dispatchMediaPlayKey();
            }
        }, 180L);

        handler.postDelayed(() -> {
            if (!canProtectNow(Prefs.get(this))) return;
            PlaybackState state = safePlaybackState(record.controller);
            if (isPauseLike(stateOf(state))) {
                try {
                    record.controller.getTransportControls().play();
                } catch (Exception ignored) {
                }
            }
        }, 520L);
    }

    private void scheduleGlobalPlay() {
        SharedPreferences prefs = Prefs.get(this);
        if (!canProtectNow(prefs)) return;

        long now = System.currentTimeMillis();
        if (now - lastGlobalPlayAt < 700L) return;

        globalResumePending = true;
        int delayMs = Math.max(40, Math.min(prefs.getInt(Prefs.DELAY_MS, 120), 300));
        handler.postDelayed(() -> {
            globalResumePending = false;
            SharedPreferences latest = Prefs.get(this);
            if (!canProtectNow(latest)) return;
            if (audioManager != null && audioManager.isMusicActive()) return;
            if (System.currentTimeMillis() - lastAnyPlayingAt > 1500L) return;

            dispatchMediaPlayKey();
            lastGlobalPlayAt = System.currentTimeMillis();
            incrementResumeCounter(latest, lastGlobalPlayAt);
        }, delayMs);
    }

    private void dispatchMediaPlayKey() {
        if (audioManager == null) return;
        try {
            long eventTime = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(
                    eventTime, eventTime, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(
                    eventTime, eventTime, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        } catch (Exception ignored) {
        }
    }

    private boolean canProtectNow(SharedPreferences prefs) {
        if (!prefs.getBoolean(Prefs.ENABLED, false)) return false;
        if (System.currentTimeMillis() < prefs.getLong(Prefs.BYPASS_UNTIL, 0L)) return false;
        return !prefs.getBoolean(Prefs.ONLY_BLUETOOTH, true) || hasBluetoothAudioDevice();
    }

    private void incrementResumeCounter(SharedPreferences prefs, long when) {
        int count = prefs.getInt(Prefs.RESUME_COUNT, 0) + 1;
        prefs.edit()
                .putInt(Prefs.RESUME_COUNT, count)
                .putLong(Prefs.LAST_RESUME_AT, when)
                .apply();
    }

    private PlaybackState safePlaybackState(MediaController controller) {
        try {
            return controller.getPlaybackState();
        } catch (Exception ignored) {
            return null;
        }
    }

    private int stateOf(PlaybackState state) {
        return state == null ? PlaybackState.STATE_NONE : state.getState();
    }

    private boolean isPlayingLike(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING;
    }

    private boolean isPauseLike(int state) {
        return state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_STOPPED
                || state == PlaybackState.STATE_NONE
                || state == PlaybackState.STATE_ERROR;
    }

    private boolean hasBluetoothAudioDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            // A denied permission must not disable the core protection.
            return true;
        }

        if (audioManager == null) return true;

        try {
            AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : outputs) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_HEARING_AID
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    private void pauseBestController() {
        for (SessionRecord record : sessions.values()) {
            PlaybackState state = safePlaybackState(record.controller);
            if (isPlayingLike(stateOf(state))) {
                try {
                    record.controller.getTransportControls().pause();
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    private final class SessionRecord {
        final MediaController controller;
        int lastState = PlaybackState.STATE_NONE;
        long lastSeenPlayingAt = 0L;
        long lastAutoResumeAt = 0L;
        boolean resumePending = false;

        final MediaController.Callback callback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                handlePlaybackState(SessionRecord.this, state);
            }

            @Override
            public void onSessionDestroyed() {
                handler.post(PodsGuardListener.this::refreshSessions);
            }
        };

        SessionRecord(MediaController controller) {
            this.controller = controller;
        }
    }
}
