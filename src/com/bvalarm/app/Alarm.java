package com.bvalarm.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * One alarm. Kept deliberately simple so it can be serialised into SharedPreferences.
 */
public class Alarm {

    public static final int RING_DEFAULT = 0;
    public static final int RING_BV = 1;

    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    public long id;
    public int hour = 7;
    public int minute = 30;
    /** bit 0 = Monday ... bit 6 = Sunday. 0 means "only once". */
    public int days = 0;
    public boolean enabled = true;
    public String label = "";

    public int ringType = RING_DEFAULT;
    public String bvid = "";
    public String ringTitle = "";
    public String ringPath = "";

    public int snoozeMinutes = 5;
    public boolean vibrate = true;
    /** Raise the alarm stream to max and boost quiet source audio while ringing. */
    public boolean loud = true;
    /** Skip the very next occurrence, then resume normally. */
    public boolean skipNext = false;
    /** How long the alarm rings before stopping automatically, in minutes. */
    public int ringDurationMinutes = 10;

    public Alarm() {
        this.id = System.currentTimeMillis() * 1000L + (long) (Math.random() * 1000);
    }

    public boolean repeats() {
        return days != 0;
    }

    public boolean hasBvRingtone() {
        return ringType == RING_BV && ringPath != null && ringPath.length() > 0;
    }

    public String timeText() {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    /** Next time this alarm should fire, strictly after {@code nowMillis}. */
    public long nextTrigger(long nowMillis) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        for (int i = 0; i < 8; i++) {
            ZonedDateTime candidate = now.toLocalDate().plusDays(i).atStartOfDay(zone)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            long millis = candidate.toInstant().toEpochMilli();
            if (millis <= nowMillis) {
                continue;
            }
            if (days == 0) {
                return millis;
            }
            int dayIndex = candidate.getDayOfWeek().getValue() - 1;
            if ((days & (1 << dayIndex)) != 0) {
                return millis;
            }
        }
        return nowMillis + 24L * 60 * 60 * 1000;
    }

    public String daysSummary() {
        if (days == 0) {
            return "仅一次";
        }
        if (days == 0x7F) {
            return "每天";
        }
        if (days == 0x1F) {
            return "工作日";
        }
        if (days == 0x60) {
            return "周末";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((days & (1 << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append("周").append(DAY_NAMES[i]);
            }
        }
        return sb.toString();
    }

    public Alarm copy() {
        Alarm a = new Alarm();
        a.id = id;
        a.hour = hour;
        a.minute = minute;
        a.days = days;
        a.enabled = enabled;
        a.label = label;
        a.ringType = ringType;
        a.bvid = bvid;
        a.ringTitle = ringTitle;
        a.ringPath = ringPath;
        a.snoozeMinutes = snoozeMinutes;
        a.vibrate = vibrate;
        a.loud = loud;
        a.skipNext = skipNext;
        a.ringDurationMinutes = ringDurationMinutes;
        return a;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("hour", hour);
            o.put("minute", minute);
            o.put("days", days);
            o.put("enabled", enabled);
            o.put("label", label == null ? "" : label);
            o.put("ringType", ringType);
            o.put("bvid", bvid == null ? "" : bvid);
            o.put("ringTitle", ringTitle == null ? "" : ringTitle);
            o.put("ringPath", ringPath == null ? "" : ringPath);
            o.put("snooze", snoozeMinutes);
            o.put("vibrate", vibrate);
            o.put("loud", loud);
            o.put("skipNext", skipNext);
            o.put("ringDuration", ringDurationMinutes);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Alarm fromJson(JSONObject o) {
        Alarm a = new Alarm();
        a.id = o.optLong("id", a.id);
        a.hour = clamp(o.optInt("hour", 7), 0, 23);
        a.minute = clamp(o.optInt("minute", 30), 0, 59);
        a.days = o.optInt("days", 0) & 0x7F;
        a.enabled = o.optBoolean("enabled", true);
        a.label = o.optString("label", "");
        a.ringType = o.optInt("ringType", RING_DEFAULT);
        a.bvid = o.optString("bvid", "");
        a.ringTitle = o.optString("ringTitle", "");
        a.ringPath = o.optString("ringPath", "");
        a.snoozeMinutes = clamp(o.optInt("snooze", 5), 1, 60);
        a.vibrate = o.optBoolean("vibrate", true);
        a.loud = o.optBoolean("loud", true);
        a.skipNext = o.optBoolean("skipNext", false);
        a.ringDurationMinutes = clamp(o.optInt("ringDuration", 10), 1, 60);
        return a;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
