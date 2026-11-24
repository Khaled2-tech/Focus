package com.focus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.RemoteViews;

import java.util.Locale;

public class TimerService extends Service {
    public static final String ACTION_REFRESH = "com.focus.app.REFRESH";
    public static final String ACTION_PAUSE = "com.focus.app.ACTION_PAUSE";
    public static final String ACTION_RESUME = "com.focus.app.ACTION_RESUME";
    public static final String ACTION_STOP_ALARM = "com.focus.app.ACTION_STOP_ALARM";
    public static final String ACTION_EXTRA_TIME = "com.focus.app.ACTION_EXTRA_TIME";
    public static final String ACTION_ALERT_STOPPED = "com.focus.app.ACTION_ALERT_STOPPED";
    public static final String ACTION_START_STUDY = "com.focus.app.ACTION_START_STUDY";


    private static final String CHANNEL_TIMER = "focus_timer";
    private static final String CHANNEL_ALERTS = "focus_alerts";

    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer alarmPlayer;
    private SeamlessAmbientPlayer ambientPlayer;
    private String activeAmbientKey;

    private final Runnable ambientStopRunnable = this::stopAmbientPlayback;
    private final Runnable alarmTimeoutRunnable = this::stopAlarmOnly;

    private final SharedPreferences.OnSharedPreferenceChangeListener ambientPrefsListener = (shared, key) -> {
        if ("ambient_play_enabled".equals(key) || "ambient_sound_key".equals(key)
                || "ambient_volume".equals(key) || "running".equals(key) || "phase".equals(key)
                || "end_at".equals(key)) {
            handler.post(this::syncAmbientPlayback);
        }
    };

