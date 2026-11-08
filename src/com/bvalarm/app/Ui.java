package com.bvalarm.app;

import android.content.Context;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public final class Ui {

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    /** "今天 07:30" / "明天 07:30" / "周四 07:30" / "3月8日 07:30" */
    public static String describeTrigger(long trigger) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate day = Instant.ofEpochMilli(trigger).atZone(zone).toLocalDate();
        long diff = ChronoUnit.DAYS.between(today, day);
        String time = String.format(Locale.US, "%02d:%02d",
                Instant.ofEpochMilli(trigger).atZone(zone).getHour(),
                Instant.ofEpochMilli(trigger).atZone(zone).getMinute());
        if (diff == 0) {
            return "今天 " + time;
        }
        if (diff == 1) {
            return "明天 " + time;
        }
        if (diff > 1 && diff < 7) {
            int index = day.getDayOfWeek().getValue() - 1;
            return WEEKDAYS[index] + " " + time;
        }
        return (day.getMonthValue()) + "月" + day.getDayOfMonth() + "日 " + time;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
