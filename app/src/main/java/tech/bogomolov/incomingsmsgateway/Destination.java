package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

// A reusable send target (Rocket.Chat recipient or SMS-relay phone number),
// managed on its own screen (DestinationsActivity) instead of being embedded in
// a forwarding rule. A rule (ForwardingConfig) refers to one or more of these
// by key (see ForwardingConfig.getDestinationIds()) and picks them via a
// multi-select in the rule's edit dialog, so the same destination can be reused
// across many rules without re-entering its details each time.
//
// Stored in its own SharedPreferences file (key_destinations_preference), kept
// separate from ForwardingConfig's file for the same reason HeartbeatSettings
// is separate: ForwardingConfig.getAll() would otherwise try to parse these
// entries as forwarding rules.
public class Destination {
    final private Context context;

    private static final String KEY_KEY = "key";
    private static final String KEY_NAME = "name";
    private static final String KEY_TYPE = "type";
    private static final String KEY_SERVER_URL = "serverUrl";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_TARGET = "target";
    private static final String KEY_PHONE_NUMBER = "phoneNumber";

    public static final String TYPE_ROCKETCHAT = "rocketchat";
    public static final String TYPE_SMS = "sms";

    private String key;
    private String name = "";
    private String type = TYPE_ROCKETCHAT;
    private String serverUrl = "";
    private String userId = "";
    private String token = "";
    private String target = "";
    private String phoneNumber = "";

    public Destination(Context context) {
        this.context = context;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getKey() {
        return this.key;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getName() {
        return this.name;
    }

    public void setType(String type) {
        this.type = TYPE_SMS.equals(type) ? TYPE_SMS : TYPE_ROCKETCHAT;
    }

    public String getType() {
        return this.type;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl == null ? "" : serverUrl;
    }

    public String getServerUrl() {
        return this.serverUrl;
    }

    public void setUserId(String userId) {
        this.userId = userId == null ? "" : userId;
    }

    public String getUserId() {
        return this.userId;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public String getToken() {
        return this.token;
    }

    public void setTarget(String target) {
        this.target = target == null ? "" : target;
    }

    public String getTarget() {
        return this.target;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber == null ? "" : phoneNumber;
    }

    public String getPhoneNumber() {
        return this.phoneNumber;
    }

    public JSONObject toJson() throws JSONException {
        if (this.getKey() == null) {
            this.setKey(this.generateKey());
        }

        JSONObject json = new JSONObject();
        json.put(KEY_KEY, this.getKey());
        json.put(KEY_NAME, this.name);
        json.put(KEY_TYPE, this.type);
        json.put(KEY_SERVER_URL, this.serverUrl);
        json.put(KEY_USER_ID, this.userId);
        json.put(KEY_TOKEN, this.token);
        json.put(KEY_TARGET, this.target);
        json.put(KEY_PHONE_NUMBER, this.phoneNumber);
        return json;
    }

    public void save() {
        try {
            JSONObject json = this.toJson();
            SharedPreferences.Editor editor = getEditor(context);
            editor.putString(this.getKey(), json.toString());
            editor.commit();
        } catch (JSONException e) {
            Log.e("Destination", e.getMessage());
        }
    }

    public void remove() {
        SharedPreferences.Editor editor = getEditor(context);
        editor.remove(this.getKey());
        editor.commit();
    }

    public static ArrayList<Destination> getAll(Context context) {
        SharedPreferences sharedPref = getPreference(context);
        Map<String, ?> sharedPrefs = sharedPref.getAll();

        ArrayList<Destination> destinations = new ArrayList<>();
        for (Map.Entry<String, ?> entry : sharedPrefs.entrySet()) {
            Destination destination = fromStoredValue(context, entry.getKey(), (String) entry.getValue());
            if (destination != null) {
                destinations.add(destination);
            }
        }
        return destinations;
    }

    // Looks up one destination by key, or null if it no longer exists (e.g. a
    // rule still references a destination the user has since deleted). Callers
    // must treat a null result as "skip this one", never crash over it.
    public static Destination findByKey(Context context, String key) {
        if (key == null) {
            return null;
        }
        SharedPreferences sharedPref = getPreference(context);
        String value = sharedPref.getString(key, null);
        if (value == null) {
            return null;
        }
        return fromStoredValue(context, key, value);
    }

    private static Destination fromStoredValue(Context context, String fallbackKey, String value) {
        Destination destination = new Destination(context);
        destination.setKey(fallbackKey);
        try {
            JSONObject json = new JSONObject(value);
            if (json.has(KEY_KEY)) {
                destination.setKey(json.getString(KEY_KEY));
            }
            if (json.has(KEY_NAME)) {
                destination.setName(json.getString(KEY_NAME));
            }
            if (json.has(KEY_TYPE)) {
                destination.setType(json.getString(KEY_TYPE));
            }
            if (json.has(KEY_SERVER_URL)) {
                destination.setServerUrl(json.getString(KEY_SERVER_URL));
            }
            if (json.has(KEY_USER_ID)) {
                destination.setUserId(json.getString(KEY_USER_ID));
            }
            if (json.has(KEY_TOKEN)) {
                destination.setToken(json.getString(KEY_TOKEN));
            }
            if (json.has(KEY_TARGET)) {
                destination.setTarget(json.getString(KEY_TARGET));
            }
            if (json.has(KEY_PHONE_NUMBER)) {
                destination.setPhoneNumber(json.getString(KEY_PHONE_NUMBER));
            }
        } catch (JSONException e) {
            Log.e("Destination", "skipping unreadable stored destination: " + e.getMessage());
            return null;
        }
        return destination;
    }

    // A short label for the multi-select list and the destinations list screen,
    // e.g. "Bank alerts (Rocket.Chat)" or "My phone (SMS)".
    public String getSummary(Context context) {
        String typeLabel = TYPE_SMS.equals(this.type)
                ? context.getString(R.string.label_type_sms)
                : context.getString(R.string.label_type_rocketchat);
        String label = this.name.isEmpty()
                ? (TYPE_SMS.equals(this.type) ? this.phoneNumber : this.target)
                : this.name;
        return label + " (" + typeLabel + ")";
    }

    private static SharedPreferences getPreference(Context context) {
        return context.getSharedPreferences(
                context.getString(R.string.key_destinations_preference),
                Context.MODE_PRIVATE
        );
    }

    private static SharedPreferences.Editor getEditor(Context context) {
        return getPreference(context).edit();
    }

    private String generateKey() {
        String stamp = Long.toString(System.currentTimeMillis());
        int randomNum = new Random().nextInt((999990 - 100000) + 1) + 100000;
        return stamp + '_' + randomNum;
    }
}
