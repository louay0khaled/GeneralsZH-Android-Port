/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
*/

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shared GeneralsOnline session store + HTTP auth calls.
 *
 * Sessions are stored independently for each supported server. The selected
 * server is also mirrored into a tiny native-readable marker so the C++ game
 * engine uses the same backend as the browser login that just completed.
 */
final class GeneralsOnlineSession {

    private static final String TAG = "GeneralsOnlineSession";

    static final String PREFS_NAME = GeneralsOnlineServer.PREFS_NAME;
    static final String PREF_SESSION_TOKEN = "session_token";
    static final String PREF_REFRESH_TOKEN = "refresh_token";
    static final String PREF_USER_ID = "user_id";
    static final String PREF_DISPLAY_NAME = "display_name";
    static final String PREF_WS_URI = "ws_uri";

    static final String SESSION_MARKER_NAME = "generalsonline_session.txt";
    static final String SERVER_MARKER_NAME = "generalsonline_server.txt";

    static class AuthResult {
        int state = -1;
        String sessionToken = "";
        String refreshToken = "";
        long userId = -1;
        String displayName = "";
        String wsUri = "";
    }

    static volatile String lastNetworkErrorDetail = "";

    private GeneralsOnlineSession() {
    }

    private static String prefKey(String serverId, String baseKey) {
        return serverId + "_" + baseKey;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Read a server-scoped value. Older Build #11 installs stored GeneralsX
     * credentials under the old unscoped key names; migrate those lazily so
     * an update does not force a fresh Discord/browser login.
     */
    private static String getString(Context ctx, String serverId, String baseKey) {
        SharedPreferences p = prefs(ctx);
        String scoped = p.getString(prefKey(serverId, baseKey), null);
        if (scoped != null) {
            return scoped;
        }

        if (GeneralsOnlineServer.GENERALSX.equals(serverId)) {
            String legacy = p.getString(baseKey, null);
            if (legacy != null) {
                p.edit().putString(prefKey(serverId, baseKey), legacy).apply();
                return legacy;
            }
        }
        return null;
    }

    private static long getLong(Context ctx, String serverId, String baseKey, long defaultValue) {
        SharedPreferences p = prefs(ctx);
        if (p.contains(prefKey(serverId, baseKey))) {
            return p.getLong(prefKey(serverId, baseKey), defaultValue);
        }

        if (GeneralsOnlineServer.GENERALSX.equals(serverId) && p.contains(baseKey)) {
            long legacy = p.getLong(baseKey, defaultValue);
            p.edit().putLong(prefKey(serverId, baseKey), legacy).apply();
            return legacy;
        }
        return defaultValue;
    }

    // Runs on a background thread.
    static AuthResult postJson(String serverId, String endpoint, JSONObject body, String bearerToken) {
        String primary = GeneralsOnlineServer.apiBase(serverId);
        String alternate = alternateApiBase(serverId);

        StringBuilder errors = new StringBuilder();
        AuthResult result = postJsonOnce(primary, endpoint, body, bearerToken, errors);
        if (result != null) {
            lastNetworkErrorDetail = "";
            return result;
        }

        // The original service historically exposed an alternate Russian API
        // hostname. GeneralsX currently has one API hostname, so do not issue
        // a duplicate request there.
        if (alternate != null && !alternate.equals(primary)) {
            Log.w(TAG, "primary API endpoint (" + primary + ") failed; retrying via alternate endpoint");
            result = postJsonOnce(alternate, endpoint, body, bearerToken, errors);
        }

        lastNetworkErrorDetail = errors.toString().trim();
        if (result != null) {
            lastNetworkErrorDetail = "";
        } else {
            Log.w(TAG, "API request failed for server=" + serverId + ", endpoint=" + endpoint + ": " + lastNetworkErrorDetail);
        }
        return result;
    }

    private static String alternateApiBase(String serverId) {
        if (GeneralsOnlineServer.PLAYGENERALS.equals(serverId)) {
            return "https://api-ru.playgenerals.online/env/prod/contract/1/";
        }
        return null;
    }

    private static AuthResult postJsonOnce(
            String base,
            String endpoint,
            JSONObject body,
            String bearerToken,
            StringBuilder errorOut) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                errorOut.append(hostOf(base)).append(": HTTP ").append(status);
                String snippet = readSnippet(conn.getErrorStream());
                if (!snippet.isEmpty()) {
                    errorOut.append(" ").append(snippet);
                }
                errorOut.append("; ");
                return null;
            }

            java.io.InputStream in = conn.getInputStream();
            if (in == null) {
                errorOut.append(hostOf(base)).append(": empty response body; ");
                return null;
            }

