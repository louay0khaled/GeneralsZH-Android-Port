/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	GeneralsOnline Android account screen.
*/

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.security.SecureRandom;

public class GeneralsOnlineActivity extends Activity {

    private static final String CLIENT_ID = "custom_third_party_client";
    private static final int POLL_INTERVAL_MS = 15000;
    private static final int POLL_MAX_ATTEMPTS = 12;
    private static final String CODE_CHARSET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 32;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private TextView statusText;
    private MaterialButton signInButton;
    private MaterialButton signOutButton;
    private RadioGroup serverGroup;

    private int pollAttempt = 0;
    private boolean busy = false;
    private String pendingCode = null;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.online_window_title);

        // Persist the selected backend before the native game can be launched.
        GeneralsOnlineServer.setSelected(this, GeneralsOnlineServer.getSelected(this));

        buildUi();
        refreshStatus();
        maybeSilentReauth();
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        setContentView(shell);
        InsetUtil.applySafeInsets(shell);

        UiKit.appBar(shell, getString(R.string.online_subtitle),
            getString(R.string.online_window_title), 0, null, null);

        android.widget.FrameLayout host = new android.widget.FrameLayout(this);
        shell.addView(host, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout page = UiKit.scrollingPage(host);

        LinearLayout serverCard = UiKit.card(page);
        UiKit.sectionHeader(serverCard, R.drawable.ic_gzh_account,
            getString(R.string.online_card_server), false);
        UiKit.supporting(serverCard, getString(R.string.online_server_help));

        serverGroup = new RadioGroup(this);
        serverGroup.setOrientation(RadioGroup.VERTICAL);

        RadioButton playGenerals = new RadioButton(this);
        playGenerals.setText(getString(R.string.online_server_playgenerals));
        serverGroup.addView(playGenerals, new RadioGroup.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        RadioButton generalsX = new RadioButton(this);
        generalsX.setText(getString(R.string.online_server_generalsx));
        serverGroup.addView(generalsX, new RadioGroup.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        serverCard.addView(serverGroup);

        String selectedServer = GeneralsOnlineServer.getSelected(this);
        playGenerals.setChecked(GeneralsOnlineServer.PLAYGENERALS.equals(selectedServer));
        generalsX.setChecked(GeneralsOnlineServer.GENERALSX.equals(selectedServer));

        serverGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (busy || checkedId == -1) {
                return;
            }

            RadioButton checked = group.findViewById(checkedId);
            if (checked == playGenerals) {
                GeneralsOnlineServer.setSelected(this, GeneralsOnlineServer.PLAYGENERALS);
            } else if (checked == generalsX) {
                GeneralsOnlineServer.setSelected(this, GeneralsOnlineServer.GENERALSX);
            }
            refreshStatus();
        });

        LinearLayout statusCard = UiKit.card(page);
        UiKit.sectionHeader(statusCard, R.drawable.ic_gzh_account,
            getString(R.string.online_window_title), false);
        statusText = UiKit.body(statusCard, null);
        statusText.setTextIsSelectable(true);
        signOutButton = UiKit.button(statusCard, UiKit.BTN_DANGER, R.drawable.ic_gzh_trash,
            getString(R.string.online_button_sign_out), this::onSignOut);

        LinearLayout stepsCard = UiKit.card(page);
        UiKit.sectionHeader(stepsCard, R.drawable.ic_gzh_check,
            getString(R.string.online_card_sign_in), false);
        UiKit.supporting(stepsCard, getString(R.string.online_signin_help));
        signInButton = UiKit.button(stepsCard, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_account,
            getString(R.string.online_button_sign_in), this::onSignIn);
    }

    private String generateGameCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; ++i) {
            sb.append(CODE_CHARSET.charAt(random.nextInt(CODE_CHARSET.length())));
        }
        return sb.toString();
    }

    private void setBusy(boolean value) {
        busy = value;
        signInButton.setEnabled(!value);
        serverGroup.setEnabled(!value);
        signOutButton.setEnabled(!value && hasCurrentSession());
    }

    private boolean hasCurrentSession() {
        String serverId = GeneralsOnlineServer.getSelected(this);
        String token = GeneralsOnlineSession.getSessionToken(this, serverId);
        return token != null && !token.isEmpty();
    }

    private void maybeSilentReauth() {
        final String serverId = GeneralsOnlineServer.getSelected(this);
        String refreshToken = GeneralsOnlineSession.getRefreshToken(this, serverId);

        if (refreshToken == null || refreshToken.isEmpty() || busy) {
            return;
        }

        setBusy(true);
        statusText.setText(getString(
            R.string.online_status_signing_in_server,
            GeneralsOnlineServer.displayName(serverId)
        ));

        new Thread(() -> {
            GeneralsOnlineSession.AuthResult result =
                GeneralsOnlineSession.loginWithToken(serverId, refreshToken);

            handler.post(() -> {
                setBusy(false);

                if (result != null && result.state == 1) {
                    saveSession(serverId, result);
                    refreshStatus();
                } else if (result != null && result.state == 2) {
                    clearSession(serverId);
                    refreshStatus();
                } else {
                    refreshStatus();
                    if (result == null) {
                        statusText.setText(withNetworkErrorDetail(
                            getString(R.string.online_status_network_error)
                        ));
                    }
                }
            });
        }, "GeneralsOnlineSessionRefresh").start();
    }

    private boolean ensureNotBatteryOptimized() {
        android.os.PowerManager pm =
            (android.os.PowerManager) getSystemService(POWER_SERVICE);

        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return true;
        }

        try {
            Intent intent = new Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Exception e) {
            return true;
        }

        statusText.setText(R.string.online_status_battery_opt);
        return false;
    }

    private void onSignIn() {
        if (busy || !ensureNotBatteryOptimized()) {
            return;
        }

        final String serverId = GeneralsOnlineServer.getSelected(this);
        final String code = generateGameCode();
        pendingCode = code;
        final String url = GeneralsOnlineServer.loginUrl(serverId, code, CLIENT_ID);

        setBusy(true);
        pollAttempt = 0;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            setBusy(false);
            Toast.makeText(
                this,
                getString(R.string.online_toast_no_browser, e.getMessage()),
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        statusText.setText(getString(
            R.string.online_status_continue_browser_server,
            GeneralsOnlineServer.displayName(serverId)
        ));

        handler.postDelayed(() -> pollOnce(serverId, code), POLL_INTERVAL_MS);
    }

    private void pollOnce(String serverId, String code) {
        new Thread(() -> {
            GeneralsOnlineSession.AuthResult result = callCheckLogin(serverId, code);
            handler.post(() -> handlePollResult(serverId, result));
        }).start();
    }

    private void handlePollResult(String serverId, GeneralsOnlineSession.AuthResult result) {
        if (result == null) {
            setBusy(false);
            statusText.setText(withNetworkErrorDetail(
                getString(R.string.online_status_network_error)
            ));
            return;
        }

        switch (result.state) {
            case 1:
                setBusy(false);
                saveSession(serverId, result);
                refreshStatus();
                Toast.makeText(
                    this,
                    getString(R.string.online_toast_signed_in_as, result.displayName),
                    Toast.LENGTH_LONG
                ).show();
                break;

            case 2:
                setBusy(false);
                statusText.setText(R.string.online_status_signin_failed);
                break;

            case 0:
            case -1:
                ++pollAttempt;
                if (pollAttempt >= POLL_MAX_ATTEMPTS) {
                    setBusy(false);
                    statusText.setText(R.string.online_status_timed_out);
                } else {
                    handler.postDelayed(
                        () -> pollOnce(serverId, pendingCode),
                        POLL_INTERVAL_MS
                    );
                }
                break;

            default:
                setBusy(false);
                statusText.setText(R.string.online_status_unexpected);
                break;
        }
    }

    private String withNetworkErrorDetail(String baseMessage) {
        String detail = GeneralsOnlineSession.lastNetworkErrorDetail;
        if (detail == null || detail.isEmpty()) {
            return baseMessage;
        }
        return baseMessage + "\n\n" + detail;
    }

    private GeneralsOnlineSession.AuthResult callCheckLogin(String serverId, String code) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("client_id", CLIENT_ID);
            body.put("reserved_0", "");
            body.put("reserved_1", "");
            body.put("reserved_2", "");
        } catch (Exception e) {
            return null;
        }

        return GeneralsOnlineSession.postJson(serverId, "CheckLogin", body, null);
    }

    private void saveSession(String serverId, GeneralsOnlineSession.AuthResult result) {
        GeneralsOnlineSession.saveSession(this, serverId, result);
    }

    private void clearSession(String serverId) {
        GeneralsOnlineSession.clearSession(this, serverId);
    }

    private void onSignOut() {
        String serverId = GeneralsOnlineServer.getSelected(this);
        clearSession(serverId);
        refreshStatus();
        Toast.makeText(this, R.string.online_toast_signed_out, Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        String serverId = GeneralsOnlineServer.getSelected(this);
        String displayName = GeneralsOnlineSession.getDisplayName(this, serverId);
        String sessionToken = GeneralsOnlineSession.getSessionToken(this, serverId);

        if (displayName != null && sessionToken != null && !sessionToken.isEmpty()) {
            statusText.setText(getString(
                R.string.online_status_signed_in_server,
                GeneralsOnlineServer.displayName(serverId),
                displayName
            ));
            signOutButton.setEnabled(!busy);
        } else {
            statusText.setText(getString(
                R.string.online_status_not_signed_in_server,
                GeneralsOnlineServer.displayName(serverId)
            ));
            signOutButton.setEnabled(false);
        }
    }

    static String getSignedInDisplayName(android.content.Context ctx) {
        String serverId = GeneralsOnlineServer.getSelected(ctx);
        String displayName = GeneralsOnlineSession.getDisplayName(ctx, serverId);
        String sessionToken = GeneralsOnlineSession.getSessionToken(ctx, serverId);

        if (displayName != null && sessionToken != null && !sessionToken.isEmpty()) {
            return displayName;
        }
        return null;
    }
}
