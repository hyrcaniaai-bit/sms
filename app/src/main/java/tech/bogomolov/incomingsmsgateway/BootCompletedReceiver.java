package tech.bogomolov.incomingsmsgateway;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootCompletedReceiver extends BroadcastReceiver {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent argIntent) {
        // Without this check, a reboot would silently undo the whole-app kill
        // switch (AppKillSwitch) by starting the service back up regardless.
        if (!AppKillSwitch.isEnabled(context)) {
            return;
        }
        Intent intent = new Intent(context, SmsReceiverService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
