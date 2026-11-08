package com.bvalarm.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Full screen ringing UI. Shown over the lock screen via showWhenLocked /
 * full-screen intent. Buttons forward commands to {@link RingService}.
 */
public class RingActivity extends Activity {

    private boolean receiverRegistered;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        setContentView(R.layout.activity_ring);

        if (!RingService.isRinging()) {
            // Stale notification / restored task: there is nothing to ring.
            finish();
            return;
        }

        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendToService(RingService.ACTION_STOP);
                finish();
            }
        });
        findViewById(R.id.btn_snooze).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendToService(RingService.ACTION_SNOOZE);
                Ui.toast(RingActivity.this, "稍后提醒");
                finish();
            }
        });

        IntentFilter filter = new IntentFilter(RingService.ACTION_RING_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopReceiver, filter);
        }
        receiverRegistered = true;

        requestKeyguardDismiss();
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!RingService.isRinging()) {
            finish();
            return;
        }
        bind(intent);
    }

    private void bind(Intent intent) {
        long alarmId = intent == null ? -1 : intent.getLongExtra(RingService.EXTRA_ID, -1);
        Alarm alarm = alarmId == -1 ? null : AlarmStore.find(this, alarmId);
        if (alarm == null) {
            finish();
            return;
        }
        TextView time = (TextView) findViewById(R.id.ring_time);
        TextView label = (TextView) findViewById(R.id.ring_label);
        TextView source = (TextView) findViewById(R.id.ring_source);
        TextView aiTip = (TextView) findViewById(R.id.ring_ai_tip);
        TextView snooze = (TextView) findViewById(R.id.btn_snooze);
        TextView hint = (TextView) findViewById(R.id.ring_hint);

        time.setText(alarm.timeText());
        if (alarm.label != null && alarm.label.length() > 0) {
            label.setText(alarm.label);
            label.setVisibility(View.VISIBLE);
        } else {
            label.setVisibility(View.GONE);
        }
        if (alarm.hasBvRingtone()) {
            String title = alarm.ringTitle == null || alarm.ringTitle.isEmpty()
                    ? alarm.bvid : alarm.ringTitle;
            source.setText("B站铃声 · " + title);
        } else {
            source.setText("系统默认铃声");
        }
        snooze.setText("稍后\n" + Math.max(1, alarm.snoozeMinutes) + " 分钟");
        hint.setText("闹钟会在 " + Math.max(1, alarm.ringDurationMinutes) + " 分钟后自动停止");
        aiTip.setText("✨ " + AiTips.random());
    }

    private void requestKeyguardDismiss() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, null);
            }
        } catch (Exception ignored) {
        }
    }

    private void sendToService(String action) {
        Intent intent = new Intent(this, RingService.class);
        intent.setAction(action);
        try {
            startService(intent);
        } catch (Exception e) {
            try {
                stopService(intent);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        Ui.toast(this, "请选择“停止”或“稍后提醒”");
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stopReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
