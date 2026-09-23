package tech.bogomolov.incomingsmsgateway;

import org.json.JSONException;
import org.json.JSONObject;

// Builds the pieces of a Rocket.Chat chat.postMessage webhook call (endpoint,
// auth headers, JSON body) from the plain fields the "Destinations" dialog
// collects (server URL, User ID, personal access token, target channel/user).
// Shared by ForwardingConfigDialog (builds these once to store in a rule's
// destinations list) and SmsBroadcastReceiver (rebuilds the same shape at
// dispatch time), so the two can never drift apart.
final class RocketChatWebhook {

    private RocketChatWebhook() {
    }

    // Strips trailing slash(es) and, unless already pasted in full, points at
    // the chat.postMessage REST endpoint.
    static String buildEndpoint(String serverUrl) {
        String base = serverUrl.trim().replaceAll("/+$", "");
        return base.endsWith("/api/v1/chat.postMessage")
                ? base
                : base + "/api/v1/chat.postMessage";
    }

    // Personal Access Token auth (X-Auth-Token/X-User-Id), not a username/password
    // login — that would need a second network call before every forward.
    static JSONObject buildHeaders(String userId, String token) throws JSONException {
        JSONObject headers = new JSONObject();
        headers.put("X-Auth-Token", token);
        headers.put("X-User-Id", userId);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    // Message body format:
    //   پیامک جدید از طرف: <sender>
    //   ----------------------
    //   <message text>
    // Starts with a Persian word (not the earlier "NewSMS from:") so chat
    // clients detect right-to-left direction for the whole message instead of
    // rendering it left-to-right just because the first strong-direction
    // character was Latin.
    static JSONObject buildTemplate(String target) throws JSONException {
        JSONObject template = new JSONObject();
        template.put("channel", target);
        template.put("text", "پیامک جدید از طرف: %from%\n----------------------\n%text%");
        return template;
    }
}
