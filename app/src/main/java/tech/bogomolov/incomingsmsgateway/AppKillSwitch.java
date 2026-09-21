package tech.bogomolov.incomingsmsgateway;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

// A hard, whole-app on/off switch — separate from any per-rule enabled toggle
// and from the "turn off all" action, which only flip ForwardingConfig's
// isSmsEnabled flags. This instead disables the manifest-declared
// SmsBroadcastReceiver component itself, so the system never delivers
// SMS_RECEIVED to this app at all, not even a cold start after the process has
// been killed (the whole reason that receiver is manifest-declared in the
// first place — see the manifest's own comment). MainActivity additionally
// stops the foreground service when switching off, and skips starting it again
// while switched off (see the isEnabled() checks in MainActivity/
// SettingsActivity), so the heartbeat stops too.
//
// Nothing here touches SharedPreferences: every ForwardingConfig, Destination
// and HeartbeatSettings entry is exactly as it was when switched back on.
final class AppKillSwitch {

    private AppKillSwitch() {
    }

    static boolean isEnabled(Context context) {
        int state = context.getPackageManager().getComponentEnabledSetting(receiver(context));
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(
                receiver(context),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private static ComponentName receiver(Context context) {
        return new ComponentName(context, SmsBroadcastReceiver.class);
    }
}
