package com.bvalarm.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局浅色 / 深色主题切换。无 AppCompat，靠两套主题资源实现。
 */
public final class ThemeManager {

    private static final String PREFS = "bv_alarm_prefs";
    private static final String KEY_DARK = "dark_theme";

    private ThemeManager() {
    }

    /** 读取当前是否为深色主题（默认深色）。 */
    public static boolean isDark(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK, true);
    }

    /** 切换主题，返回切换后的值。 */
    public static boolean toggle(Context context) {
        boolean next = !isDark(context);
        setDark(context, next);
        return next;
    }

    public static void setDark(Context context, boolean dark) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_DARK, dark).apply();
    }

    /** 在 Activity.setContentView 之前调用，应用当前主题。 */
    public static void apply(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.AppTheme : R.style.AppTheme_Light);
    }
}
