package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Objects;

// Manages the reusable "Destinations" list (Rocket.Chat recipients and SMS-relay
// numbers) that forwarding rules pick from via multi-select in their own edit
// dialog, instead of each rule carrying its own full copy of this setup.
public class DestinationsActivity extends AppCompatActivity {

    private Context context;
    private DestinationsListAdapter listAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_destinations);
        context = this;

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        ListView listView = findViewById(R.id.listView);
        ArrayList<Destination> destinations = Destination.getAll(context);
        showInfo(destinations.isEmpty());

        listAdapter = new DestinationsListAdapter(destinations, context);
        listView.setAdapter(listAdapter);

        listAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                showInfo(listAdapter.getCount() == 0);
            }
        });

        FloatingActionButton fab = findViewById(R.id.btn_add);
        fab.setOnClickListener(v -> showAddDialog());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showInfo(boolean empty) {
        findViewById(R.id.empty_state).setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showAddDialog() {
        showDialog(null);
    }

    public void showEditDialog(Destination destination) {
        showDialog(destination);
    }

    private void showDialog(Destination existing) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_destination_edit_form, null);

        final EditText nameInput = view.findViewById(R.id.input_destination_name);
        final RadioGroup typeGroup = view.findViewById(R.id.input_destination_type);
        final RadioButton typeRocketChat = view.findViewById(R.id.type_rocketchat);
        final RadioButton typeSms = view.findViewById(R.id.type_sms);
        final ViewGroup rocketChatFields = view.findViewById(R.id.rocketchat_fields);
        final ViewGroup smsFields = view.findViewById(R.id.sms_fields);
        final EditText serverUrlInput = view.findViewById(R.id.input_rc_server_url);
        final EditText userIdInput = view.findViewById(R.id.input_rc_user_id);
        final EditText tokenInput = view.findViewById(R.id.input_rc_token);
        final EditText targetInput = view.findViewById(R.id.input_rc_target);
        final EditText phoneNumberInput = view.findViewById(R.id.input_sms_phone_number);

        typeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isSms = checkedId == R.id.type_sms;
            rocketChatFields.setVisibility(isSms ? View.GONE : View.VISIBLE);
            smsFields.setVisibility(isSms ? View.VISIBLE : View.GONE);
        });

        if (existing != null) {
            nameInput.setText(existing.getName());
            if (Destination.TYPE_SMS.equals(existing.getType())) {
                typeSms.setChecked(true);
            } else {
                typeRocketChat.setChecked(true);
            }
            serverUrlInput.setText(existing.getServerUrl());
            userIdInput.setText(existing.getUserId());
            tokenInput.setText(existing.getToken());
            targetInput.setText(existing.getTarget());
            phoneNumberInput.setText(existing.getPhoneNumber());
        }
        // Sync initial field visibility with whichever radio ended up checked
        // above (defaults to Rocket.Chat when adding new).
        boolean startsAsSms = typeSms.isChecked();
        rocketChatFields.setVisibility(startsAsSms ? View.GONE : View.VISIBLE);
        smsFields.setVisibility(startsAsSms ? View.VISIBLE : View.GONE);

        builder.setTitle(existing == null ? R.string.title_add_destination : R.string.title_edit_destination);
        builder.setView(view);
        builder.setPositiveButton(existing == null ? R.string.btn_add : R.string.btn_save, null);
        builder.setNegativeButton(R.string.btn_cancel, null);

        final AlertDialog dialog = builder.show();
        Objects.requireNonNull(dialog.getWindow())
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                nameInput.setError(getString(R.string.error_empty_destination_name));
                return;
            }

            boolean isSms = typeGroup.getCheckedRadioButtonId() == R.id.type_sms;
            Destination destination = existing == null ? new Destination(context) : existing;
            destination.setName(name);
            destination.setType(isSms ? Destination.TYPE_SMS : Destination.TYPE_ROCKETCHAT);

            if (isSms) {
                String phoneNumber = phoneNumberInput.getText().toString().trim();
                if (TextUtils.isEmpty(phoneNumber)) {
                    phoneNumberInput.setError(getString(R.string.error_empty_sms_phone_number));
                    return;
                }
                destination.setPhoneNumber(phoneNumber);
            } else {
                String serverUrl = serverUrlInput.getText().toString().trim();
                String userId = userIdInput.getText().toString().trim();
                String token = tokenInput.getText().toString().trim();
                String target = targetInput.getText().toString().trim();

                if (TextUtils.isEmpty(serverUrl)) {
                    serverUrlInput.setError(getString(R.string.error_empty_rocketchat_server_url));
                    return;
                }
                try {
                    new URL(RocketChatWebhook.buildEndpoint(serverUrl));
                } catch (MalformedURLException e) {
                    serverUrlInput.setError(getString(R.string.error_wrong_rocketchat_server_url));
                    return;
                }
                if (TextUtils.isEmpty(userId)) {
                    userIdInput.setError(getString(R.string.error_empty_rocketchat_user_id));
                    return;
                }
                if (TextUtils.isEmpty(token)) {
                    tokenInput.setError(getString(R.string.error_empty_rocketchat_token));
                    return;
                }
                if (TextUtils.isEmpty(target)) {
                    targetInput.setError(getString(R.string.error_empty_rocketchat_target));
                    return;
                }

                destination.setServerUrl(serverUrl.replaceAll("/+$", ""));
                destination.setUserId(userId);
                destination.setToken(token);
                destination.setTarget(target);
            }

            destination.save();
            if (existing == null) {
                listAdapter.add(destination);
            } else {
                listAdapter.notifyDataSetChanged();
            }
            dialog.dismiss();
        });
    }

    public void confirmDelete(Destination destination, DestinationsListAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.delete_record);
        builder.setMessage(String.format(getString(R.string.confirm_delete), destination.getSummary(context)));
        builder.setPositiveButton(R.string.btn_delete, (dialog, id) -> {
            adapter.remove(destination);
            destination.remove();
        });
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.show();
    }
}