    private int currentRepeat;
    private int totalRepeats;
    private boolean finishing;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (prefs == null || !prefs.getBoolean("running", false)) {
                return;
            }
            long left = prefs.getLong("end_at", 0L) - System.currentTimeMillis();
            if (left <= 1000L) {
                stopAmbientPlayback();
            }
            if (left <= 0) {
                stopAmbientPlayback();
                finishSession();
                return;
            }
            updateTimerNotification(clock(left), true);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("focus_prefs", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(ambientPrefsListener);
        createChannels();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "focus:timer");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        holdWake();

        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_STOP_ALARM.equals(act)) {
                stopAlarmOnly();
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(11);
                } catch (Exception ignored) {}
                return START_STICKY;
            } else if (ACTION_START_STUDY.equals(act)) {
                stopAlarmOnly();
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(11);
                } catch (Exception ignored) {}
                long dur = minutesFor(0) * 60000L;
                long endAt = System.currentTimeMillis() + dur;
                prefs.edit()
                        .putInt("phase", 0)
                        .putBoolean("running", true)
                        .putLong("session_duration_ms", dur)
                        .putLong("remaining_ms", dur)
                        .putLong("end_at", endAt)
                        .apply();
                handler.removeCallbacks(tick);
                syncAmbientPlayback();
                startForeground(10, buildTimerNotification(clock(dur), true));
                handler.post(tick);
                return START_STICKY;
            } else if (ACTION_EXTRA_TIME.equals(act)) {
                stopAlarmOnly();
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(11);
                } catch (Exception ignored) {}
                int ep = intent.getIntExtra("ended_phase", 0);
                long addMs = 5 * 60 * 1000L;
                long total = sessionDuration() + addMs;
                long endAt = System.currentTimeMillis() + addMs;
                prefs.edit()
                        .putInt("phase", ep)
                        .putBoolean("running", true)
                        .putLong("session_duration_ms", total)
                        .putLong("remaining_ms", addMs)
                        .putLong("end_at", endAt)
                        .apply();
                handler.removeCallbacks(tick);
                syncAmbientPlayback();
                startForeground(10, buildTimerNotification(clock(addMs), true));
                handler.post(tick);
                return START_STICKY;
            } else if (ACTION_PAUSE.equals(act)) {
                long now = System.currentTimeMillis();
                long left = Math.max(0, prefs.getLong("end_at", now) - now);
                prefs.edit().putBoolean("running", false).putLong("remaining_ms", left).apply();
                handler.removeCallbacks(tick);
                stopAmbientPlayback();
                updateTimerNotification(clock(left), false);
                return START_STICKY;
            } else if (ACTION_RESUME.equals(act)) {
                long total = sessionDuration();
                long left = prefs.getLong("remaining_ms", total);
                if (left <= 0 || left > total) left = total;
                long endAt = System.currentTimeMillis() + left;
                prefs.edit().putBoolean("running", true).putLong("end_at", endAt).apply();
                handler.removeCallbacks(tick);
                syncAmbientPlayback();
                startForeground(10, buildTimerNotification(clock(left), true));
                handler.post(tick);
                return START_STICKY;
            }
        }

        stopAlarm();
        boolean isRunning = prefs.getBoolean("running", false);
        long now = System.currentTimeMillis();
        long left = isRunning ? Math.max(0, prefs.getLong("end_at", now) - now) : prefs.getLong("remaining_ms", sessionDuration());
        startForeground(10, buildTimerNotification(clock(left), isRunning));

        handler.removeCallbacks(tick);
        if (isRunning) {
            syncAmbientPlayback();
            handler.post(tick);
        }
        return START_STICKY;
    }

    public static void stopAlarmNow(Context context) {
        try {
            Intent i = new Intent(context, TimerService.class);
            i.setAction(ACTION_STOP_ALARM);
            context.startService(i);
        } catch (Exception ignored) {}
    }

    public static void addExtraTime(Context context, int endedPhase) {
        try {
            Intent i = new Intent(context, TimerService.class);
            i.setAction(ACTION_EXTRA_TIME);
            i.putExtra("ended_phase", endedPhase);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (prefs != null && prefs.getBoolean("running", false)) {
            Intent again = new Intent(this, TimerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(again); else startService(again);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void finishSession() {
        if (finishing || prefs == null) return;
        finishing = true;
        int phase = prefs.getInt("phase", 0);
        int completed = prefs.getInt("completed_sessions", 0);
        int cycle = Math.max(1, prefs.getInt("long_after", 4));
        boolean advance;
        int next;
        if (phase == 0) {
            completed += 1;
            next = completed % cycle == 0 ? 2 : 1;
            advance = prefs.getBoolean("auto_short", true);
        } else if (phase == 1) {
            next = 0;
            advance = prefs.getBoolean("auto_study", true);
        } else {
            next = 0;
            advance = prefs.getBoolean("auto_long", true);
        }
        stopAmbientPlayback();
        long dur = minutesFor(next) * 60000L;
        SharedPreferences.Editor editor = prefs.edit()
                .putInt("completed_sessions", completed)
                .putInt("phase", next)
                .putLong("session_duration_ms", dur)
                .putLong("remaining_ms", dur);

        playAlarmForPhase(phase);
        vibrate();
        showCompletionAlert(phase);

        if (advance) {
            editor.putBoolean("running", true).putLong("end_at", System.currentTimeMillis() + dur).apply();
            syncAmbientPlayback();
            updateTimerNotification(clock(dur), true);
            finishing = false;
            handler.post(tick);
            return;
        }

        editor.putBoolean("running", false).apply();
        try {
            stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(10);
        } catch (Exception ignored) {}
        handler.removeCallbacks(tick);
        finishing = false;
    }

    private void showCompletionAlert(int endedPhase) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        // Interactive full-screen intent over lockscreen
        Intent alertIntent = new Intent(this, SessionAlertActivity.class);
        alertIntent.putExtra("ended_phase", endedPhase);
        alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent fullScreenPending = PendingIntent.getActivity(this, 100, alertIntent, piFlags);

        // Content intent:
        // For study session: opens session alert screen
        // For break: opens main timer screen
        Intent contentIntent;
        if (endedPhase == 0) {
            contentIntent = alertIntent;
        } else {
            contentIntent = new Intent(this, MainActivity.class);
            contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        PendingIntent contentPending = PendingIntent.getActivity(this, 101, contentIntent, piFlags);

        // Action 1: Extra Time (+5 min) or Start Study
        Intent action1Intent = new Intent(this, TimerService.class);
        if (endedPhase == 0) {
            action1Intent.setAction(ACTION_EXTRA_TIME);
            action1Intent.putExtra("ended_phase", endedPhase);
        } else {
            action1Intent.setAction(ACTION_START_STUDY);
        }
        PendingIntent piAction1 = PendingIntent.getService(this, 102, action1Intent, piFlags);

        // Action 2: Stop Alarm
        Intent stopIntent = new Intent(this, TimerService.class);
        stopIntent.setAction(ACTION_STOP_ALARM);
        PendingIntent piStop = PendingIntent.getService(this, 103, stopIntent, piFlags);

        // Exact texts from Notifications_System.md
        String title;
        String text;
        String action1Text;
        int action1Icon;
        if (endedPhase == 0) {
            title = "انتهت جلسة المذاكرة!";
            text = "أحسنت! لقد أنهيت جلسة المذاكرة!";
            action1Text = "وقت إضافي";
            action1Icon = R.drawable.ic_clock_white;
        } else if (endedPhase == 1) {
            title = "انتهت الاستراحة القصيرة!";
            text = "حان وقت العودة إلى المذاكرة!";
            action1Text = "بدء المذاكرة";
            action1Icon = R.drawable.ic_play;
        } else {
            title = "انتهت الاستراحة الطويلة!";
            text = "انتهت استراحتك الطويلة، استعد لجلسة مذاكرة جديدة!";
            action1Text = "بدء المذاكرة";
            action1Icon = R.drawable.ic_play;
        }

        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_completion);
        views.setTextViewText(R.id.notif_comp_title, title);
        views.setTextViewText(R.id.notif_comp_text, text);
        views.setTextViewText(R.id.notif_comp_text1, action1Text);
        views.setImageViewResource(R.id.notif_comp_icon1, action1Icon);
        views.setTextViewText(R.id.notif_comp_text2, "إيقاف");
        views.setImageViewResource(R.id.notif_comp_icon2, R.drawable.ic_close_white);

        views.setOnClickPendingIntent(R.id.notif_comp_action1, piAction1);
        views.setOnClickPendingIntent(R.id.notif_comp_action2, piStop);
        views.setOnClickPendingIntent(R.id.notif_comp_title, contentPending);
        views.setOnClickPendingIntent(R.id.notif_comp_text, contentPending);
        views.setOnClickPendingIntent(R.id.notif_comp_root, contentPending);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ALERTS);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(R.drawable.ic_focus_logo)
         .setContentTitle(title)
         .setContentText(text)
         .setContentIntent(contentPending)
         .setFullScreenIntent(fullScreenPending, true)
         .setAutoCancel(true)
         .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFF5146D8);
            b.setCategory(Notification.CATEGORY_ALARM);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        if (Build.VERSION.SDK_INT >= 16) {
            b.setPriority(Notification.PRIORITY_MAX);
            b.setStyle(new Notification.BigTextStyle().bigText(text));
            b.addAction(action1Icon, action1Text, piAction1);
            b.addAction(R.drawable.ic_close_white, "إيقاف", piStop);
        }

        if (Build.VERSION.SDK_INT >= 24) {
            b.setCustomContentView(views);
            b.setCustomBigContentView(views);
            b.setStyle(new Notification.DecoratedCustomViewStyle());
        } else {
            b.setContent(views);
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(11, b.build());
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                startActivity(alertIntent);
            }
        } catch (Exception ignored) {}
    }

    private int minutesFor(int phase) {
        if (phase == 0) return prefs.getInt("study_minutes", 25);
        if (phase == 1) return prefs.getInt("short_minutes", 5);
        return prefs.getInt("long_minutes", 15);
    }

    private long sessionDuration() {
        if (prefs == null) return 25 * 60000L;
        int p = prefs.getInt("phase", 0);
        long stored = prefs.getLong("session_duration_ms", 0L);
        return stored > 0 ? stored : (minutesFor(p) * 60000L);
    }

    private String phaseName(int phase) {
        if (phase == 0) return "مذاكرة";
        if (phase == 1) return "استراحة قصيرة";
        return "استراحة طويلة";
    }

    private String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private int rawResForSoundKey(String key) {
        if (key == null) return R.raw.sparkle;
        switch (key) {
            case "chime": return R.raw.chime;
            case "piano": return R.raw.piano;
            case "success": return R.raw.success;
            case "game_show": return R.raw.game_show;
            case "train_arrival": return R.raw.train_arrival;
            case "commuter_jingle": return R.raw.commuter_jingle;
            case "sparkle":
            default:
                return R.raw.sparkle;
        }
    }

    private int rawResForAmbientKey(String key) {
        if (key == null) return 0;
        switch (key) {
            case "rain": return R.raw.rain;
            case "light_rain": return R.raw.light_rain;
            case "heavy_rain": return R.raw.heavy_rain;
            case "light_rain_on_tent": return R.raw.light_rain_on_tent;
            case "thunderstorm": return R.raw.thunderstorm;
            case "ocean": return R.raw.ocean;
            case "waterfall": return R.raw.waterfall;
            case "campfire": return R.raw.campfire;
            case "fireplace": return R.raw.fireplace;
            case "wind": return R.raw.wind;
            case "birds_in_the_woods": return R.raw.birds_in_the_woods;
            case "summer_night": return R.raw.summer_night;
            case "exam_hall": return R.raw.exam_hall;
            default: return 0;
        }
    }

    private void syncAmbientPlayback() {
        if (prefs == null) return;
        boolean shouldPlay = prefs.getBoolean("running", false)
                && prefs.getInt("phase", 0) == 0
                && prefs.getBoolean("ambient_play_enabled", false);
        String key = prefs.getString("ambient_sound_key", "");
        int rawRes = rawResForAmbientKey(key);
        if (!shouldPlay || key.isEmpty() || rawRes == 0) {
            stopAmbientPlayback();
            return;
        }

        if (ambientPlayer == null) {
            ambientPlayer = new SeamlessAmbientPlayer(this);
        }

        if (!key.equals(activeAmbientKey) || !ambientPlayer.isPlaying()) {
            activeAmbientKey = key;
            float volume = prefs.getFloat("ambient_volume", 0.70f);
            ambientPlayer.play(rawRes, volume);
        } else {
            float volume = prefs.getFloat("ambient_volume", 0.70f);
            ambientPlayer.setVolume(volume);
        }
        scheduleAmbientStop();
    }

    private void scheduleAmbientStop() {
        handler.removeCallbacks(ambientStopRunnable);
        if (prefs == null || ambientPlayer == null || !ambientPlayer.isPlaying()) return;
        long stopAt = prefs.getLong("end_at", 0L) - 1000L;
        long delay = stopAt - System.currentTimeMillis();
        if (delay <= 0) {
            stopAmbientPlayback();
        } else {
            handler.postDelayed(ambientStopRunnable, delay);
        }
    }

    private void stopAmbientPlayback() {
        handler.removeCallbacks(ambientStopRunnable);
        if (ambientPlayer != null) {
            ambientPlayer.stop();
            ambientPlayer = null;
        }
        activeAmbientKey = null;
    }

    /**
     * SeamlessAmbientPlayer maintains two alternating MediaPlayer instances.
     * Before the active track finishes, the second player is started at 0 offset,
     * cross-overlapping by ~700ms to eliminate any pause or silence gap during looping.
     */
    private static class SeamlessAmbientPlayer {
        private final Context context;
        private final Handler handler = new Handler();
        private int rawRes;
        private float volume = 0.70f;
        private boolean isPlaying = false;

        private MediaPlayer playerA;
        private MediaPlayer playerB;
        private boolean isCurrentA = true;
        private int trackDuration = 0;

        private final Runnable loopTransitionRunnable = this::onLoopHandoff;
        private final Runnable stopPrevRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying) return;
                MediaPlayer prev = isCurrentA ? playerB : playerA;
                if (prev != null) {
                    try {
                        if (prev.isPlaying()) prev.pause();
                        prev.seekTo(0);
                    } catch (Exception ignored) {}
                }
            }
        };

        public SeamlessAmbientPlayer(Context context) {
            this.context = context.getApplicationContext();
        }

        public synchronized void play(int resId, float vol) {
            stop();
            this.rawRes = resId;
            this.volume = vol;
            try {
                playerA = MediaPlayer.create(context, rawRes);
                if (playerA == null) return;
                trackDuration = playerA.getDuration();
                playerA.setVolume(volume, volume);

                try {
                    playerB = MediaPlayer.create(context, rawRes);
                    if (playerB != null) {
                        playerB.setVolume(volume, volume);
                    }
                } catch (Exception ignored) {
                    playerB = null;
                }

                if (playerB == null || trackDuration <= 1500) {
                    playerA.setLooping(true);
                }

                isCurrentA = true;
                isPlaying = true;
                playerA.start();
                scheduleNextHandoff(trackDuration - 700);
            } catch (Exception ignored) {
                stop();
            }
        }

        public synchronized void setVolume(float vol) {
            this.volume = vol;
            if (playerA != null) {
                try { playerA.setVolume(vol, vol); } catch (Exception ignored) {}
            }
            if (playerB != null) {
                try { playerB.setVolume(vol, vol); } catch (Exception ignored) {}
            }
        }

        private void scheduleNextHandoff(long delayMs) {
            handler.removeCallbacks(loopTransitionRunnable);
            if (!isPlaying || playerB == null || trackDuration <= 1500) return;
            long delay = Math.max(200, delayMs);
            handler.postDelayed(loopTransitionRunnable, delay);
        }

        private void onLoopHandoff() {
            if (!isPlaying) return;
            try {
                MediaPlayer next = isCurrentA ? playerB : playerA;
                if (next != null) {
                    next.seekTo(0);
                    next.setVolume(volume, volume);
                    next.start();
                }

                isCurrentA = !isCurrentA;

                handler.removeCallbacks(stopPrevRunnable);
                handler.postDelayed(stopPrevRunnable, 750);

                scheduleNextHandoff(trackDuration - 700);
            } catch (Exception ignored) {}
        }

        public synchronized void stop() {
            isPlaying = false;
            handler.removeCallbacks(loopTransitionRunnable);
            handler.removeCallbacks(stopPrevRunnable);
            if (playerA != null) {
                try {
                    playerA.setVolume(0f, 0f);
                    if (playerA.isPlaying()) playerA.pause();
                    playerA.stop();
                    playerA.reset();
                    playerA.release();
                } catch (Exception ignored) {}
                playerA = null;
            }
            if (playerB != null) {
                try {
                    playerB.setVolume(0f, 0f);
                    if (playerB.isPlaying()) playerB.pause();
                    playerB.stop();
                    playerB.reset();
                    playerB.release();
                } catch (Exception ignored) {}
                playerB = null;
            }
            trackDuration = 0;
        }

        public synchronized boolean isPlaying() {
            MediaPlayer cur = isCurrentA ? playerA : playerB;
            return isPlaying && cur != null && cur.isPlaying();
        }
    }

    private void playAlarmForPhase(int endedPhase) {
        stopAmbientPlayback();
        stopAlarm();
        handler.removeCallbacks(alarmTimeoutRunnable);

        try {
            String soundKey = prefs != null ? prefs.getString("alert_sound_key", "sparkle") : "sparkle";
            int resId = rawResForSoundKey(soundKey);
            final float vol = prefs != null ? prefs.getFloat("alert_volume", 0.8f) : 0.8f;

            alarmPlayer = MediaPlayer.create(getApplicationContext(), resId);
            if (alarmPlayer == null) {
                stopAlarmOnly();
                return;
            }
            alarmPlayer.setVolume(vol, vol);
            alarmPlayer.setOnCompletionListener(mp -> stopAlarmOnly());
            alarmPlayer.start();

            // Maximum 1 minute alert sound duration as specified in Notifications_System.md
            handler.postDelayed(alarmTimeoutRunnable, 60000L);
        } catch (Exception ignored) {
            stopAlarmOnly();
        }
    }

    private void stopAlarmOnly() {
        handler.removeCallbacks(alarmTimeoutRunnable);
        stopAlarm();
        try {
            sendBroadcast(new Intent(ACTION_ALERT_STOPPED));
        } catch (Exception ignored) {}
    }

    private void stopAlarm() {
        if (alarmPlayer != null) {
            try {
                if (alarmPlayer.isPlaying()) alarmPlayer.stop();
                alarmPlayer.release();
            } catch (Exception ignored) {}
            alarmPlayer = null;
        }
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(400);
        } catch (Exception ignored) {}
    }

    private void holdWake() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWake() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel cTimer = new NotificationChannel(CHANNEL_TIMER, "مؤقّت Focus", NotificationManager.IMPORTANCE_LOW);
            cTimer.setDescription("العداد التنازلي لحالة المؤقت");
            cTimer.setSound(null, null);
            cTimer.enableVibration(false);
            cTimer.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(cTimer);

            NotificationChannel cAlert = new NotificationChannel(CHANNEL_ALERTS, "تنبيهات انتهاء الجلسات", NotificationManager.IMPORTANCE_HIGH);
            cAlert.setDescription("تنبيه عند انتهاء وقت الجلسة أو الاستراحة");
            cAlert.enableVibration(true);
            cAlert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            cAlert.setBypassDnd(true);
            nm.createNotificationChannel(cAlert);
        }
    }

    private void updateTimerNotification(String countdownStr, boolean isRunning) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(10, buildTimerNotification(countdownStr, isRunning));
        }
    }

    private Notification buildTimerNotification(String countdownStr, boolean isRunning) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent toggleIntent = new Intent(this, TimerService.class);
        toggleIntent.setAction(isRunning ? ACTION_PAUSE : ACTION_RESUME);
        PendingIntent piToggle = PendingIntent.getService(this, 1, toggleIntent, piFlags);

        int phase = prefs != null ? prefs.getInt("phase", 0) : 0;
        String statusText;
        if (phase == 0) {
            statusText = isRunning ? "جلسة مذاكرة جارية" : "جلسة مذاكرة (متوقفة مؤقتاً)";
        } else if (phase == 1) {
            statusText = isRunning ? "استراحة قصيرة جارية" : "استراحة قصيرة (متوقفة مؤقتاً)";
        } else {
            statusText = isRunning ? "استراحة طويلة جارية" : "استراحة طويلة (متوقفة مؤقتاً)";
        }
        String headerTitle = "Focus • " + statusText;

        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_timer);
        views.setTextViewText(R.id.notif_phase_title, headerTitle);
        views.setTextViewText(R.id.notif_countdown, countdownStr);
        views.setTextViewText(R.id.notif_action_text, isRunning ? "إيقاف مؤقت" : "متابعة");
        views.setImageViewResource(R.id.notif_action_icon, isRunning ? R.drawable.ic_pause : R.drawable.ic_play);
        views.setImageViewResource(R.id.notif_logo, R.drawable.ic_focus_logo);

        views.setOnClickPendingIntent(R.id.notif_action_btn, piToggle);
        views.setOnClickPendingIntent(R.id.notif_action_text, piToggle);
        views.setOnClickPendingIntent(R.id.notif_action_icon, piToggle);
        views.setOnClickPendingIntent(R.id.notif_phase_title, piOpen);
        views.setOnClickPendingIntent(R.id.notif_countdown, piOpen);
        views.setOnClickPendingIntent(R.id.notif_logo, piOpen);
        views.setOnClickPendingIntent(R.id.notif_root, piOpen);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_TIMER);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(R.drawable.ic_focus_logo)
         .setContentTitle(headerTitle)
         .setContentText(countdownStr)
         .setContentIntent(piOpen)
         .setOngoing(isRunning)
         .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFF5146D8);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        if (Build.VERSION.SDK_INT >= 16) {
            b.setPriority(Notification.PRIORITY_LOW);
            b.addAction(isRunning ? R.drawable.ic_pause : R.drawable.ic_play,
                        isRunning ? "إيقاف مؤقت" : "متابعة", piToggle);
        }

        if (Build.VERSION.SDK_INT >= 24) {
            b.setCustomContentView(views);
            b.setCustomBigContentView(views);
            b.setStyle(new Notification.DecoratedCustomViewStyle());
        } else {
            b.setContent(views);
        }

        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        return b.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(alarmTimeoutRunnable);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(ambientPrefsListener);
        stopAmbientPlayback();
        stopAlarm();
        try {
            stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(10);
        } catch (Exception ignored) {}
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
