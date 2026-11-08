package com.bvalarm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 10;

    private final List<Alarm> alarms = new ArrayList<>();
    private AlarmAdapter adapter;
    private TextView nextAlarmText;
    private TextView banner;
    private View emptyState;
    private ListView listView;
    private TextView clockTime;
    private TextView clockDate;
    private TextView aiTip;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer testPlayer;
    private final SimpleDateFormat clockTimeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat clockDateFmt = new SimpleDateFormat("M月d日 EEEE", Locale.CHINESE);

    /**
     * 时钟每分钟刷新一次（不显示秒，秒级刷新纯属浪费 CPU 与电量）。
     * 首帧对齐到下一个整分，之后每隔 60s 触发一次。
     */
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            long delay = 60_000L - (now % 60_000L);
            handler.postDelayed(this, delay);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = (ListView) findViewById(R.id.alarm_list);
        nextAlarmText = (TextView) findViewById(R.id.next_alarm);
        banner = (TextView) findViewById(R.id.permission_banner);
        emptyState = findViewById(R.id.empty_state);
        clockTime = (TextView) findViewById(R.id.clock_time);
        clockDate = (TextView) findViewById(R.id.clock_date);
        aiTip = (TextView) findViewById(R.id.ai_tip);

        adapter = new AlarmAdapter(this, alarms, new AlarmAdapter.Listener() {
            @Override
            public void onToggle(Alarm alarm, boolean enabled) {
                setAlarmEnabled(alarm, enabled);
            }
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openEditor(alarms.get(position).id);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showAlarmMenu(view, alarms.get(position));
                return true;
            }
        });

        findViewById(R.id.fab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(-1);
            }
        });
        findViewById(R.id.menu_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBannerClick();
            }
        });

        aiTip.setText(AiTips.random());
        runFirstLaunch();
    }

    /** 首次进入：弹窗说明所需权限，随后触发通知授权与 AI 提示。 */
    private void runFirstLaunch() {
        android.content.SharedPreferences sp = getSharedPreferences("bv_alarm_prefs",
                android.content.Context.MODE_PRIVATE);
        if (sp.getBoolean("permission_intro_seen", false)) {
            askForNotificationPermission();
            showAiTipOnFirstLaunch();
            return;
        }
        sp.edit().putBoolean("permission_intro_seen", true).apply();
        showPermissionIntroDialog();
    }

    /** 首次权限说明弹窗；关闭后展示 AI 提示弹窗。 */
    private void showPermissionIntroDialog() {
        final boolean needExact = exactAlarmMissing();
        final boolean needNotif = notificationMissing();

        StringBuilder msg = new StringBuilder();
        msg.append("为了闹钟能准时响起，需要以下权限：");
        msg.append("\n\n");
        msg.append("① 「闹钟和提醒」权限\n");
        msg.append("   Android 12 及以上需手动在系统设置中开启，否则闹钟可能延迟。");
        msg.append("\n\n");
        msg.append("② 「通知」权限\n");
        msg.append("   用于响铃时弹出全屏提醒。");

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("需要开启权限")
                .setMessage(msg.toString())
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (needExact) {
                            openExactAlarmSettings();
                        } else if (needNotif) {
                            askForNotificationPermission();
                        }
                    }
                })
                .setNegativeButton("稍后再说", null)
                .show();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                showAiTipOnFirstLaunch();
            }
        });
    }

    /** 第一次进入时弹窗提醒：本应用内置了 AI 提示语。 */
    private void showAiTipOnFirstLaunch() {
        android.content.SharedPreferences sp = getSharedPreferences("bv_alarm_prefs",
                android.content.Context.MODE_PRIVATE);
        if (sp.getBoolean("ai_tip_seen", false)) {
            return;
        }
        sp.edit().putBoolean("ai_tip_seen", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("AI 提示")
                .setMessage("本应用已加入 AI 提示语：\n\n主页会显示一句随机提示，"
                        + "响铃时也会附上一句，为你开启元气满满的一天。")
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmScheduler.rescheduleAll(this);
        reload();
        updateClock();
        handler.removeCallbacks(clockTick);
        handler.postDelayed(clockTick, 60_000L - (System.currentTimeMillis() % 60_000L));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        stopTestRing();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTestRing();
    }

    private void updateClock() {
        Date now = new Date();
        clockTime.setText(clockTimeFmt.format(now));
        clockDate.setText(clockDateFmt.format(now));
    }

    private void reload() {
        alarms.clear();
        alarms.addAll(AlarmStore.load(this));
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(alarms.isEmpty() ? View.VISIBLE : View.GONE);
        updateSummary();
        updateBanner();
    }

    private void updateSummary() {
        long next = AlarmScheduler.nextAlarmTime(this);
        if (next > 0) {
            nextAlarmText.setText("下一个闹钟 · " + Ui.describeTrigger(next));
        } else {
            nextAlarmText.setText(R.string.no_active_alarm);
        }
    }

    private void updateBanner() {
        boolean exact = exactAlarmMissing();
        boolean notifications = notificationMissing();
        if (!exact && !notifications) {
            banner.setVisibility(View.GONE);
            return;
        }
        StringBuilder message = new StringBuilder("⚠ ");
        if (exact && notifications) {
            message.append("未开启“闹钟和提醒”和通知权限，闹钟可能不响或延迟。");
        } else if (exact) {
            message.append("未开启“闹钟和提醒”权限，闹钟可能延迟。");
        } else {
            message.append("通知权限未开启，响铃时可能没有全屏提醒。");
        }
        message.append("点此开启 ›");
        banner.setText(message.toString());
        banner.setVisibility(View.VISIBLE);
    }

    private boolean exactAlarmMissing() {
        return Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canScheduleExact(this);
    }

    private boolean notificationMissing() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void onBannerClick() {
        if (exactAlarmMissing()) {
            openExactAlarmSettings();
        } else if (notificationMissing()) {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            Ui.toast(this, "无法打开通知设置");
        }
    }

    private void openEditor(long id) {
        Intent intent = new Intent(this, EditActivity.class);
        if (id != -1) {
            intent.putExtra(EditActivity.EXTRA_ID, id);
        }
        startActivity(intent);
    }

    private void setAlarmEnabled(Alarm alarm, boolean enabled) {
        alarm.enabled = enabled;
        AlarmStore.upsert(this, alarm);
        if (enabled) {
            AlarmScheduler.schedule(this, alarm);
        } else {
            AlarmScheduler.cancel(this, alarm.id);
        }
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    private void confirmDelete(final Alarm alarm) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_alarm_title)
                .setMessage(R.string.delete_alarm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AlarmStore.remove(MainActivity.this, alarm.id);
                        AlarmScheduler.cancel(MainActivity.this, alarm.id);
                        reload();
                        Ui.toast(MainActivity.this, "已删除");
                    }
                })
                .show();
    }

    private void showAlarmMenu(View anchor, final Alarm alarm) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "试响");
        popup.getMenu().add(0, 2, 1, alarm.skipNext ? "取消跳过下一次" : "跳过下一次");
        popup.getMenu().add(0, 3, 2, "编辑");
        popup.getMenu().add(0, 4, 3, "删除");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        testRing(alarm);
                        return true;
                    case 2:
                        skipNext(alarm);
                        return true;
                    case 3:
                        openEditor(alarm.id);
                        return true;
                    case 4:
                        confirmDelete(alarm);
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.show();
    }

    private void skipNext(Alarm alarm) {
        alarm.skipNext = !alarm.skipNext;
        AlarmStore.upsert(this, alarm);
        if (alarm.enabled) {
            AlarmScheduler.schedule(this, alarm);
        }
        reload();
        Ui.toast(this, alarm.skipNext ? "已跳过下一次响铃" : "已取消跳过");
    }

    private void testRing(Alarm alarm) {
        stopTestRing();
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            String source = null;
            if (alarm.hasBvRingtone()) {
                File f = new File(alarm.ringPath);
                if (f.exists()) {
                    source = alarm.ringPath;
                }
            }
            if (source == null) {
                android.net.Uri ringtone = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI;
                if (ringtone != null) {
                    mp.setDataSource(this, ringtone);
                } else {
                    mp.setAudioAttributes(null);
                    mp.setDataSource(this,
                            android.provider.Settings.System.DEFAULT_RINGTONE_URI);
                }
            } else {
                mp.setDataSource(source);
            }
            mp.setLooping(true);
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                    Ui.toast(MainActivity.this, "试响失败");
                    stopTestRing();
                    return true;
                }
            });
            mp.prepare();
            mp.start();
            testPlayer = mp;
            Ui.toast(this, "正在试响，再次长按可停止");
        } catch (Exception e) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            Ui.toast(this, "试响失败：" + e.getMessage());
        }
    }

    private void stopTestRing() {
        if (testPlayer != null) {
            try {
                testPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                testPlayer.release();
            } catch (Exception ignored) {
            }
            testPlayer = null;
        }
    }

    private void showAbout() {
        startActivity(new Intent(this, AboutActivity.class));
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "电池优化设置");
        popup.getMenu().add(0, 2, 1, "使用说明");
        popup.getMenu().add(0, 3, 2, "关于");
        popup.getMenu().add(0, 4, 3, ThemeManager.isDark(this) ? "浅色模式" : "深色模式");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        openBatterySettings();
                        return true;
                    case 2:
                        showHelp();
                        return true;
                    case 3:
                        showAbout();
                        return true;
                    case 4:
                        toggleTheme();
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.show();
    }

    private void toggleTheme() {
        boolean dark = ThemeManager.toggle(this);
        Ui.toast(this, dark ? "已切换为深色模式" : "已切换为浅色模式");
        recreate();
    }

    private void showHelp() {
        startActivity(new Intent(this, HelpActivity.class));
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Ui.toast(this, "无法打开系统设置");
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            Ui.toast(this, "无法打开电池优化设置");
        }
    }

    private void askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateBanner();
    }
}
