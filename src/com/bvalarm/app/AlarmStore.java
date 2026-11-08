package com.bvalarm.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Tiny JSON-backed alarm store. Using commit() on purpose: an alarm that was
 * saved but not flushed before the process dies is worse than a few ms of IO.
 */
public final class AlarmStore {

    private static final String PREFS = "bv_alarm_store";
    private static final String KEY_ALARMS = "alarms";
    private static final Object LOCK = new Object();

    private AlarmStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Alarm> load(Context context) {
        synchronized (LOCK) {
            List<Alarm> result = new ArrayList<>();
            String raw = prefs(context).getString(KEY_ALARMS, "");
            if (raw != null && raw.length() > 0) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) {
                            result.add(Alarm.fromJson(o));
                        }
                    }
                } catch (JSONException ignored) {
                }
            }
            Collections.sort(result, new Comparator<Alarm>() {
                @Override
                public int compare(Alarm a, Alarm b) {
                    int va = a.hour * 60 + a.minute;
                    int vb = b.hour * 60 + b.minute;
                    return Integer.compare(va, vb);
                }
            });
            return result;
        }
    }

    public static void save(Context context, List<Alarm> alarms) {
        synchronized (LOCK) {
            JSONArray arr = new JSONArray();
            for (Alarm a : alarms) {
                arr.put(a.toJson());
            }
            prefs(context).edit().putString(KEY_ALARMS, arr.toString()).commit();
        }
    }

    public static Alarm find(Context context, long id) {
        for (Alarm a : load(context)) {
            if (a.id == id) {
                return a;
            }
        }
        return null;
    }

    public static void upsert(Context context, Alarm alarm) {
        synchronized (LOCK) {
            List<Alarm> list = load(context);
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == alarm.id) {
                    list.set(i, alarm);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                list.add(alarm);
            }
            save(context, list);
        }
    }

    public static void remove(Context context, long id) {
        synchronized (LOCK) {
            List<Alarm> list = load(context);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == id) {
                    list.remove(i);
                    break;
                }
            }
            save(context, list);
        }
    }

    public static void setEnabled(Context context, long id, boolean enabled) {
        synchronized (LOCK) {
            List<Alarm> list = load(context);
            for (Alarm a : list) {
                if (a.id == id) {
                    a.enabled = enabled;
                    break;
                }
            }
            save(context, list);
        }
    }
}
