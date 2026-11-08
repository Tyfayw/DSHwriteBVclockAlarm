package com.bvalarm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditActivity extends Activity {

    public static final String EXTRA_ID = "alarm_id";

    private static final int[] DAY_VIEW_IDS = {
            R.id.day_1, R.id.day_2, R.id.day_3, R.id.day_4,
            R.id.day_5, R.id.day_6, R.id.day_7
    };
    private static final int[] SNOOZE_VIEW_IDS = {
            R.id.snooze_5, R.id.snooze_10, R.id.snooze_15, R.id.snooze_20, R.id.snooze_30
    };
    private static final int[] SNOOZE_VALUES = {5, 10, 15, 20, 30};
    private static final int[] DURATION_VIEW_IDS = {
            R.id.duration_1, R.id.duration_5, R.id.duration_10, R.id.duration_30, R.id.duration_60
    };
    private static final int[] DURATION_VALUES = {1, 5, 10, 30, 60};

    private long alarmId = -1;
    private Alarm original;

    private int hour = 7;
    private int minute = 30;
    private int days = 0;
    private int snooze = 5;
    private int ringDuration = 10;
    private int ringType = Alarm.RING_DEFAULT;
    private String bvid = "";
    private String ringTitle = "";
    private String ringPath = "";
    private boolean vibrate = true;

    private boolean bvPanelOpen = false;
    private boolean downloading = false;
    private final AtomicBoolean cancelDownload = new AtomicBoolean(false);

    private EditText labelInput;
    private EditText bvInput;
    private TextView timeText;
    private TextView ringCurrent;
    private TextView ringSub;
    private TextView ringBadge;
    private TextView bvStatus;
    private TextView bvTitle;
    private TextView bvMeta;
    private TextView btnResolve;
    private TextView btnPreview;
    private ProgressBar bvProgress;
    private View bvPanel;
    private View bvResult;
    private CheckBox vibrateCheck;
    private CheckBox loudCheck;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private MediaPlayer preview;
    private LoudnessEnhancer previewEnhancer;
    private boolean warnedLowMediaVolume;

    private final Runnable stopPreviewTask = new Runnable() {
        @Override
        public void run() {
            stopPreview();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        io = Executors.newSingleThreadExecutor();
        alarmId = getIntent().getLongExtra(EXTRA_ID, -1);
        if (alarmId != -1) {
            original = AlarmStore.find(this, alarmId);
        }
        if (original != null) {
            hour = original.hour;
            minute = original.minute;
            days = original.days;
            snooze = original.snoozeMinutes;
            vibrate = original.vibrate;
            ringDuration = original.ringDurationMinutes;
            ringType = original.ringType;
            bvid = original.bvid;
            ringTitle = original.ringTitle;
            ringPath = original.ringPath;
            bvPanelOpen = original.hasBvRingtone();
            if (original.hasBvRingtone() && !new File(original.ringPath).exists()) {
                ringType = Alarm.RING_DEFAULT;
                ringPath = "";
                Ui.toast(this, "原来的 B站 铃声文件丢失了，请重新下载");
            }
        } else {
            alarmId = -1;
        }

        labelInput = (EditText) findViewById(R.id.label_input);
        bvInput = (EditText) findViewById(R.id.bv_input);
        timeText = (TextView) findViewById(R.id.time_text);
        ringCurrent = (TextView) findViewById(R.id.ring_current);
        ringSub = (TextView) findViewById(R.id.ring_sub);
        ringBadge = (TextView) findViewById(R.id.ring_badge);
        bvStatus = (TextView) findViewById(R.id.bv_status);
        bvTitle = (TextView) findViewById(R.id.bv_title);
        bvMeta = (TextView) findViewById(R.id.bv_meta);
        btnResolve = (TextView) findViewById(R.id.btn_resolve);
        btnPreview = (TextView) findViewById(R.id.btn_preview);
        bvProgress = (ProgressBar) findViewById(R.id.bv_progress);
        bvPanel = findViewById(R.id.bv_panel);
        bvResult = findViewById(R.id.bv_result);
        vibrateCheck = (CheckBox) findViewById(R.id.vibrate_check);
        loudCheck = (CheckBox) findViewById(R.id.loud_check);

        TextView title = (TextView) findViewById(R.id.title);
        title.setText(original == null ? R.string.new_alarm : R.string.edit_alarm);

        TextView delete = (TextView) findViewById(R.id.btn_delete);
        delete.setVisibility(original == null ? View.GONE : View.VISIBLE);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.time_card).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTimePicker();
            }
        });

        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            findViewById(DAY_VIEW_IDS[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    days ^= bit;
                    updateDayChips();
                }
            });
        }
        findViewById(R.id.preset_everyday).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                days = 0x7F;
                updateDayChips();
            }
        });
        findViewById(R.id.preset_workday).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                days = 0x1F;
                updateDayChips();
            }
        });
        findViewById(R.id.preset_weekend).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                days = 0x60;
                updateDayChips();
            }
        });

        for (int i = 0; i < SNOOZE_VALUES.length; i++) {
            final int value = SNOOZE_VALUES[i];
            findViewById(SNOOZE_VIEW_IDS[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    snooze = value;
                    updateSnoozeChips();
                }
            });
        }

        for (int i = 0; i < DURATION_VALUES.length; i++) {
            final int value = DURATION_VALUES[i];
            findViewById(DURATION_VIEW_IDS[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ringDuration = value;
                    updateDurationChips();
                }
            });
        }

        findViewById(R.id.btn_default_ring).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ringType = Alarm.RING_DEFAULT;
                bvPanelOpen = false;
                updateRingUi();
            }
        });
        findViewById(R.id.btn_bv).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bvPanelOpen = true;
                if (ringPath != null && ringPath.length() > 0) {
                    ringType = Alarm.RING_BV;
                }
                updateRingUi();
            }
        });

        btnResolve.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startResolve();
            }
        });
        btnPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePreview();
            }
        });

        vibrateCheck.setChecked(vibrate);
        loudCheck.setChecked(original == null || original.loud);
        if (original != null) {
            labelInput.setText(original.label);
        }
        if (!bvid.isEmpty()) {
            bvInput.setText(bvid);
            bvTitle.setText(ringTitle.isEmpty() ? bvid : ringTitle);
            bvMeta.setText(bvid);
        }

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        updateAll();
    }

    private void updateAll() {
        updateTime();
        updateDayChips();
        updateSnoozeChips();
        updateDurationChips();
        updateRingUi();
    }

    private void updateTime() {
        timeText.setText(String.format(java.util.Locale.US, "%02d:%02d", hour, minute));
    }

    private void updateDayChips() {
        for (int i = 0; i < 7; i++) {
            TextView chip = (TextView) findViewById(DAY_VIEW_IDS[i]);
            boolean on = (days & (1 << i)) != 0;
            chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip.setTextColor(getColor(on ? R.color.accent : R.color.text_secondary));
        }
    }

    private void updateSnoozeChips() {
        for (int i = 0; i < SNOOZE_VALUES.length; i++) {
            TextView chip = (TextView) findViewById(SNOOZE_VIEW_IDS[i]);
            boolean on = snooze == SNOOZE_VALUES[i];
            chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip.setTextColor(getColor(on ? R.color.accent : R.color.text_secondary));
        }
    }

    private void updateDurationChips() {
        for (int i = 0; i < DURATION_VALUES.length; i++) {
            TextView chip = (TextView) findViewById(DURATION_VIEW_IDS[i]);
            boolean on = ringDuration == DURATION_VALUES[i];
            chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip.setTextColor(getColor(on ? R.color.accent : R.color.text_secondary));
        }
    }

    private void updateRingUi() {
        boolean bv = ringType == Alarm.RING_BV && ringPath != null && ringPath.length() > 0;
        if (bv) {
            ringCurrent.setText(ringTitle == null || ringTitle.isEmpty() ? bvid : ringTitle);
            ringBadge.setText("B站");
            ringBadge.setTextColor(getColor(R.color.accent));
            ringSub.setVisibility(View.VISIBLE);
            ringSub.setText(bvid + (ringPath.endsWith(".mp4") ? " · 备用音轨" : ""));
            btnPreview.setEnabled(true);
            btnPreview.setAlpha(1f);
        } else {
            ringCurrent.setText(R.string.default_ringtone);
            ringBadge.setText("系统");
            ringBadge.setTextColor(getColor(R.color.text_tertiary));
            ringSub.setVisibility(View.GONE);
            btnPreview.setEnabled(false);
            btnPreview.setAlpha(0.4f);
        }
        boolean showPanel = bvPanelOpen || bv;
        bvPanel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        bvResult.setVisibility(bv && !bvid.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openTimePicker() {
        new android.app.TimePickerDialog(this,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker view, int h, int m) {
                        hour = h;
                        minute = m;
                        updateTime();
                    }
                }, hour, minute, true)
                .show();
    }

    private void startResolve() {
        if (downloading) {
            return;
        }
        final String input = bvInput.getText().toString().trim();
        if (input.isEmpty()) {
            Ui.toast(this, "请先输入 BV 号或视频链接");
            return;
        }
        downloading = true;
        cancelDownload.set(false);
        stopPreview();
        bvPanelOpen = true;
        bvPanel.setVisibility(View.VISIBLE);
        btnResolve.setEnabled(false);
        btnResolve.setAlpha(0.5f);
        bvProgress.setVisibility(View.VISIBLE);
        bvProgress.setProgress(0);
        bvStatus.setVisibility(View.VISIBLE);
        bvStatus.setTextColor(getColor(R.color.text_secondary));
        bvStatus.setText("正在解析视频信息…");

        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final BiliApi.Track track = BiliApi.resolve(input);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            bvStatus.setText("已找到《" + track.title + "》，开始下载…");
                        }
                    });
                    File file = RingtoneDownloader.download(EditActivity.this, track,
                            new RingtoneDownloader.Listener() {
                                @Override
                                public void onStage(final String stage) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!isFinishing()) {
                                                bvStatus.setText(stage);
                                            }
                                        }
                                    });
                                }

                                @Override
                                public void onProgress(final int percent, final long downloaded,
                                                        final long total) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (isFinishing()) {
                                                return;
                                            }
                                            if (percent >= 0) {
                                                bvProgress.setProgress(percent);
                                            }
                                            String size = Ui.formatSize(downloaded)
                                                    + (total > 0 ? " / " + Ui.formatSize(total) : "");
                                            bvStatus.setText("下载中 " + (percent >= 0 ? percent + "%" : "")
                                                    + "  " + size);
                                        }
                                    });
                                }
                            }, cancelDownload);
                    final File result = file;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onResolveSuccess(track, result);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onResolveFailure(e);
                        }
                    });
                }
            }
        });
    }

    private void onResolveSuccess(BiliApi.Track track, File file) {
        if (isFinishing()) {
            return;
        }
        downloading = false;
        btnResolve.setEnabled(true);
        btnResolve.setAlpha(1f);
        bvProgress.setVisibility(View.GONE);
        bvStatus.setTextColor(getColor(R.color.success));
        bvStatus.setText("✓ 已下载完成，保存后这个铃声就会生效");

        bvid = track.bvid;
        ringTitle = track.title;
        ringPath = file.getAbsolutePath();
        ringType = Alarm.RING_BV;
        bvPanelOpen = true;

        bvInput.setText(bvid);
        bvTitle.setText(track.title.isEmpty() ? bvid : track.title);
        StringBuilder meta = new StringBuilder();
        if (!track.owner.isEmpty()) {
            meta.append(track.owner).append(" · ");
        }
        meta.append(track.durationText()).append(" · ").append(Ui.formatSize(file.length()));
        if (track.pageCount > 1) {
            meta.append(" · P").append(track.pageIndex);
        }
        bvMeta.setText(meta.toString());
        updateRingUi();
    }

    private void onResolveFailure(Exception e) {
        if (isFinishing()) {
            return;
        }
        downloading = false;
        btnResolve.setEnabled(true);
        btnResolve.setAlpha(1f);
        bvProgress.setVisibility(View.GONE);
        bvStatus.setVisibility(View.VISIBLE);
        bvStatus.setTextColor(getColor(R.color.danger));
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = "解析失败，请稍后重试";
        }
        bvStatus.setText(message + "\n\n可以换一个 BV 号重试；闹钟仍可使用系统默认铃声。");
    }

    private void togglePreview() {
        if (preview != null) {
            stopPreview();
            return;
        }
        if (ringPath == null || ringPath.length() == 0) {
            Ui.toast(this, "还没有可试听的铃声");
            return;
        }
        warnIfMediaVolumeLow();
        final MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(ringPath);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    if (preview != mediaPlayer) {
                        mediaPlayer.release();
                        return;
                    }
                    if (loudCheck.isChecked()) {
                        attachPreviewLoudness(mediaPlayer);
                    }
                    mediaPlayer.start();
                    btnPreview.setText("■");
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    stopPreview();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                    Ui.toast(EditActivity.this, "这个音频无法试听");
                    stopPreview();
                    return true;
                }
            });
            mp.prepareAsync();
            preview = mp;
            handler.postDelayed(stopPreviewTask, 30000);
        } catch (Exception e) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            Ui.toast(this, "试听失败：" + e.getMessage());
        }
    }

    private void stopPreview() {
        handler.removeCallbacks(stopPreviewTask);
        releasePreviewLoudness();
        MediaPlayer mp = preview;
        preview = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
        if (btnPreview != null) {
            btnPreview.setText("▶");
        }
    }

    private void warnIfMediaVolumeLow() {
        if (warnedLowMediaVolume) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max > 0 && current * 2 < max) {
                warnedLowMediaVolume = true;
                Ui.toast(this, "媒体音量较低，试听会偏小；实际响铃会自动调大");
            }
        } catch (Exception ignored) {
        }
    }

    private void attachPreviewLoudness(MediaPlayer mp) {
        releasePreviewLoudness();
        try {
            LoudnessEnhancer enhancer = new LoudnessEnhancer(mp.getAudioSessionId());
            enhancer.setTargetGain(600);
            enhancer.setEnabled(true);
            previewEnhancer = enhancer;
        } catch (Exception ignored) {
            // Some devices/ROMs do not expose the loudness enhancer.
        }
    }

    private void releasePreviewLoudness() {
        if (previewEnhancer != null) {
            try {
                previewEnhancer.release();
            } catch (Exception ignored) {
            }
            previewEnhancer = null;
        }
    }

    private void save() {
        if (downloading) {
            Ui.toast(this, "铃声还在下载，请稍候");
            return;
        }
        if (ringType == Alarm.RING_BV) {
            if (ringPath == null || ringPath.length() == 0 || !new File(ringPath).exists()) {
                Ui.toast(this, "请先解析并下载 B站 铃声");
                bvPanelOpen = true;
                updateRingUi();
                return;
            }
        }
        String label = labelInput.getText().toString().trim();
        Alarm alarm = original != null ? original : new Alarm();
        alarm.hour = hour;
        alarm.minute = minute;
        alarm.days = days;
        alarm.label = label;
        alarm.ringType = ringType;
        alarm.bvid = bvid;
        alarm.ringTitle = ringTitle;
        alarm.ringPath = ringPath;
        alarm.snoozeMinutes = snooze;
        alarm.ringDurationMinutes = ringDuration;
        alarm.vibrate = vibrate;
        alarm.loud = loudCheck.isChecked();
        alarm.enabled = true;

        AlarmStore.upsert(this, alarm);
        AlarmScheduler.schedule(this, alarm);

        if (!AlarmScheduler.canScheduleExact(this)) {
            Ui.toast(this, "已保存。开启“闹钟和提醒”权限后才会准时响铃");
        } else {
            Ui.toast(this, "已保存 · " + Ui.describeTrigger(
                    alarm.nextTrigger(System.currentTimeMillis())));
        }
        finish();
    }

    private void confirmDelete() {
        if (original == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_alarm_title)
                .setMessage(R.string.delete_alarm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AlarmStore.remove(EditActivity.this, original.id);
                        AlarmScheduler.cancel(EditActivity.this, original.id);
                        finish();
                    }
                })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        cancelDownload.set(true);
        if (io != null) {
            io.shutdownNow();
        }
        handler.removeCallbacksAndMessages(null);
        stopPreview();
        super.onDestroy();
    }
}
