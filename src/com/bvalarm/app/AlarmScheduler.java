package com.bvalarm.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/**
 * Wraps AlarmManager#setAlarmClock, which gives us an exact, doze-proof alarm and
 * shows the system "next alarm" indicator. Falls back to setExactAndAllowWhileIdle
 * when the exact-alarm permission has not been granted.
 */
public final class AlarmScheduler {

    public static final String ACTION_FIRE = "com.bvalarm.app.action.FIRE";
    public static final String EXTRA_ID = "alarm_id";
    public static final String EXTRA_SNOOZE = "snooze";

    private static final int SNOOZE_OFFSET = 100000;

    private AlarmScheduler() {
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = context.getSystemService(AlarmManager.class);
            return am != null && am.canScheduleExactAlarms();
        }
        return true;
    }

    public static void rescheduleAll(Context context) {
        List<Alarm> alarms = AlarmStore.load(context);
        long now = System.currentTimeMillis();
        for (Alarm a : alarms) {
            if (!a.enabled) {
                cancel(context, a.id);
                continue;
            }
            long trigger = a.nextTrigger(now + 1000L);
            arm(context, a, trigger, false);
        }
    }

    public static void schedule(Context context, Alarm alarm) {
        if (!alarm.enabled) {
            cancel(context, alarm.id);
            return;
        }
        arm(context, alarm, alarm.nextTrigger(System.currentTimeMillis() + 1000L), false);
    }

    public static void scheduleSnooze(Context context, Alarm alarm, int minutes) {
        long trigger = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        arm(context, alarm, trigger, true);
    }

    public static void cancel(Context context, long id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent main = fireIntent(context, id, false);
        am.cancel(main);
        main.cancel();
        PendingIntent snoozed = fireIntent(context, id, true);
        am.cancel(snoozed);
        snoozed.cancel();
    }

    /** Earliest trigger among enabled alarms, or 0. */
    public static long nextAlarmTime(Context context) {
        long now = System.currentTimeMillis();
        long best = 0;
        for (Alarm a : AlarmStore.load(context)) {
            if (!a.enabled) {
                continue;
            }
            long t = a.nextTrigger(now);
            if (best == 0 || t < best) {
                best = t;
            }
        }
        return best;
    }

    private static void arm(Context context, Alarm alarm, long trigger, boolean snooze) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent operation = fireIntent(context, alarm.id, snooze);

        // Tapping the system "next alarm" indicator should open the app, not the
        // ringing screen. The ringing screen is launched by the notification's
        // full-screen intent instead.
        Intent show = new Intent(context, MainActivity.class);
        show.putExtra(EXTRA_ID, alarm.id);
        show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(context,
                requestCode(alarm.id, snooze) + 1, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(trigger, showPi);
        try {
            am.setAlarmClock(info, operation);
        } catch (SecurityException e) {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, trigger, operation);
                }
            } catch (SecurityException ignored) {
                am.set(AlarmManager.RTC_WAKEUP, trigger, operation);
            }
        }
    }

    private static PendingIntent fireIntent(Context context, long id, boolean snooze) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_FIRE);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_SNOOZE, snooze);
        return PendingIntent.getBroadcast(context, requestCode(id, snooze), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int requestCode(long id, boolean snooze) {
        int base = (int) (id & 0x7FFFFFFF);
        return snooze ? base + SNOOZE_OFFSET : base;
    }
}
