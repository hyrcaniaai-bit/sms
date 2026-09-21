package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;

public class ListAdapter extends ArrayAdapter<ForwardingConfig> {
    final private ArrayList<ForwardingConfig> dataSet;
    Context context;

    public ListAdapter(ArrayList<ForwardingConfig> data, Context context) {
        super(context, R.layout.list_item, data);
        this.dataSet = data;
        this.context = context;
    }

    @Override
    public int getCount() {
        return this.dataSet.size();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View row = convertView;
        if (null == convertView) {
            row = inflater.inflate(R.layout.list_item, parent, false);
        }

        ForwardingConfig config = getItem(position);

        String senderText = config.getSender();
        String asterisk = context.getString(R.string.asterisk);
        String any = context.getString(R.string.any);
        String displaySender = senderText.equals(asterisk) ? any : senderText;
        // The optional "name" field is meant to replace the raw sender in the
        // list once set (e.g. "Bank X" instead of the sender ID); rules that
        // never set one just keep showing the sender, unchanged from before.
        String name = config.getName();
        TextView sender = row.findViewById(R.id.text_sender);
        sender.setText((name == null || name.isEmpty()) ? displaySender : name);

        // A rule using "Destinations" instead of a manual webhook has an empty
        // (or stale) url, which would otherwise show as a blank second line.
        TextView url = row.findViewById(R.id.text_url);
        int destinationsCount = 0;
        try {
            destinationsCount = config.getDestinationsArray().length();
        } catch (org.json.JSONException ignored) {
        }
        url.setText(destinationsCount > 0
                ? context.getString(R.string.list_item_destinations_summary, destinationsCount)
                : config.getUrl());

        SwitchCompat switchSmsOnOff = row.findViewById(R.id.switch_sms_on_off);
        // Detach any listener a recycled row carries before syncing the state,
        // so setChecked doesn't save the previous row's config.
        switchSmsOnOff.setOnCheckedChangeListener(null);
        switchSmsOnOff.setChecked(config.getIsSmsEnabled());

        switchSmsOnOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setIsSmsEnabled(isChecked);
            config.save();
        });

        View editButton = row.findViewById(R.id.edit_button);
        editButton.setTag(R.id.edit_button, position);
        editButton.setOnClickListener(this::onEditClick);

        View deleteButton = row.findViewById(R.id.delete_button);
        deleteButton.setTag(R.id.delete_button, position);
        deleteButton.setOnClickListener(this::onDeleteClick);

        View backupButton = row.findViewById(R.id.backup_button);
        // ACTION_CREATE_DOCUMENT (Storage Access Framework) needs API 19+; below
        // that there's no activity to resolve it, so hide the button instead of
        // offering something that would crash on tap — same call SettingsActivity
        // already makes for the bulk export/import section.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            backupButton.setVisibility(View.GONE);
        } else {
            backupButton.setVisibility(View.VISIBLE);
            backupButton.setTag(R.id.backup_button, position);
            backupButton.setOnClickListener(this::onBackupClick);
        }

        return row;
    }

    // Exports just this one rule via MainActivity (which owns the
    // startActivityForResult/onActivityResult SAF flow — the classic API, not
    // the newer Activity Result API, matching SettingsActivity's bulk export).
    public void onBackupClick(View view) {
        final int position = (int) view.getTag(R.id.backup_button);
        final ForwardingConfig config = getItem(position);
        ((MainActivity) context).exportSingleConfig(config);
    }

    public void onEditClick(View view) {
        ListAdapter listAdapter = this;
        final int position = (int) view.getTag(R.id.edit_button);
        final ForwardingConfig config = listAdapter.getItem(position);
        (new ForwardingConfigDialog(
                context,
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE),
                listAdapter
        )).showEdit(config);
    }

    public void onDeleteClick(View view) {
        ListAdapter listAdapter = this;
        final int position = (int) view.getTag(R.id.delete_button);
        final ForwardingConfig config = listAdapter.getItem(position);

        AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
        builder.setTitle(R.string.delete_record);
        String asterisk = context.getString(R.string.asterisk);
        String any = context.getString(R.string.any);
        String message = context.getString(R.string.confirm_delete);
        message = String.format(message, (config.getSender().equals(asterisk) ? any : config.getSender()));
        builder.setMessage(message);

        builder.setPositiveButton(R.string.btn_delete, (dialog, id) -> {
            listAdapter.remove(config);
            config.remove();
        });
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.show();
    }
}
