package com.bvalarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores alarms after reboot, app update, clock change or a timezone change.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        AlarmScheduler.rescheduleAll(context);
    }
}
