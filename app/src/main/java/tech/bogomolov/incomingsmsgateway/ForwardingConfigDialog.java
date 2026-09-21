package tech.bogomolov.incomingsmsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SubscriptionManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Objects;

public class ForwardingConfigDialog {

    static final public String BROADCAST_KEY = "TEST_RESULT";

    final private Context context;
    final private LayoutInflater layoutInflater;
    final private ListAdapter listAdapter;

    public ForwardingConfigDialog(Context context, LayoutInflater layoutInflater, ListAdapter listAdapter) {
        this.context = context;
        this.layoutInflater = layoutInflater;
        this.listAdapter = listAdapter;

        IntentFilter filter = new IntentFilter(BROADCAST_KEY);
        BroadcastReceiver testResult = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String result = intent.getStringExtra(BROADCAST_KEY);
                Toast.makeText(context.getApplicationContext(), result, Toast.LENGTH_LONG).show();
            }
        };
        context.registerReceiver(testResult, filter);
    }

    public void showNew() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = layoutInflater.inflate(R.layout.dialog_config_edit_form, null);

        final EditText templateInput = view.findViewById(R.id.input_json_template);
        templateInput.setText(ForwardingConfig.getDefaultJsonTemplate());

        final EditText headersInput = view.findViewById(R.id.input_json_headers);
        headersInput.setText(ForwardingConfig.getDefaultJsonHeaders());

        final EditText retriesNumInput = view.findViewById(R.id.input_number_retries);
        retriesNumInput.setText(String.valueOf(ForwardingConfig.getDefaultRetriesNumber()));

        final SwitchCompat chunkedModeCheckbox = view.findViewById(R.id.input_chunked_mode);
        // Default off: chunked request bodies (Transfer-Encoding: chunked, no Content-Length)
        // are valid HTTP but many webhook servers — notably common PHP setups — receive them
        // as an empty body (issue #97). Fixed-length mode sends Content-Length and works
        // everywhere for these small payloads.
        chunkedModeCheckbox.setChecked(false);

        final SwitchCompat signHmacSha256Checkbox = view.findViewById(R.id.id_sign_hmac_sha256);
        final EditText signHmacSha256Input = view.findViewById(R.id.id_sign_hmac_sha256_secret);

        signHmacSha256Checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            signHmacSha256Input.setEnabled(isChecked);
        });

        final EditText destinationsInput = view.findViewById(R.id.input_destinations);
        destinationsInput.setText("[]");

        prepareSimSelector(context, view, 0);
        setupAdvancedToggle(view, false);
        setupDestinations(view);

        builder.setView(view);
        builder.setPositiveButton(R.string.btn_add, null);
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.setNeutralButton(R.string.btn_test, null);

        final AlertDialog dialog = builder.show();
        Objects.requireNonNull(dialog.getWindow())
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view1 -> {
                    ForwardingConfig config = populateConfig(view, context, new ForwardingConfig(context));
                    if (config == null) {
                        return;
                    }
                    config.save();

                    listAdapter.add(config);
                    dialog.dismiss();
                });

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view1 -> {
                    ForwardingConfig config = populateConfig(view, context, new ForwardingConfig(context));
                    testConfig(config);
                });
    }

    public void showEdit(ForwardingConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = layoutInflater.inflate(R.layout.dialog_config_edit_form, null);

        final EditText phoneInput = view.findViewById(R.id.input_phone);
        phoneInput.setText(config.getSender());

        final EditText nameInput = view.findViewById(R.id.input_name);
        nameInput.setText(config.getName());

        final SwitchCompat senderRegexCheckbox = view.findViewById(R.id.input_sender_regex);
        senderRegexCheckbox.setChecked(config.getIsSenderRegex());

        final EditText smsFilterInput = view.findViewById(R.id.input_sms_filter);
        smsFilterInput.setText(config.getSmsFilter());

        final EditText urlInput = view.findViewById(R.id.input_url);
        urlInput.setText(config.getUrl());

        prepareSimSelector(context, view, config.getSimSlot());

        final EditText templateInput = view.findViewById(R.id.input_json_template);
        templateInput.setText(config.getTemplate());

        final EditText headersInput = view.findViewById(R.id.input_json_headers);
        headersInput.setText(config.getHeaders());

        final EditText retriesNumInput = view.findViewById(R.id.input_number_retries);
        retriesNumInput.setText(String.valueOf(config.getRetriesNumber()));

        final SwitchCompat ignoreSslCheckbox = view.findViewById(R.id.input_ignore_ssl);
        ignoreSslCheckbox.setChecked(config.getIgnoreSsl());

        final SwitchCompat chunkedModeCheckbox = view.findViewById(R.id.input_chunked_mode);
        chunkedModeCheckbox.setChecked(config.getChunkedMode());

        final SwitchCompat storeFailedCheckbox = view.findViewById(R.id.input_store_failed);
        storeFailedCheckbox.setChecked(config.getStoreFailed());

        final SwitchCompat localModeCheckbox = view.findViewById(R.id.input_local_mode);
        localModeCheckbox.setChecked(config.getLocalMode());

        final SwitchCompat signHmacSha256Checkbox = view.findViewById(R.id.id_sign_hmac_sha256);
        signHmacSha256Checkbox.setChecked(config.getSignHmacSha256());

        final EditText signHmacSha256Input = view.findViewById(R.id.id_sign_hmac_sha256_secret);
        String signHmacSha256Secret = config.getSignHmacSha256Secret();
        signHmacSha256Input.setText(signHmacSha256Secret == null ? "" : signHmacSha256Secret);
        signHmacSha256Input.setEnabled(signHmacSha256Checkbox.isChecked());

        signHmacSha256Checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            signHmacSha256Input.setEnabled(isChecked);
        });

        final EditText destinationsInput = view.findViewById(R.id.input_destinations);
        destinationsInput.setText(config.getDestinations());

        // Auto-expand advanced when editing a rule that already relies on it, so the
        // user doesn't have to hunt for settings they previously configured.
        setupAdvancedToggle(view, hasNonDefaultAdvanced(config));
        setupDestinations(view);
        updateDestinationsButtonLabel(view);

        builder.setView(view);
        builder.setPositiveButton(R.string.btn_save, null);
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.setNeutralButton(R.string.btn_test, null);

        final AlertDialog dialog = builder.show();
        Objects.requireNonNull(dialog.getWindow())
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view1 -> {
                    ForwardingConfig configUpdated = populateConfig(view, context, config);
                    if (configUpdated == null) {
                        return;
                    }
                    configUpdated.save();
                    listAdapter.notifyDataSetChanged();
                    dialog.dismiss();
                });

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view1 -> {
                    ForwardingConfig configUpdated = populateConfig(view, context, config);
                    testConfig(configUpdated);
                });
    }

    public ForwardingConfig populateConfig(View view, Context context, ForwardingConfig config) {
        final EditText senderInput = view.findViewById(R.id.input_phone);
        String sender = senderInput.getText().toString();
        if (TextUtils.isEmpty(sender)) {
            senderInput.setError(context.getString(R.string.error_empty_sender));
            return null;
        }

        final EditText nameInput = view.findViewById(R.id.input_name);
        String name = nameInput.getText().toString();

        final SwitchCompat senderRegexCheckbox = view.findViewById(R.id.input_sender_regex);
        boolean isSenderRegex = senderRegexCheckbox.isChecked();

        final EditText smsFilterInput = view.findViewById(R.id.input_sms_filter);
        String smsFilter = smsFilterInput.getText().toString();

        final EditText destinationsInput = view.findViewById(R.id.input_destinations);
        String destinationsJson = destinationsInput.getText().toString();
        boolean hasDestinations = destinationsArrayLength(destinationsJson) > 0;

        // "Destinations" (set via that button) replaces the need for a manually
        // configured webhook entirely, so the URL is only required when the rule
        // has none — a rule can rely purely on Destinations with url left blank.
        final EditText urlInput = view.findViewById(R.id.input_url);
        String url = urlInput.getText().toString();
        if (!hasDestinations) {
            if (TextUtils.isEmpty(url)) {
                urlInput.setError(context.getString(R.string.error_empty_url));
                return null;
            }
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                urlInput.setError(context.getString(R.string.error_wrong_url));
                return null;
            }
        }

        Spinner simSlotSelector = (Spinner) view.findViewById(R.id.input_sim_slot);
        int simSlot = (int) simSlotSelector.getSelectedItemId();
        config.setSimSlot(simSlot);

        final EditText templateInput = view.findViewById(R.id.input_json_template);
        String template = templateInput.getText().toString();
        try {
            new JSONObject(template);
        } catch (JSONException e) {
            templateInput.setError(context.getString(R.string.error_wrong_json));
            return null;
        }

        final EditText headersInput = view.findViewById(R.id.input_json_headers);
        String headers = headersInput.getText().toString();
        try {
            new JSONObject(headers);
        } catch (JSONException e) {
            headersInput.setError(context.getString(R.string.error_wrong_json));
            return null;
        }

        final EditText retriesNumInput = view.findViewById(R.id.input_number_retries);
        int retriesNum = Integer.parseInt(retriesNumInput.getText().toString());
        if (retriesNum < 0) {
            retriesNumInput.setError(context.getString(R.string.error_wrong_retries_number));
            return null;
        }

        final SwitchCompat ignoreSslCheckbox = view.findViewById(R.id.input_ignore_ssl);
        boolean ignoreSsl = ignoreSslCheckbox.isChecked();

        final SwitchCompat chunkedModeCheckbox = view.findViewById(R.id.input_chunked_mode);
        boolean chunkedMode = chunkedModeCheckbox.isChecked();

        final SwitchCompat storeFailedCheckbox = view.findViewById(R.id.input_store_failed);
        boolean storeFailed = storeFailedCheckbox.isChecked();

        final SwitchCompat localModeCheckbox = view.findViewById(R.id.input_local_mode);
        boolean localMode = localModeCheckbox.isChecked();

        final SwitchCompat signHmacSha256Checkbox = view.findViewById(R.id.id_sign_hmac_sha256);
        boolean signHmacSha256 = signHmacSha256Checkbox.isChecked();

        final EditText signHmacSha256Input = view.findViewById(R.id.id_sign_hmac_sha256_secret);
        String signHmacSha256Secret = signHmacSha256Input.getText().toString();
        // An empty secret can't produce a signature (SecretKeySpec rejects an
        // empty key), so block it here like the other invalid-form cases.
        if (signHmacSha256 && signHmacSha256Secret.isEmpty()) {
            signHmacSha256Input.setError(context.getString(R.string.error_empty_hmac_secret));
            return null;
        }

        config.setSender(sender);
        config.setName(name);
        config.setIsSenderRegex(isSenderRegex);
        config.setSmsFilter(smsFilter);
        config.setUrl(url);
        config.setTemplate(template);
        config.setHeaders(headers);
        config.setRetriesNumber(retriesNum);
        config.setIgnoreSsl(ignoreSsl);
        config.setChunkedMode(chunkedMode);
        config.setStoreFailed(storeFailed);
        config.setLocalMode(localMode);
        config.setSignHmacSha256(signHmacSha256);
        config.setSignHmacSha256Secret(signHmacSha256Secret);
        config.setDestinations(destinationsJson);

        return config;
    }

    // Parses a destinations JSON array string just far enough to get its length;
    // used only to decide whether the URL field is required (see populateConfig
    // and updateDestinationsButtonLabel). Treats anything unparseable as empty
    // rather than blocking save over it.
    private int destinationsArrayLength(String destinationsJson) {
        try {
            return new JSONArray(destinationsJson == null || destinationsJson.isEmpty() ? "[]" : destinationsJson).length();
        } catch (JSONException e) {
            return 0;
        }
    }

    // The advanced options live in a section collapsed by default so the basic
    // form is just sender + filter + URL. The bold header doubles as the toggle,
    // with a chevron showing the current state.
    private void setupAdvancedToggle(View view, boolean expanded) {
        final TextView header = view.findViewById(R.id.advanced_header);
        final View section = view.findViewById(R.id.advanced_section);

        section.setVisibility(expanded ? View.VISIBLE : View.GONE);
        updateAdvancedHeader(header, expanded);

        header.setOnClickListener(v -> {
            boolean nowVisible = section.getVisibility() != View.VISIBLE;
            section.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            updateAdvancedHeader(header, nowVisible);
        });
    }

    private void updateAdvancedHeader(TextView header, boolean expanded) {
        String arrow = expanded ? "▾  " : "▸  ";
        header.setText(arrow + context.getString(R.string.label_advanced));
    }

    // Wires the "Destinations" button: a sub-dialog where a rule can be pointed
    // at one shared Rocket.Chat account (server/User ID/personal access token)
    // plus any number of recipients under it, and/or any number of SMS-relay
    // phone numbers — all without the user ever touching the advanced
    // URL/headers/template fields. The result is stored as a JSON array in the
    // hidden input_destinations field (see ForwardingConfig.DEST_* constants),
    // read back by populateConfig() and consumed at dispatch time by
    // SmsBroadcastReceiver.
    private void setupDestinations(View formView) {
        View button = formView.findViewById(R.id.btn_destinations);
        button.setOnClickListener(v -> showDestinationsDialog(formView));
    }

    private void showDestinationsDialog(View formView) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = layoutInflater.inflate(R.layout.dialog_destinations, null);

        final EditText serverUrlInput = view.findViewById(R.id.input_rc_server_url);
        final EditText userIdInput = view.findViewById(R.id.input_rc_user_id);
        final EditText tokenInput = view.findViewById(R.id.input_rc_token);
        final ViewGroup rocketChatContainer = view.findViewById(R.id.rocketchat_targets_container);
        final ViewGroup smsContainer = view.findViewById(R.id.sms_targets_container);

        // Pre-populate from whatever is already stored on this rule (empty on a
        // brand-new rule, or when re-opening after a previous Apply).
        final EditText destinationsInput = formView.findViewById(R.id.input_destinations);
        try {
            JSONArray existing = new JSONArray(destinationsInput.getText().toString());
            for (int i = 0; i < existing.length(); i++) {
                JSONObject destination = existing.getJSONObject(i);
                String type = destination.optString(ForwardingConfig.DEST_TYPE, "");
                if (ForwardingConfig.DEST_TYPE_ROCKETCHAT.equals(type)) {
                    // All Rocket.Chat recipients on a rule share one account; the
                    // account fields only need filling in once, from any entry.
                    if (TextUtils.isEmpty(serverUrlInput.getText())) {
                        serverUrlInput.setText(destination.optString(ForwardingConfig.DEST_SERVER_URL));
                        userIdInput.setText(destination.optString(ForwardingConfig.DEST_USER_ID));
                        tokenInput.setText(destination.optString(ForwardingConfig.DEST_TOKEN));
                    }
                    addTargetRow(rocketChatContainer, destination.optString(ForwardingConfig.DEST_TARGET),
                            InputType.TYPE_CLASS_TEXT);
                } else if (ForwardingConfig.DEST_TYPE_SMS.equals(type)) {
                    addTargetRow(smsContainer, destination.optString(ForwardingConfig.DEST_PHONE_NUMBER),
                            InputType.TYPE_CLASS_PHONE);
                }
            }
        } catch (JSONException e) {
            Log.e("ForwardingConfigDialog", "invalid stored destinations, starting empty: " + e.getMessage());
        }

        view.findViewById(R.id.btn_add_rocketchat_target).setOnClickListener(
                v -> addTargetRow(rocketChatContainer, "", InputType.TYPE_CLASS_TEXT));
        view.findViewById(R.id.btn_add_sms_target).setOnClickListener(
                v -> addTargetRow(smsContainer, "", InputType.TYPE_CLASS_PHONE));

        builder.setTitle(R.string.title_destinations);
        builder.setView(view);
        builder.setPositiveButton(R.string.btn_apply, null);
        builder.setNegativeButton(R.string.btn_cancel, null);

        final AlertDialog dialog = builder.show();
        Objects.requireNonNull(dialog.getWindow())
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String serverUrl = serverUrlInput.getText().toString().trim();
            String userId = userIdInput.getText().toString().trim();
            String token = tokenInput.getText().toString().trim();
            ArrayList<String> targets = collectNonEmptyValues(rocketChatContainer);
            ArrayList<String> phoneNumbers = collectNonEmptyValues(smsContainer);

            if (!targets.isEmpty()) {
                if (TextUtils.isEmpty(serverUrl)) {
                    serverUrlInput.setError(context.getString(R.string.error_empty_rocketchat_server_url));
                    return;
                }
                try {
                    new URL(RocketChatWebhook.buildEndpoint(serverUrl));
                } catch (MalformedURLException e) {
                    serverUrlInput.setError(context.getString(R.string.error_wrong_rocketchat_server_url));
                    return;
                }
                if (TextUtils.isEmpty(userId)) {
                    userIdInput.setError(context.getString(R.string.error_empty_rocketchat_user_id));
                    return;
                }
                if (TextUtils.isEmpty(token)) {
                    tokenInput.setError(context.getString(R.string.error_empty_rocketchat_token));
                    return;
                }
            }

            try {
                JSONArray destinations = new JSONArray();
                for (String target : targets) {
                    JSONObject destination = new JSONObject();
                    destination.put(ForwardingConfig.DEST_TYPE, ForwardingConfig.DEST_TYPE_ROCKETCHAT);
                    destination.put(ForwardingConfig.DEST_SERVER_URL, serverUrl.replaceAll("/+$", ""));
                    destination.put(ForwardingConfig.DEST_USER_ID, userId);
                    destination.put(ForwardingConfig.DEST_TOKEN, token);
                    destination.put(ForwardingConfig.DEST_TARGET, target);
                    destinations.put(destination);
                }
                for (String phoneNumber : phoneNumbers) {
                    JSONObject destination = new JSONObject();
                    destination.put(ForwardingConfig.DEST_TYPE, ForwardingConfig.DEST_TYPE_SMS);
                    destination.put(ForwardingConfig.DEST_PHONE_NUMBER, phoneNumber);
                    destinations.put(destination);
                }

                destinationsInput.setText(destinations.toString());
                updateDestinationsButtonLabel(formView);
            } catch (JSONException e) {
                // Every value above is a plain string being put into a fresh
                // JSONObject; there is nothing here that can actually fail.
                return;
            }

            dialog.dismiss();
        });
    }

    // Appends one removable "target" row (an EditText plus an × button) to a
    // Rocket.Chat-recipients or SMS-numbers container.
    private void addTargetRow(ViewGroup container, String initialValue, int inputType) {
        View row = layoutInflater.inflate(R.layout.row_destination_target, container, false);
        EditText input = row.findViewById(R.id.target_input);
        input.setInputType(inputType);
        input.setHint(inputType == InputType.TYPE_CLASS_PHONE
                ? R.string.hint_sms_target
                : R.string.hint_rocketchat_target);
        if (initialValue != null) {
            input.setText(initialValue);
        }
        row.findViewById(R.id.remove_button).setOnClickListener(v -> container.removeView(row));
        container.addView(row);
    }

    private ArrayList<String> collectNonEmptyValues(ViewGroup container) {
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            EditText input = container.getChildAt(i).findViewById(R.id.target_input);
            String value = input.getText().toString().trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    // Shows a recipient count on the button itself once any destination is set,
    // since the JSON behind it is deliberately hidden from the user.
    private void updateDestinationsButtonLabel(View formView) {
        Button button = formView.findViewById(R.id.btn_destinations);
        EditText destinationsInput = formView.findViewById(R.id.input_destinations);
        int count = destinationsArrayLength(destinationsInput.getText().toString());
        button.setText(count > 0
                ? context.getString(R.string.btn_destinations_with_count, count)
                : context.getString(R.string.btn_destinations));
    }

    // True when the rule deviates from the defaults on any advanced field, so the
    // edit dialog knows to reveal the advanced section instead of hiding settings
    // the user already relies on.
    private boolean hasNonDefaultAdvanced(ForwardingConfig config) {
        return config.getSimSlot() != 0
                || !ForwardingConfig.getDefaultJsonTemplate().equals(config.getTemplate())
                || !ForwardingConfig.getDefaultJsonHeaders().equals(config.getHeaders())
                || config.getRetriesNumber() != ForwardingConfig.getDefaultRetriesNumber()
                || config.getIgnoreSsl()
                || config.getChunkedMode()
                || config.getStoreFailed()
                || config.getLocalMode()
                || config.getSignHmacSha256();
    }

    private void prepareSimSelector(Context context, View view, int selected) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
            SubscriptionManager subscriptionManager =
                    (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            int simSlots = subscriptionManager.getActiveSubscriptionInfoCountMax();
            if (simSlots > 1) {
                View label = view.findViewById(R.id.input_sim_slot_label);
                label.setVisibility(View.VISIBLE);

                Spinner simSlotSelector = (Spinner) view.findViewById(R.id.input_sim_slot);
                simSlotSelector.setVisibility(View.VISIBLE);

                String[] items = new String[simSlots + 1];
                items[0] = "any";
                for (int i = 1; i <= simSlots; i++) {
                    items[i] = "sim" + i;
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                        android.R.layout.simple_spinner_item, items);
                simSlotSelector.setAdapter(adapter);

                if (selected > simSlots || selected < 0) {
                    selected = 0;
                }

                simSlotSelector.setSelection(selected);
            }
        }
    }

    private void testConfig(ForwardingConfig config) {
        if (config == null) {
            return;
        }

        JSONArray destinations;
        try {
            destinations = config.getDestinationsArray();
        } catch (JSONException e) {
            destinations = new JSONArray();
        }

        if (destinations.length() > 0) {
            testDestinations(config, destinations);
            return;
        }

        Thread thread = new Thread(() -> {
            String payload = config.prepareMessage(
                    "123456789", "test message", "sim1", System.currentTimeMillis());

            Request request = new Request(config.getUrl(), payload);
            request.setJsonHeaders(config.getHeaders());
            // Guard against a null/empty secret (possible in a hand-edited backup
            // import) — an uncaught exception on this bare thread would kill the
            // whole app, not just the test request.
            String secret = config.getSignHmacSha256Secret();
            if (config.getSignHmacSha256() && secret != null && !secret.isEmpty()) {
                request.setSignatureHeader(secret, payload);
            }
            request.setIgnoreSsl(config.getIgnoreSsl());
            request.setUseChunkedMode(config.getChunkedMode());

            String result = request.execute();
            if (!Objects.equals(result, Request.RESULT_SUCCESS)) {
                result = Request.RESULT_ERROR;
            }

            Intent in = new Intent(BROADCAST_KEY);
            in.putExtra(BROADCAST_KEY, result);
            context.sendBroadcast(in);
        });
        thread.start();
    }

    // Tests every Rocket.Chat destination with a real HTTP call each (one Toast
    // per result). SMS destinations are skipped here rather than sending an
    // actual text message just to test the button.
    private void testDestinations(ForwardingConfig config, JSONArray destinations) {
        Thread thread = new Thread(() -> {
            boolean testedAny = false;
            for (int i = 0; i < destinations.length(); i++) {
                try {
                    JSONObject destination = destinations.getJSONObject(i);
                    if (!ForwardingConfig.DEST_TYPE_ROCKETCHAT.equals(
                            destination.optString(ForwardingConfig.DEST_TYPE, ""))) {
                        continue;
                    }
                    testedAny = true;

                    String endpoint = RocketChatWebhook.buildEndpoint(
                            destination.getString(ForwardingConfig.DEST_SERVER_URL));
                    String headers = RocketChatWebhook.buildHeaders(
                            destination.getString(ForwardingConfig.DEST_USER_ID),
                            destination.getString(ForwardingConfig.DEST_TOKEN)).toString();
                    String template = RocketChatWebhook.buildTemplate(
                            destination.getString(ForwardingConfig.DEST_TARGET)).toString();

                    String savedTemplate = config.getTemplate();
                    config.setTemplate(template);
                    String payload = config.prepareMessage(
                            "123456789", "test message", "sim1", System.currentTimeMillis());
                    config.setTemplate(savedTemplate);

                    Request request = new Request(endpoint, payload);
                    request.setJsonHeaders(headers);
                    String result = request.execute();
                    if (!Objects.equals(result, Request.RESULT_SUCCESS)) {
                        result = Request.RESULT_ERROR;
                    }

                    Intent in = new Intent(BROADCAST_KEY);
                    in.putExtra(BROADCAST_KEY, result);
                    context.sendBroadcast(in);
                } catch (JSONException e) {
                    Log.e("ForwardingConfigDialog", "invalid destination in test: " + e.getMessage());
                }
            }
            if (!testedAny) {
                Intent in = new Intent(BROADCAST_KEY);
                in.putExtra(BROADCAST_KEY, context.getString(R.string.test_sms_destinations_skipped));
                context.sendBroadcast(in);
            }
        });
        thread.start();
    }
}
