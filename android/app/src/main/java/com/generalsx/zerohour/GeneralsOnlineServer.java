package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Central definition of the two supported GeneralsOnline backends.
 *
 * The Android launcher, session cache and native engine all use the same
 * selected server id. Credentials are kept separately per server so switching
 * between servers never mixes refresh/session tokens.
 */
final class GeneralsOnlineServer {

    static final String PLAYGENERALS = "playgenerals";
    static final String GENERALSX = "generalsx";
    static final String DEFAULT = GENERALSX;

    static final String PREFS_NAME = "generalsonline_session";
    static final String PREF_SELECTED_SERVER = "selected_server";

    private GeneralsOnlineServer() {
    }

    static boolean isKnown(String serverId) {
        return PLAYGENERALS.equals(serverId) || GENERALSX.equals(serverId);
    }

    static String getSelected(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String value = prefs.getString(PREF_SELECTED_SERVER, DEFAULT);
        return isKnown(value) ? value : DEFAULT;
    }

    static void setSelected(Context ctx, String serverId) {
        if (!isKnown(serverId)) {
            serverId = DEFAULT;
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SELECTED_SERVER, serverId)
            .apply();

        // The native game reads this tiny marker before it creates the
        // GeneralsOnline manager. Keeping the selection separate from the
        // session marker also lets a user choose a server before signing in.
        GeneralsOnlineSession.writeSelectedServerMarker(ctx, serverId);
    }

    static String displayName(String serverId) {
        return PLAYGENERALS.equals(serverId) ? "Generals Online" : "GeneralsX";
    }

    static String subtitle(String serverId) {
        return PLAYGENERALS.equals(serverId)
            ? "Official GeneralsOnline / playgenerals.online"
            : "GeneralsX / FBraz3 server";
    }

    static String apiBase(String serverId) {
        return PLAYGENERALS.equals(serverId)
            ? "https://api.playgenerals.online/env/prod/contract/1/"
            : "https://online.generalsx.org/env/prod/contract/1/";
    }

    static String loginUrl(String serverId, String code, String clientId) {
        if (PLAYGENERALS.equals(serverId)) {
            return String.format(
                "http://www.playgenerals.online/login/?gamecode=%s&client=%s",
                code, clientId
            );
        }
        return String.format(
            "https://login.generalsx.org/login/?gamecode=%s&client=%s",
            code, clientId
        );
    }
}
