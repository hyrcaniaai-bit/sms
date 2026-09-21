package tech.bogomolov.incomingsmsgateway;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private Context context;
    private ListAdapter listAdapter;

    private static final int PERMISSION_CODE = 0;

    // Single-rule backup export (per-row "Backup" button), reusing the same
    // Storage Access Framework flow as the bulk export in SettingsActivity.
    private static final int REQUEST_EXPORT_SINGLE = 10;
    private static final String BACKUP_MIME_TYPE = "application/json";
    private ForwardingConfig pendingExportConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ArrayList<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS);
        }
        // Android 13+ gates the foreground-service "F" indicator behind a runtime
        // permission. Without it the service still runs and forwards SMS, but the
        // persistent notification never shows (issue #77). Treated as best-effort:
        // we ask for it, but its denial does not block the app like RECEIVE_SMS does.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Only needed for a rule's "Destinations" SMS-relay option. Best-effort like
        // POST_NOTIFICATIONS above: denial doesn't block the app, that one rule's SMS
        // destination just won't deliver until it's granted (SmsBroadcastReceiver
        // checks again before every send).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.SEND_SMS);
        }

        if (permissions.isEmpty()) {
            showList();
        } else {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != PERMISSION_CODE) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (!permissions[i].equals(Manifest.permission.RECEIVE_SMS)) {
                continue;
            }

            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                showList();
            } else {
                showInfo(getResources().getString(R.string.permission_needed));
            }

            return;
        }

        // RECEIVE_SMS wasn't part of this result (it was already granted, and only
        // the best-effort POST_NOTIFICATIONS was requested), so proceed as long as
        // RECEIVE_SMS is in fact still granted. Re-checking also handles the
        // empty-array case Android delivers when the dialog is cancelled.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            showList();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Failures accrue in the background, so refresh the retry counter each
        // time the activity comes forward.
        invalidateOptionsMenu();
        // Rules can also change outside this screen (Settings -> import a backup),
        // so reload the list. Null until the permission flow has let showList() run.
        if (listAdapter != null) {
            listAdapter.clear();
            listAdapter.addAll(ForwardingConfig.getAll(this));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_bar_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem retryItem = menu.findItem(R.id.action_bar_retry_failed);
        int count = FailedMessage.getCount(this);
        retryItem.setVisible(count > 0);
        if (count > 0) {
            retryItem.setTitle(getString(R.string.menu_retry_failed, count));
        }

        // One mass switch for every rule's per-row on/off toggle. Label flips
        // between "turn off all" and "turn on all" depending on whether any rule
        // is currently enabled; hidden entirely with no rules to act on.
        MenuItem toggleAllItem = menu.findItem(R.id.action_bar_toggle_all);
        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(this);
        toggleAllItem.setVisible(!configs.isEmpty());
        if (!configs.isEmpty()) {
            boolean anyEnabled = false;
            for (ForwardingConfig config : configs) {
                if (config.getIsSmsEnabled()) {
                    anyEnabled = true;
                    break;
                }
            }
            toggleAllItem.setTitle(anyEnabled
                    ? getString(R.string.menu_turn_off_all)
                    : getString(R.string.menu_turn_on_all));
        }

        // Whole-app kill switch (AppKillSwitch) — a harder, separate stop from
        // the per-rule toggles above. Tinted red while off so it's obvious at a
        // glance that nothing is being forwarded right now.
        MenuItem powerItem = menu.findItem(R.id.action_bar_power);
        boolean appEnabled = AppKillSwitch.isEnabled(this);
        powerItem.setTitle(appEnabled ? getString(R.string.menu_turn_off_app) : getString(R.string.menu_turn_on_app));
        Drawable icon = powerItem.getIcon();
        if (icon != null) {
            icon.mutate();
            if (appEnabled) {
                icon.clearColorFilter();
            } else {
                icon.setColorFilter(ContextCompat.getColor(this, R.color.colorDanger), PorterDuff.Mode.SRC_IN);
            }
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_bar_menu) {
            showHamburgerMenu();
            return true;
        }

        if (id == R.id.action_bar_power) {
            toggleAppEnabled();
            return true;
        }

        if (id == R.id.action_bar_retry_failed) {
            int count = FailedMessage.getCount(this);
            FailedMessage.retryAll(this);
            Toast.makeText(this, getString(R.string.retry_failed_toast, count), Toast.LENGTH_LONG).show();
            invalidateOptionsMenu();
            return true;
        }

        if (id == R.id.action_bar_toggle_all) {
            ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(this);
            boolean anyEnabled = false;
            for (ForwardingConfig config : configs) {
                if (config.getIsSmsEnabled()) {
                    anyEnabled = true;
                    break;
                }
            }
            // Blunt toggle: turning off sets every rule off, turning back on sets
            // every rule back on — it does not remember which rules were already
            // individually disabled beforehand.
            boolean newState = !anyEnabled;
            for (ForwardingConfig config : configs) {
                config.setIsSmsEnabled(newState);
                config.save();
            }
            listAdapter.clear();
            listAdapter.addAll(configs);
            invalidateOptionsMenu();
            Toast.makeText(this, newState ? R.string.toast_turned_on_all : R.string.toast_turned_off_all,
                    Toast.LENGTH_SHORT).show();
            return true;
        }

        if (id == R.id.action_bar_syslogs) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View view = getLayoutInflater().inflate(R.layout.syslogs, null);

            String logs = "";
            try {
                String[] command = new String[]{
                        "logcat", "-d", "*:E", "-m", "1000",
                        "|", "grep", "tech.bogomolov.incomingsmsgateway"};
                Process process = Runtime.getRuntime().exec(command);

                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    logs += line + "\n";
                }
            } catch (IOException ex) {
                logs = "getLog failed";
            }

            TextView logsTextContainer = view.findViewById(R.id.syslogs_text);
            logsTextContainer.setText(logs);

            TextView version = view.findViewById(R.id.syslogs_version);
            version.setText("v" + BuildConfig.VERSION_NAME);

            builder.setView(view);
            builder.setNegativeButton(R.string.btn_close, null);
            builder.setNeutralButton(R.string.btn_clear, null);

            final AlertDialog dialog = builder.show();
            Objects.requireNonNull(dialog.getWindow())
                    .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view1 -> {
                        String[] command = new String[]{"logcat", "-c"};
                        try {
                            Runtime.getRuntime().exec(command);
                        } catch (IOException e) {
                            Log.e("SmsGateway", "log clear error: " + e);
                        }
                        dialog.cancel();
                    });
        }

        return super.onOptionsItemSelected(item);
    }

    // The hamburger action-bar icon opens a plain two-row picker rather than a
    // navigation drawer, which would be a lot more moving parts (a DrawerLayout,
    // a NavigationView, syncing its selected state) for exactly two destinations.
    private void showHamburgerMenu() {
        CharSequence[] items = {getString(R.string.menu_settings), getString(R.string.menu_destinations)};
        new AlertDialog.Builder(this)
                .setItems(items, (dialog, which) -> {
                    Class<?> target = which == 0 ? SettingsActivity.class : DestinationsActivity.class;
                    startActivity(new Intent(this, target));
                })
                .show();
    }

    // A hard whole-app stop, independent from every rule's own on/off switch and
    // from "turn off all" (both of which only flip stored isSmsEnabled flags,
    // leaving the app itself running). Turning off asks for confirmation since
    // it silently stops all SMS handling until turned back on; turning back on
    // is non-destructive so it doesn't need one.
    private void toggleAppEnabled() {
        if (AppKillSwitch.isEnabled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.turn_off_app_title)
                    .setMessage(R.string.turn_off_app_message)
                    .setPositiveButton(R.string.menu_turn_off_app, (dialog, which) -> {
                        AppKillSwitch.setEnabled(this, false);
                        stopSmsService();
                        invalidateOptionsMenu();
                        Toast.makeText(this, R.string.toast_app_turned_off, Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        } else {
            AppKillSwitch.setEnabled(this, true);
            startService();
            invalidateOptionsMenu();
            Toast.makeText(this, R.string.toast_app_turned_on, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSmsService() {
        getApplicationContext().stopService(new Intent(this, SmsReceiverService.class));
    }

    private void showList() {
        context = this;
        ListView listview = findViewById(R.id.listView);

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);

        // First-run / empty state: point the user at the + button instead of
        // leaving a blank screen.
        showInfo(configs.isEmpty() ? getString(R.string.empty_list_hint) : "");

        listAdapter = new ListAdapter(configs, context);
        listview.setAdapter(listAdapter);

        // Keep the empty-state hint in sync as rules are added or deleted.
        listAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                showInfo(listAdapter.getCount() == 0 ? getString(R.string.empty_list_hint) : "");
            }
        });

        FloatingActionButton fab = findViewById(R.id.btn_add);
        fab.setOnClickListener(this.showAddDialog());

        // Respect the whole-app kill switch: don't resurrect the service just
        // because the screen was reopened while switched off.
        if (AppKillSwitch.isEnabled(this) && !this.isServiceRunning()) {
            this.startService();
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (tech.bogomolov.incomingsmsgateway.SmsReceiverService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void startService() {
        Context appContext = getApplicationContext();
        Intent intent = new Intent(this, SmsReceiverService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    private void showInfo(String text) {
        TextView notice = findViewById(R.id.info_notice);
        notice.setText(text);
        // The icon above the notice should disappear together with the text.
        findViewById(R.id.empty_state).setVisibility(
                text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View.OnClickListener showAddDialog() {
        return v -> {
            (new ForwardingConfigDialog(context, getLayoutInflater(), listAdapter)).showNew();
        };
    }

    // Per-row "Backup" button (ListAdapter): exports just this one rule, using the
    // same Storage Access Framework flow and file shape (a one-element JSON array)
    // as the bulk export in SettingsActivity, so the result can be imported back
    // through the same "Import" button there.
    public void exportSingleConfig(ForwardingConfig config) {
        pendingExportConfig = config;
        String sender = config.getSender();
        String asterisk = getString(R.string.asterisk);
        String label = (sender == null || sender.equals(asterisk)) ? getString(R.string.any) : sender;
        String fileName = "sms-gateway-rule-" + label.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BACKUP_MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(intent, REQUEST_EXPORT_SINGLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_SINGLE || resultCode != RESULT_OK
                || data == null || data.getData() == null || pendingExportConfig == null) {
            return;
        }
        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            JSONArray array = new JSONArray();
            array.put(pendingExportConfig.toJson());
            output.write(array.toString(2).getBytes(Charset.forName("UTF-8")));
            Toast.makeText(this, getString(R.string.backup_export_success, 1), Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            Log.e("MainActivity", "single rule export failed: " + e);
            Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_LONG).show();
        } finally {
            pendingExportConfig = null;
        }
    }
}
