package com.bvalarm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.File;

/**
 * Foreground service that actually rings. It is started from the exact alarm
 * broadcast, plays the downloaded BV audio (or the system alarm tone), vibrates,
 * and posts a full-screen-intent notification.
 */
public class RingService extends Service {

    public static final String ACTION_START = "com.bvalarm.app.action.RING_START";
    public static final String ACTION_STOP = "com.bvalarm.app.action.RING_STOP";
    public static final String ACTION_SNOOZE = "com.bvalarm.app.action.RING_SNOOZE";
    public static final String ACTION_RING_STOPPED = "com.bvalarm.app.action.RING_STOPPED";

    public static final String EXTRA_ID = "alarm_id";
    public static final String EXTRA_SNOOZE = "snooze";

    public static final String CHANNEL_ID = "alarm_ringing";
    private static final int NOTIFICATION_ID = 1001;
    private static final long DEFAULT_AUTO_STOP_MS = 10L * 60L * 1000L;

    /** Gradual volume ramp: starts quiet and reaches full volume in ~20s. */
    private static final long RAMP_MS = 20_000L;
    private static final int RAMP_TICK_MS = 500;

    /** Lets RingActivity know whether a ring is actually in progress in this process. */
    private static volatile boolean sRinging;

    public static boolean isRinging() {
        return sRinging;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private Alarm alarm;
    private int savedAlarmVolume = -1;
    private boolean ringing;
    private AudioFocusRequest focusRequest;
    private LoudnessEnhancer loudnessEnhancer;
    private boolean boostLoudness;
    private long autoStopMs = DEFAULT_AUTO_STOP_MS;
    private long rampStartedAt;

    private final Runnable autoStop = new Runnable() {
        @Override
        public void run() {
            stopRinging(false);
        }
    };

    private final Runnable rampTick = new Runnable() {
        @Override
        public void run() {
            rampVolume();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRinging(true);
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            snooze();
            return START_NOT_STICKY;
        }

        long id = intent == null ? Long.MIN_VALUE : intent.getLongExtra(EXTRA_ID, Long.MIN_VALUE);
        Alarm found = id == Long.MIN_VALUE ? null : AlarmStore.find(this, id);
        if (found == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startRinging(found);
        return START_NOT_STICKY;
    }

    private void startRinging(Alarm a) {
        if (ringing) {
            stopPlayback();
        }
        alarm = a;
        boostLoudness = a.loud;
        autoStopMs = Math.max(1, a.ringDurationMinutes) * 60_000L;
        if (a.loud) {
            boostAlarmVolume();
        }
        ringing = true;
        sRinging = true;
        startForegroundCompat();
        acquireWakeLock();
        requestAudioFocus();
        playSound(a);
        startVibration(a);
        startRamp();
        handler.removeCallbacks(autoStop);
        handler.postDelayed(autoStop, autoStopMs);
        launchRingScreen(a);
    }

    private void playSound(Alarm a) {
        stopPlayer();
        if (a.hasBvRingtone()) {
            File file = new File(a.ringPath);
            if (file.exists() && file.length() > 1024
                    && playFrom(file.getAbsolutePath())) {
                return;
            }
        }
        playFrom(null);
    }

    private boolean playFrom(String path) {
        if (path != null) {
            return tryPlay(path, null);
        }
        int[] types = {RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE,
                RingtoneManager.TYPE_NOTIFICATION};
        for (int type : types) {
            Uri uri = RingtoneManager.getDefaultUri(type);
            if (uri != null && tryPlay(null, uri)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryPlay(String path, Uri uri) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setLooping(true);
            if (path != null) {
                mp.setDataSource(path);
            } else {
                mp.setDataSource(this, uri);
            }
            mp.prepare();
            if (boostLoudness) {
                attachLoudness(mp);
            }
            mp.setVolume(0.0f, 0.0f);
            mp.start();
            player = mp;
            rampStartedAt = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private void startRamp() {
        rampStartedAt = System.currentTimeMillis();
        handler.removeCallbacks(rampTick);
        handler.post(rampTick);
    }

    private void rampVolume() {
        if (!ringing || player == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - rampStartedAt;
        if (elapsed >= RAMP_MS) {
            if (player != null) {
                player.setVolume(1.0f, 1.0f);
            }
            return;
        }
        float progress = (float) elapsed / (float) RAMP_MS;
        // Ease-in curve so it stays quiet at first and swells near the end.
        float volume = progress * progress;
        if (player != null) {
            player.setVolume(volume, volume);
        }
        handler.postDelayed(rampTick, RAMP_TICK_MS);
    }

    private void boostAlarmVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int current = am.getStreamVolume(AudioManager.STREAM_ALARM);
            if (max > 0 && current < max) {
                if (savedAlarmVolume < 0) {
                    savedAlarmVolume = current;
                }
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void attachLoudness(MediaPlayer mp) {
        releaseLoudness();
        try {
            LoudnessEnhancer enhancer = new LoudnessEnhancer(mp.getAudioSessionId());
            enhancer.setTargetGain(700);
            enhancer.setEnabled(true);
            loudnessEnhancer = enhancer;
        } catch (Exception ignored) {
            // Not every device exposes the loudness enhancer; the volume boost
            // above still applies.
        }
    }

    private void releaseLoudness() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.release();
            } catch (Exception ignored) {
            }
            loudnessEnhancer = null;
        }
    }

    private void restoreAlarmVolume() {
        if (savedAlarmVolume < 0) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            }
        } catch (Exception ignored) {
        }
        savedAlarmVolume = -1;
    }

    private void startVibration(Alarm a) {
        if (!a.vibrate) {
            return;
        }
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            long[] pattern = {0, 700, 500, 700, 500, 1200, 900};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BVAlarm::ring");
                wakeLock.acquire(autoStopMs + 60_000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void requestAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            // An alarm keeps ringing even if something else grabs focus.
                        }
                    })
                    .build();
            am.requestAudioFocus(focusRequest);
        } catch (Exception ignored) {
        }
    }

    private void abandonAudioFocus() {
        if (focusRequest == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Exception ignored) {
        }
        focusRequest = null;
    }

    private void launchRingScreen(Alarm a) {
        try {
            Intent intent = new Intent(this, RingActivity.class);
            intent.putExtra(EXTRA_ID, a.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception ignored) {
            // Background activity starts may be blocked; the full-screen-intent
            // notification below is the supported path.
        }
    }

    private void stopRinging(boolean fromUser) {
        if (fromUser && alarm != null && !alarm.repeats()) {
            AlarmStore.setEnabled(this, alarm.id, false);
        }
        handler.removeCallbacks(autoStop);
        teardown();
        stopSelf();
    }

    private void snooze() {
        if (alarm != null) {
            AlarmScheduler.scheduleSnooze(this, alarm, Math.max(1, alarm.snoozeMinutes));
        }
        handler.removeCallbacks(autoStop);
        teardown();
        stopSelf();
    }

    private void teardown() {
        ringing = false;
        sRinging = false;
        handler.removeCallbacks(rampTick);
        stopPlayback();
        try {
            sendBroadcast(new Intent(ACTION_RING_STOPPED).setPackage(getPackageName()));
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void stopPlayback() {
        stopPlayer();
        abandonAudioFocus();
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
        restoreAlarmVolume();
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
    }

    private void stopPlayer() {
        releaseLoudness();
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, RingActivity.class);
        open.putExtra(EXTRA_ID, alarm.id);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopIntent = PendingIntent.getService(this, 11,
                new Intent(this, RingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snoozeIntent = PendingIntent.getService(this, 12,
                new Intent(this, RingService.class).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = alarm.timeText() + (alarm.label.isEmpty() ? " 闹钟" : "  " + alarm.label);
        String text = alarm.hasBvRingtone()
                ? "B站铃声" + (alarm.ringTitle.isEmpty() ? "" : " · " + alarm.ringTitle)
                : "系统默认铃声";

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setSound(null)
                .setColor(0xFFFB7299)
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopIntent)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_alarm, "稍后提醒",
                        snoozeIntent).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_alarm, "停止",
                        stopIntent).build());

        if (canUseFullScreenIntent()) {
            builder.setFullScreenIntent(contentIntent, true);
        }
        return builder.build();
    }

    private boolean canUseFullScreenIntent() {
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            return nm != null && nm.canUseFullScreenIntent();
        }
        return true;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoStop);
        handler.removeCallbacks(rampTick);
        sRinging = false;
        stopPlayback();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "闹钟响铃",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("闹钟到点时的全屏提醒");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setShowBadge(true);
        nm.createNotificationChannel(channel);
    }

    /** Used if the service could not be started; still better than silence. */
    public static void postFallbackNotification(Context context, Alarm alarm) {
        try {
            ensureChannel(context);
            NotificationManager nm = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 20, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_alarm)
                    .setContentTitle(alarm.timeText() + " 闹钟")
                    .setContentText("闹钟服务启动失败，点按打开应用")
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTIFICATION_ID + 1, notification);
        } catch (Exception ignored) {
        }
    }
}
