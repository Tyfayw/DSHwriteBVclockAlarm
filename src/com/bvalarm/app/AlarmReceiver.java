package com.bvalarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/**
 * Exact-alarm receiver. Starts the ringing foreground service and immediately
 * schedules the next occurrence so a repeating alarm never gets lost.
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmScheduler.ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        long id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, Long.MIN_VALUE);
        boolean snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false);
        Alarm alarm = AlarmStore.find(context, id);
        if (alarm == null) {
            return;
        }

        if (!snooze && alarm.skipNext) {
            // "Skip next" was requested: clear the flag and reschedule for the
            // following occurrence without ringing this time.
            alarm.skipNext = false;
            AlarmStore.upsert(context, alarm);
            AlarmScheduler.schedule(context, alarm);
            return;
        }

        holdCpuBriefly(context);

        Intent service = new Intent(context, RingService.class);
        service.setAction(RingService.ACTION_START);
        service.putExtra(RingService.EXTRA_ID, id);
        service.putExtra(RingService.EXTRA_SNOOZE, snooze);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            // Last resort: make sure the user still gets a notification.
            RingService.postFallbackNotification(context, alarm);
        }

        if (!snooze) {
            if (alarm.repeats()) {
                AlarmScheduler.schedule(context, alarm);
            } else {
                alarm.enabled = false;
                AlarmStore.upsert(context, alarm);
            }
        }
    }

    private void holdCpuBriefly(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "BVAlarm::receiver");
                lock.acquire(10_000L);
            }
        } catch (Exception ignored) {
        }
    }
}