            JSONObject json = new JSONObject(readAll(in));
            AuthResult result = new AuthResult();
            result.state = json.optInt("result", -1);
            result.sessionToken = json.optString("session_token", "");
            result.refreshToken = json.optString("refresh_token", "");
            result.userId = json.optLong("user_id", -1);
            result.displayName = json.optString("display_name", "");
            result.wsUri = json.optString("ws_uri", "");
            return result;
        } catch (Exception e) {
            errorOut.append(hostOf(base)).append(": ").append(e.getClass().getSimpleName());
            if (e.getMessage() != null) {
                errorOut.append(": ").append(e.getMessage());
            }
            errorOut.append("; ");
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String hostOf(String base) {
        try {
            return new URL(base).getHost();
        } catch (Exception e) {
            return base;
        }
    }

    private static String readSnippet(java.io.InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            String body = readAll(in).replaceAll("\\s+", " ").trim();
            if (body.length() > 200) {
                body = body.substring(0, 200) + "...";
            }
            return body;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    static AuthResult loginWithToken(String serverId, String refreshToken) {
        JSONObject body = new JSONObject();
        try {
            body.put("reserved_0", "");
            body.put("reserved_1", "");
            body.put("reserved_2", "");
        } catch (Exception e) {
            return null;
        }
        return postJson(serverId, "LoginWithToken", body, refreshToken);
    }

    static void saveSession(Context ctx, String serverId, AuthResult result) {
        prefs(ctx).edit()
            .putString(prefKey(serverId, PREF_SESSION_TOKEN), result.sessionToken)
            .putString(prefKey(serverId, PREF_REFRESH_TOKEN), result.refreshToken)
            .putLong(prefKey(serverId, PREF_USER_ID), result.userId)
            .putString(prefKey(serverId, PREF_DISPLAY_NAME), result.displayName)
            .putString(prefKey(serverId, PREF_WS_URI), result.wsUri)
            .apply();

        writeSelectedServerMarker(ctx, serverId);

        File marker = new File(ctx.getFilesDir(), SESSION_MARKER_NAME);
        try (FileWriter w = new FileWriter(marker, false)) {
            w.write("server_id=" + serverId + "\n");
            w.write("session_token=" + result.sessionToken + "\n");
            w.write("user_id=" + result.userId + "\n");
            w.write("display_name=" + result.displayName + "\n");
            w.write("ws_uri=" + result.wsUri + "\n");
        } catch (IOException e) {
            Log.w(TAG, "could not write native session marker", e);
        }
    }

    static void writeSelectedServerMarker(Context ctx, String serverId) {
        File marker = new File(ctx.getFilesDir(), SERVER_MARKER_NAME);
        try (FileWriter w = new FileWriter(marker, false)) {
            w.write(serverId);
            w.write("\n");
        } catch (IOException e) {
            Log.w(TAG, "could not write selected-server marker", e);
        }
    }

    static void clearSession(Context ctx, String serverId) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.remove(prefKey(serverId, PREF_SESSION_TOKEN));
        e.remove(prefKey(serverId, PREF_REFRESH_TOKEN));
        e.remove(prefKey(serverId, PREF_USER_ID));
        e.remove(prefKey(serverId, PREF_DISPLAY_NAME));
        e.remove(prefKey(serverId, PREF_WS_URI));

        // Remove legacy unscoped keys left by Build #11 when the selected
        // server is GeneralsX; other servers never used those keys.
        if (GeneralsOnlineServer.GENERALSX.equals(serverId)) {
            e.remove(PREF_SESSION_TOKEN);
            e.remove(PREF_REFRESH_TOKEN);
            e.remove(PREF_USER_ID);
            e.remove(PREF_DISPLAY_NAME);
            e.remove(PREF_WS_URI);
        }
        e.apply();

        File marker = new File(ctx.getFilesDir(), SESSION_MARKER_NAME);
        marker.delete();
        writeSelectedServerMarker(ctx, serverId);
    }

    static String getRefreshToken(Context ctx, String serverId) {
        return getString(ctx, serverId, PREF_REFRESH_TOKEN);
    }

    static String getSessionToken(Context ctx, String serverId) {
        return getString(ctx, serverId, PREF_SESSION_TOKEN);
    }

    static String getDisplayName(Context ctx, String serverId) {
        return getString(ctx, serverId, PREF_DISPLAY_NAME);
    }

    static void refreshSessionAsync(Context appContext) {
        final Context ctx = appContext.getApplicationContext();
        final String serverId = GeneralsOnlineServer.getSelected(ctx);

        new Thread(() -> {
            String refreshToken = getRefreshToken(ctx, serverId);
            if (refreshToken == null || refreshToken.isEmpty()) {
                Log.i(TAG, "no cached refresh_token for server=" + serverId);
                return;
            }

            AuthResult result = loginWithToken(serverId, refreshToken);
            if (result != null && result.state == 1) {
                saveSession(ctx, serverId, result);
                Log.i(TAG, "session refreshed at launch for server=" + serverId + ", user=" + result.userId);
            } else {
                Log.w(TAG, "launch-time refresh failed for server=" + serverId
                    + " (state=" + (result != null ? result.state : "network-error")
                    + "); keeping existing session marker");
            }
        }, "GeneralsOnlineSessionRefresh").start();
    }
}
