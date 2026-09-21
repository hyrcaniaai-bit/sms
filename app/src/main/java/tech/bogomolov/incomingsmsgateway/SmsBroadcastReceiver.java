package tech.bogomolov.incomingsmsgateway;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.work.Data;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class SmsBroadcastReceiver extends BroadcastReceiver {

    private Context context;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;

        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }

        StringBuilder content = new StringBuilder();
        final SmsMessage[] messages = new SmsMessage[pdus.length];
        for (int i = 0; i < pdus.length; i++) {
            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            content.append(messages[i].getDisplayMessageBody());
        }

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);
        String asterisk = context.getString(R.string.asterisk);

        String sender = messages[0].getOriginatingAddress();
        if (sender == null) {
            return;
        }

        for (ForwardingConfig config : configs) {
            if (!matchesSender(config, sender, asterisk)) {
                continue;
            }

            if (!config.getIsSmsEnabled()) {
                continue;
            }

            if (!matchesFilter(config.getSmsFilter(), content.toString())) {
                continue;
            }

            int slotId = this.detectSim(bundle) + 1;
            String slotName = "undetected";
            if (slotId < 0) {
                slotId = 0;
            }

            if (config.getSimSlot() > 0 && config.getSimSlot() != slotId) {
                continue;
            }

            if (slotId > 0) {
                slotName = "sim" + slotId;
            }

            long timeStamp = messages[0].getTimestampMillis();
            JSONArray destinationIds;
            try {
                destinationIds = config.getDestinationIdsArray();
            } catch (JSONException e) {
                Log.e("SmsBroadcastReceiver", "invalid destination ids JSON, falling back to the rule's own webhook: " + e.getMessage());
                destinationIds = new JSONArray();
            }

            if (destinationIds.length() > 0) {
                // "Destinations" replaces the rule's own URL/template/headers
                // entirely — a rule that has any destination selected is
                // dispatched only to those, not also to its (possibly stale or
                // never-filled-in) single webhook fields.
                this.dispatchDestinations(config, destinationIds, sender, slotName, content.toString(), timeStamp);
            } else {
                this.callWebHook(config, sender, slotName, content.toString(), timeStamp);
            }
        }
    }

    // Fans one incoming SMS out to every destination selected for this rule
    // (picked from the reusable Destinations list via the "Destinations" button
    // in the edit dialog): each Rocket.Chat destination becomes its own webhook
    // call (reusing callWebHook/RequestWorker, so it gets the same retry/failed-
    // message handling as a normal rule), and each SMS destination is relayed as
    // an actual outgoing text message. A destination the user has since deleted
    // from the Destinations screen is silently skipped.
    private void dispatchDestinations(ForwardingConfig config, JSONArray destinationIds,
                                       String sender, String slotName, String content, long timeStamp) {
        for (int i = 0; i < destinationIds.length(); i++) {
            String key;
            try {
                key = destinationIds.getString(i);
            } catch (JSONException e) {
                Log.e("SmsBroadcastReceiver", "invalid destination id entry #" + i + ": " + e.getMessage());
                continue;
            }

            Destination destination = Destination.findByKey(this.context, key);
            if (destination == null) {
                Log.e("SmsBroadcastReceiver", "destination " + key + " no longer exists, skipping");
                continue;
            }

            if (Destination.TYPE_ROCKETCHAT.equals(destination.getType())) {
                dispatchRocketChatDestination(config, destination, sender, slotName, content, timeStamp);
            } else if (Destination.TYPE_SMS.equals(destination.getType())) {
                dispatchSmsDestination(destination, content);
            } else {
                Log.e("SmsBroadcastReceiver", "unknown destination type: " + destination.getType());
            }
        }
    }

    // Momentarily points `config` at this one destination's webhook and reuses
    // callWebHook exactly as a normal rule would use it, then restores the
    // original fields. Safe because `config` is an in-memory instance from
    // ForwardingConfig.getAll() — nothing here is saved, and callWebHook reads
    // every field synchronously into the WorkManager Data before returning, so
    // there's no later read of the temporarily-swapped values.
    private void dispatchRocketChatDestination(ForwardingConfig config, Destination destination,
                                                String sender, String slotName, String content, long timeStamp) {
        try {
            String endpoint = RocketChatWebhook.buildEndpoint(destination.getServerUrl());
            String headers = RocketChatWebhook.buildHeaders(
                    destination.getUserId(), destination.getToken()).toString();
            String template = RocketChatWebhook.buildTemplate(destination.getTarget()).toString();

            String savedUrl = config.getUrl();
            String savedTemplate = config.getTemplate();
            String savedHeaders = config.getHeaders();
            boolean savedSignHmac = config.getSignHmacSha256();

            config.setUrl(endpoint);
            config.setTemplate(template);
            config.setHeaders(headers);
            // HMAC signing is a single-webhook, one-secret feature; a Rocket.Chat
            // destination authenticates with its own token instead.
            config.setSignHmacSha256(false);

            this.callWebHook(config, sender, slotName, content, timeStamp);

            config.setUrl(savedUrl);
            config.setTemplate(savedTemplate);
            config.setHeaders(savedHeaders);
            config.setSignHmacSha256(savedSignHmac);
        } catch (JSONException e) {
            Log.e("SmsBroadcastReceiver", "invalid Rocket.Chat destination: " + e.getMessage());
        }
    }

    // Relays the SMS body as an actual outgoing text message via SmsManager.
    // Requires SEND_SMS, requested best-effort in MainActivity; if it was denied
    // this destination just silently can't deliver, same as any other
    // permission-gated feature in this app — it never crashes the receiver.
    private void dispatchSmsDestination(Destination destination, String content) {
        String phoneNumber = destination.getPhoneNumber();
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this.context, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e("SmsBroadcastReceiver", "SEND_SMS not granted; cannot relay to " + phoneNumber);
            return;
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            List<String> parts = smsManager.divideMessage(content);
            smsManager.sendMultipartTextMessage(phoneNumber, null,
                    new ArrayList<>(parts), null, null);
        } catch (Exception e) {
            // SmsManager can throw for all sorts of carrier/radio reasons; never
            // let an SMS-relay destination crash the receiver over it.
            Log.e("SmsBroadcastReceiver", "SMS relay to " + phoneNumber + " failed: " + e.getMessage());
        }
    }

    protected void callWebHook(ForwardingConfig config, String sender, String slotName,
                               String content, long timeStamp) {

        String message = config.prepareMessage(sender, content, slotName, timeStamp);

        Data data = new Data.Builder()
                .putString(RequestWorker.DATA_URL, config.getUrl())
                .putString(RequestWorker.DATA_TEXT, message)
                .putString(RequestWorker.DATA_HEADERS, config.getHeaders())
                .putBoolean(RequestWorker.DATA_IGNORE_SSL, config.getIgnoreSsl())
                .putBoolean(RequestWorker.DATA_CHUNKED_MODE, config.getChunkedMode())
                .putInt(RequestWorker.DATA_MAX_RETRIES, config.getRetriesNumber())
                .putBoolean(RequestWorker.DATA_SIGN_HMAC_SHA256, config.getSignHmacSha256())
                .putString(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET, config.getSignHmacSha256Secret())
                .putBoolean(RequestWorker.DATA_STORE_FAILED, config.getStoreFailed())
                .putBoolean(RequestWorker.DATA_LOCAL_MODE, config.getLocalMode())
                .build();

        RequestWorker.enqueue(this.context, data);
    }

    // Per-config sender match. The asterisk wildcard always means "any sender"
    // regardless of the regex flag. When the rule opts into regex matching (issue
    // #88 — e.g. an Indian sender ID like AB-CTAXKR whose operator prefix rotates),
    // the configured sender is a Java regex tested against the incoming address with
    // find() (substring), mirroring the content filter. Unlike the content filter
    // this fails *closed*: an invalid pattern matches nothing, so a typo cannot leak
    // unrelated senders to the endpoint. The default (flag off) is the historic
    // exact String.equals match, so every existing stored rule is unchanged.
    static boolean matchesSender(ForwardingConfig config, String sender, String asterisk) {
        String configured = config.getSender();
        if (configured.equals(asterisk)) {
            return true;
        }
        if (config.getIsSenderRegex()) {
            try {
                return Pattern.compile(configured).matcher(sender).find();
            } catch (PatternSyntaxException e) {
                Log.e("SmsBroadcastReceiver",
                        "Invalid sender regex \"" + configured + "\": " + e.getMessage());
                return false;
            }
        }
        return sender.equals(configured);
    }

    // Per-config content filter (issue #52). An empty filter forwards every
    // message (the historic behaviour). A non-empty filter is a Java regex tested
    // against the SMS body with find() (substring match): the message is forwarded
    // only when the regex matches. The single regex covers both directions —
    // "OTP" forwards messages that contain OTP, while a negative-lookahead such as
    // "(?s)^(?!.*OTP)" forwards every message that does NOT contain it. An invalid
    // pattern fails open (forwards and logs) so a typo never silently drops SMS,
    // mirroring the "never crash forwarding" rule used by the %Regex% placeholder.
    static boolean matchesFilter(String filter, String content) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        try {
            return Pattern.compile(filter).matcher(content).find();
        } catch (PatternSyntaxException e) {
            Log.e("SmsBroadcastReceiver", "Invalid filter regex \"" + filter + "\": " + e.getMessage());
            return true;
        }
    }

    private int detectSim(Bundle bundle) {
        int slotId = -1;
        Set<String> keySet = bundle.keySet();
        for (String key : keySet) {
            switch (key) {
                case "phone":
                    slotId = bundle.getInt("phone", -1);
                    break;
                case "slot":
                    slotId = bundle.getInt("slot", -1);
                    break;
                case "simId":
                    slotId = bundle.getInt("simId", -1);
                    break;
                case "simSlot":
                    slotId = bundle.getInt("simSlot", -1);
                    break;
                case "slot_id":
                    slotId = bundle.getInt("slot_id", -1);
                    break;
                case "simnum":
                    slotId = bundle.getInt("simnum", -1);
                    break;
                case "slotId":
                    slotId = bundle.getInt("slotId", -1);
                    break;
                case "slotIdx":
                    slotId = bundle.getInt("slotIdx", -1);
                    break;
                case "android.telephony.extra.SLOT_INDEX":
                    slotId = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
                    break;
                default:
                    if (key.toLowerCase().contains("slot") | key.toLowerCase().contains("sim")) {
                        String value = bundle.getString(key, "-1");
                        if (value.equals("0") | value.equals("1") | value.equals("2")) {
                            slotId = bundle.getInt(key, -1);
                        }
                    }
            }

            if (slotId != -1) {
                break;
            }
        }

        return slotId;
    }
}
