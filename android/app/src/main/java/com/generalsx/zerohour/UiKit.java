/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android port launcher-ui-2026 08/09/2026
//
// One place that knows what the launcher looks like.
//
// Every launcher screen is still built in code rather than inflated from
// layout XML -- that predates this file and is deliberate: the screens are
// conditional (the Vulkan-only cards, the base-Generals button, six
// diagnostic toggles generated from an array) and a layout file per
// permutation would be more machinery, not less. What was actually wrong
// was that each screen re-derived its own paddings, radii, text sizes and
// colours from scratch, so "16dp here, 12dp there, 22sp title, 0.7 alpha"
// drifted independently in four files and read as four different apps.
//
// So the widgets stay in code and the STYLE moves here: a card is whatever
// card() says it is, a primary action is whatever button(BTN_PRIMARY) says
// it is, and the numbers all come from res/values/dimens.xml + the M3
// colour roles in res/values/colors.xml. Nothing below is screen-specific.
//
// Everything here is API 24-safe (see the minSdk note in build.gradle) and
// start/end-relative rather than left/right, so the Arabic and Farsi
// translations mirror correctly.
//
// GeneralsX @feature Android port tactical-command-ui 20/10/2026 The look
// itself is now TACTICAL COMMAND — a military command-console HUD —
// replacing the short-lived navy/cyan glass redesign wholesale: flat
// dark-olive panels with khaki hairlines and SHARP corners (3-4dp, see
// dimens.xml), signal-amber primary, olive/khaki secondary, section
// headers as caps "command labels" with an amber tick and a hairline
// rule, squared buttons/segments/chips, and a blueprint-grid page
// backdrop. No glass, no translucency, no pills. Every public method
// signature from the glass pass is unchanged; only what the widgets
// look like moved.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.HashMap;
import java.util.Map;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

final class UiKit {

    private UiKit() {}

    // ---------------------------------------------------------------- tokens

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static int dim(Context c, int dimenRes) {
        return c.getResources().getDimensionPixelSize(dimenRes);
    }

    static int color(Context c, int colorRes) {
        int themed = themedColorRes(c, colorRes);
        return ContextCompat.getColor(c, themed != 0 ? themed : colorRes);
    }

    /**
     * Maps a base gzh_* colour resource to the active theme's variant,
     * or 0 when the resource is not a themed launcher token. Results are
     * cached per resource id; the cache is dropped on every theme change.
     */
    private static int themedColorRes(Context c, int colorRes) {
        int theme = themeIndex(c);
        if (theme == THEME_TACTICAL) {
            return 0;
        }
        Integer cached = themedResCache.get(colorRes);
        if (cached != null) {
            return cached;
        }
        int resolved = 0;
        try {
            Resources res = c.getResources();
            if (c.getPackageName().equals(res.getResourcePackageName(colorRes))) {
                String name = res.getResourceEntryName(colorRes);
                if (name.startsWith("gzh_")) {
                    resolved = res.getIdentifier(
                        THEME_PREFIX[theme] + name.substring(4), "color", c.getPackageName());
                }
            }
        } catch (Resources.NotFoundException ignored) {
            resolved = 0;
        }
        themedResCache.put(colorRes, resolved);
        return resolved;
    }

    // GeneralsX @feature Android port launcher-themes 20/10/2026
    // Runtime launcher themes: four presets whose colour tokens live in
    // values/colors.xml (gzhg_* / gzh_* / gzhm_* / gzhc_*), chosen in
    // Setup's Interface tab, persisted in the setup SharedPreferences
    // and re-applied on every launch. color() re-points every gzh_*
    // token at the active theme's variant, so existing call sites follow
    // the theme without knowing it exists; the XML drawables that cannot
    // be re-pointed that way (page background, hero plate) ship per-theme
    // variants picked by the *Res() helpers below; and Material/framework
    // widgets follow through the ThemeOverlay.ZeroHour.* styles applied
    // by applyThemeOverlay(). The default (no saved choice) is Generals,
    // the palette of the app icon: royal blue + eagle gold.
    static final int THEME_GENERALS = 0;
    static final int THEME_TACTICAL = 1;
    static final int THEME_MIDNIGHT = 2;
    static final int THEME_CRIMSON = 3;
    static final int THEME_COUNT = 4;
    private static final String THEME_PREFS = "generalszh_setup";
    private static final String THEME_KEY = "ui_theme";
    private static final String[] THEME_PREFIX = { "gzhg_", "gzh_", "gzhm_", "gzhc_" };

    private static int activeTheme = -1;
    private static final Map<Integer, Integer> themedResCache = new HashMap<>();

    static int themeIndex(Context c) {
        if (activeTheme < 0) {
            int saved = c.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
                .getInt(THEME_KEY, THEME_GENERALS);
            activeTheme = saved >= 0 && saved < THEME_COUNT ? saved : THEME_GENERALS;
        }
        return activeTheme;
    }

    static void setThemeIndex(Context c, int index) {
        activeTheme = index >= 0 && index < THEME_COUNT ? index : THEME_GENERALS;
        themedResCache.clear();
        SharedPreferences prefs = c.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(THEME_KEY, activeTheme).apply();
    }

    /** Applies the active theme's Material overlay; call first in onCreate. */
    static void applyThemeOverlay(Activity activity) {
        int res;
        switch (themeIndex(activity)) {
            case THEME_TACTICAL: res = R.style.ThemeOverlay_ZeroHour_Tactical; break;
            case THEME_MIDNIGHT: res = R.style.ThemeOverlay_ZeroHour_Midnight; break;
            case THEME_CRIMSON: res = R.style.ThemeOverlay_ZeroHour_Crimson; break;
            case THEME_GENERALS:
            default: res = R.style.ThemeOverlay_ZeroHour_Generals; break;
        }
        activity.getTheme().applyStyle(res, true);
    }

    private static int pageBackgroundRes(Context c) {
        switch (themeIndex(c)) {
            case THEME_GENERALS: return R.drawable.gzh_page_background_g;
            case THEME_MIDNIGHT: return R.drawable.gzh_page_background_m;
            case THEME_CRIMSON: return R.drawable.gzh_page_background_c;
            case THEME_TACTICAL:
            default: return R.drawable.gzh_page_background;
        }
    }

    private static int heroBackgroundRes(Context c) {
        switch (themeIndex(c)) {
            case THEME_GENERALS: return R.drawable.gzh_hero_background_g;
            case THEME_MIDNIGHT: return R.drawable.gzh_hero_background_m;
            case THEME_CRIMSON: return R.drawable.gzh_hero_background_c;
            case THEME_TACTICAL:
            default: return R.drawable.gzh_hero_background;
        }
    }

    static ColorStateList tint(Context c, int colorRes) {
        return ColorStateList.valueOf(color(c, colorRes));
    }

    /** enabled/disabled pair, the shape every Material tint slot wants. */
    static ColorStateList tint(Context c, int enabledRes, int disabledRes) {
        return new ColorStateList(
            new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
            new int[] { color(c, disabledRes), color(c, enabledRes) });
    }

    // GeneralsX @feature Android port material3-glass-ui 20/10/2026
    // Bundled Arabic typefaces (res/font/): IBM Plex Sans Arabic for body
    // text, Tajawal for display/headings. Loaded once and cached; weighted
    // faces come from Typeface.create(family, weight, italic), API 28+,
    // which is exactly this app's minSdk. Any load failure falls back to
    // the platform faces, so a font problem can never break a screen.
    private static Typeface bodyFamily;
    private static Typeface bodyMedium;
    private static Typeface bodySemibold;
    private static Typeface displayFamily;
    private static Typeface displayBold;

    private static Typeface family(Context c, int fontRes) {
        try {
            Typeface f = ResourcesCompat.getFont(c, fontRes);
            return f != null ? f : Typeface.DEFAULT;
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    /** Regular body face (IBM Plex Sans Arabic 400). */
    static Typeface fontBody(Context c) {
        if (bodyFamily == null) bodyFamily = family(c, R.font.gzh_font_body);
        return bodyFamily;
    }

    /** Medium body face (500) — overlines, supporting emphasis. */
    static Typeface fontMedium(Context c) {
        if (bodyMedium == null) bodyMedium = Typeface.create(fontBody(c), 500, false);
        return bodyMedium;
    }

    /** Semibold face (600) — headings, buttons, row titles (was DEFAULT_BOLD). */
    static Typeface fontSemibold(Context c) {
        if (bodySemibold == null) bodySemibold = Typeface.create(fontBody(c), 600, false);
        return bodySemibold;
    }

    /** Display face (Tajawal ExtraBold 800) — app-bar titles. */
    static Typeface fontDisplay(Context c) {
        if (displayFamily == null) displayFamily = family(c, R.font.gzh_font_display);
        if (displayBold == null) displayBold = Typeface.create(displayFamily, 800, false);
        return displayBold;
    }

    /**
     * The Tactical Command page backdrop (@drawable/gzh_page_background)
     * applied to a page's root view: dark olive base, faint blueprint
     * grid, one amber corner glow. Panels sit flat on it.
     */
    static void applyPageBackground(View view) {
        view.setBackground(ContextCompat.getDrawable(view.getContext(),
            pageBackgroundRes(view.getContext())));
    }

    /**
     * GeneralsX @feature Android port tactical-command-ui 20/10/2026
     * Command-bar treatment for SetupActivity's bottom navigation: a
     * solid near-black slab (no glass sheet, no floating pill) with a
     * 2dp signal-amber rule along its top edge. Name kept — SetupActivity
     * calls it — but the look is the squared command bar now. Layer
     * gravity/height are API 23+, safe at minSdk 28.
     */
    static void applyGlassNavBackground(View view) {
        Context c = view.getContext();
        ColorDrawable slab = new ColorDrawable(color(c, R.color.gzh_glass_nav));
        ColorDrawable topRule = new ColorDrawable(color(c, R.color.gzh_amber_dim));
        LayerDrawable layers = new LayerDrawable(
            new android.graphics.drawable.Drawable[] { slab, topRule });
        layers.setLayerGravity(1, Gravity.TOP);
        layers.setLayerHeight(1, Math.max(1, dp(c, 2)));
        view.setBackground(layers);
    }

    /**
     * GeneralsX @feature Android port tactical-command-ui 20/10/2026
     * A flat tactical panel fill: one solid colour, a 1dp khaki hairline
     * stroke and sharp corners. Replaces the old translucent glass
     * gradient outright — no translucency, no top-light sheen.
     */
    private static GradientDrawable panelDrawable(Context c, float radius, int fillRes) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(color(c, fillRes));
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, dp(c, 1)), color(c, R.color.gzh_glass_stroke));
        return bg;
    }

    /** checked/unchecked pair, for segmented buttons. */
    private static ColorStateList checkedTint(Context c, int checkedRes, int uncheckedRes) {
        return new ColorStateList(
            new int[][] { new int[] { android.R.attr.state_checked }, new int[0] },
            new int[] { color(c, checkedRes), color(c, uncheckedRes) });
    }

    // ------------------------------------------------------------ page shell

    /**
     * The scrolling body of a page: a vertical column of cards, gutter-padded,
     * inside a ScrollView. Returns the column to add cards to; the ScrollView
     * is already attached to {@code host}.
     */
    static LinearLayout scrollingPage(ViewGroup host) {
        Context c = host.getContext();
        applyPageBackground(host);
        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        int gutter = dim(c, R.dimen.gzh_gutter);
        column.setPadding(gutter, dp(c, 4), gutter, dim(c, R.dimen.gzh_card_gap));
        scroll.addView(column, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.addView(scroll, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return column;
    }

    /**
     * Top app bar: a small muted overline, a large bold title beneath it, and
     * an optional icon button pinned to the end edge. Returns the title view,
     * which the caller retitles as the user moves between sections.
     */
    static TextView appBar(ViewGroup parent, CharSequence overline, CharSequence title,
                           int trailingIconRes, CharSequence trailingDescription,
                           Runnable trailingAction) {
        Context c = parent.getContext();
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int gutter = dim(c, R.dimen.gzh_gutter);
        bar.setPadding(gutter, dp(c, 14), gutter, dp(c, 10));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        bar.addView(text, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (overline != null) {
            TextView over = new TextView(c);
            over.setText(overline);
            over.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_overline));
            over.setTextColor(color(c, R.color.gzh_primary));
            over.setTypeface(fontMedium(c));
            over.setAllCaps(true);
            over.setLetterSpacing(0.09f);
            over.setMaxLines(1);
            over.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(over);
        }

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_display));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(fontDisplay(c));
        titleView.setLineSpacing(0f, 1.0f);
        text.addView(titleView);

        if (trailingIconRes != 0 && trailingAction != null) {
            bar.addView(iconButton(c, trailingIconRes, trailingDescription, trailingAction));
        }

        parent.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return titleView;
    }

    /** Squared tonal icon-only button — used for the app bar's trailing action. */
    static View iconButton(Context c, int iconRes, CharSequence description, Runnable action) {
        int size = dim(c, R.dimen.gzh_icon_button);
        ImageView button = new ImageView(c);
        button.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
        button.setImageTintList(tint(c, R.color.gzh_on_surface_variant));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(c, 11);
        button.setPadding(pad, pad, pad, pad);
        button.setContentDescription(description);
        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // Was a round glass disc; now a squared plate with a khaki edge.
        button.setBackground(panelDrawable(c, dim(c, R.dimen.gzh_radius_row),
            R.color.gzh_surface_container_high));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(dim(c, R.dimen.gzh_item_gap));
        button.setLayoutParams(lp);
        return button;
    }

    // ----------------------------------------------------------------- cards

    /**
     * A squared surface-container panel appended to {@code parent}. Returns
     * the panel's inner vertical content holder — callers just add views
     * to it.
     */
    static LinearLayout card(LinearLayout parent) {
        Context c = parent.getContext();
        MaterialCardView card = new MaterialCardView(c);
        card.setRadius(dim(c, R.dimen.gzh_radius_card));
        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // Tactical panel: flat near-opaque olive fill, a 1dp khaki
        // hairline stroke, sharp 4dp corners (gzh_radius_card) and NO
        // elevation — panels bolt onto the backdrop, they do not float
        // over it like the old glass cards did.
        card.setCardElevation(0);
        card.setCardBackgroundColor(color(c, R.color.gzh_glass_surface));
        card.setStrokeColor(color(c, R.color.gzh_glass_stroke));
        card.setStrokeWidth(Math.max(1, dp(c, 1)));
        card.setUseCompatPadding(false);
        card.setPreventCornerOverlap(false);

        // A muted-khaki accent stripe runs the panel's full height at its
        // start edge, outside the padded body.
        LinearLayout shell = new LinearLayout(c);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        View stripe = new View(c);
        stripe.setBackgroundColor(color(c, R.color.gzh_outline));
        shell.addView(stripe, new LinearLayout.LayoutParams(dp(c, 3),
            ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dim(c, R.dimen.gzh_card_padding);
        body.setPadding(pad, pad, pad, pad);
        shell.addView(body, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(shell, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_card_gap);
        parent.addView(card, lp);
        return body;
    }

    /**
     * GeneralsX @feature Android port tactical-command-ui 20/10/2026
     * The hero plate (Setup Home): @drawable/gzh_hero_background — a flat
     * dark-olive panel with amber corner ticks — instead of a card. Same
     * contract as {@link #card}: returns the padded inner vertical holder
     * callers add views to.
     */
    static LinearLayout heroPanel(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(ContextCompat.getDrawable(c, heroBackgroundRes(c)));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dim(c, R.dimen.gzh_card_padding);
        body.setPadding(pad, pad, pad, pad);
        panel.addView(body, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_card_gap);
        parent.addView(panel, lp);
        return body;
    }

    /**
     * GeneralsX @feature Android port tactical-command-ui 20/10/2026
     * A horizontal strip of squared status readouts (see {@link #readout}),
     * appended to {@code parent} — the hero's instrument row.
     */
    static LinearLayout readoutStrip(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap);
        parent.addView(strip, lp);
        return strip;
    }

    /**
     * GeneralsX @feature Android port tactical-command-ui 20/10/2026
     * One squared "readout" chip in a {@link #readoutStrip}: dark plate,
     * khaki hairline, caps label in the given colour. Returns the view so
     * a caller can keep one readout live (the hero's folder verdict is
     * rewritten by SetupActivity.refreshStatus()).
     */
    static TextView readout(LinearLayout strip, CharSequence text, int textColorRes) {
        Context c = strip.getContext();
        TextView view = new TextView(c);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        view.setTextColor(color(c, textColorRes));
        view.setTypeface(fontSemibold(c));
        view.setAllCaps(true);
        view.setLetterSpacing(0.04f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        view.setMaxLines(1);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setBackground(panelDrawable(c, dp(c, 2), R.color.gzh_surface));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (strip.getChildCount() > 0) {
            lp.setMarginStart(dim(c, R.dimen.gzh_item_gap_tight));
        }
        strip.addView(view, lp);
        return view;
    }

    /**
     * Card header as a Tactical Command "command label": a leading amber
     * tick block, the section name in the display face (caps, letter-
     * spaced), an optional value pinned to the end edge in the accent
     * colour, and a hairline rule under the whole label. Returns that
     * value view, or null when {@code withValue} is false.
     */
    static TextView sectionHeader(LinearLayout body, int iconRes, CharSequence title, boolean withValue) {
        Context c = body.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);

        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // The amber tick: every settings group announces itself with the
        // same signal block before its name.
        View tick = new View(c);
        tick.setBackgroundColor(color(c, R.color.gzh_primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(c, 5), dp(c, 20));
        tlp.setMarginEnd(dp(c, 10));
        row.addView(tick, tlp);

        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
            icon.setImageTintList(tint(c, R.color.gzh_secondary));
            int s = dim(c, R.dimen.gzh_icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.setMarginEnd(dp(c, 10));
            row.addView(icon, ilp);
        }

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_title));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(fontDisplay(c));
        titleView.setAllCaps(true);
        titleView.setLetterSpacing(0.05f);
        row.addView(titleView, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = null;
        if (withValue) {
            value = new TextView(c);
            value.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_title));
            value.setTextColor(color(c, R.color.gzh_primary));
            value.setTypeface(fontSemibold(c));
            value.setGravity(Gravity.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.setMarginStart(dim(c, R.dimen.gzh_item_gap));
            row.addView(value, vlp);
        }

        body.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The hairline rule the label stands on, extending the full width.
        View rule = new View(c);
        rule.setBackgroundColor(color(c, R.color.gzh_outline_variant));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        rlp.topMargin = dp(c, 6);
        rlp.bottomMargin = dim(c, R.dimen.gzh_item_gap);
        body.addView(rule, rlp);
        return value;
    }

    // ------------------------------------------------------------------ text

    /** Ordinary paragraph text inside a card. */
    static TextView body(LinearLayout parent, CharSequence text) {
        return text(parent, text, R.dimen.gzh_text_body, R.color.gzh_on_surface, 0);
    }

    /** Secondary/explanatory text — the grey supporting line. */
    static TextView supporting(LinearLayout parent, CharSequence text) {
        return text(parent, text, R.dimen.gzh_text_body, R.color.gzh_on_surface_variant, 0);
    }

    /** The smallest tier: help paragraphs and "when to turn this on" copy. */
    static TextView caption(LinearLayout parent, CharSequence text) {
        return text(parent, text, R.dimen.gzh_text_caption, R.color.gzh_on_surface_faint, 0);
    }

    private static TextView text(LinearLayout parent, CharSequence text,
                                 int sizeDimen, int colorRes, int topGapDp) {
        Context c = parent.getContext();
        TextView view = new TextView(c);
        if (text != null) {
            view.setText(text);
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, sizeDimen));
        view.setTextColor(color(c, colorRes));
        view.setTypeface(fontBody(c));
        view.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topGapDp > 0 ? dp(c, topGapDp) : dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(view, lp);
        return view;
    }

    /**
     * Long help copy folded away behind a tappable "?" affordance would need a
     * new string; instead help paragraphs get a quieter treatment and a hairline
     * above them, so a card reads as "controls, then explanation".
     */
    static TextView helpText(LinearLayout parent, CharSequence text) {
        divider(parent);
        TextView view = caption(parent, text);
        ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin =
            dim(parent.getContext(), R.dimen.gzh_item_gap);
        return view;
    }

    static View divider(LinearLayout parent) {
        Context c = parent.getContext();
        View line = new View(c);
        line.setBackgroundColor(color(c, R.color.gzh_outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dim(c, R.dimen.gzh_item_gap);
        parent.addView(line, lp);
        return line;
    }

    // --------------------------------------------------------------- buttons

    /** Filled accent action — one per card at most. */
    static final int BTN_PRIMARY = 0;
    /** Quiet filled action on a container tint — the everyday button. */
    static final int BTN_TONAL = 1;
    /** Outlined action, for a secondary choice next to a tonal one. */
    static final int BTN_OUTLINE = 2;
    /** Destructive/reset action, in the muted pastel. */
    static final int BTN_DANGER = 3;

    static MaterialButton button(LinearLayout parent, int kind, int iconRes,
                                 CharSequence label, Runnable onClick) {
        Context c = parent.getContext();
        MaterialButton b = new MaterialButton(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTypeface(fontSemibold(c));
        b.setLetterSpacing(0f);
        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // Squared corners (was a 28dp glass pill radius).
        b.setCornerRadius(dim(c, R.dimen.gzh_radius_row));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dim(c, R.dimen.gzh_button_height));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setGravity(Gravity.CENTER);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setStrokeWidth(0);

        if (iconRes != 0) {
            b.setIcon(ContextCompat.getDrawable(c, iconRes));
            b.setIconSize(dim(c, R.dimen.gzh_icon));
            b.setIconPadding(dp(c, 10));
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        }

        switch (kind) {
            case BTN_PRIMARY:
                b.setBackgroundTintList(tint(c, R.color.gzh_primary, R.color.gzh_container_disabled));
                b.setTextColor(tint(c, R.color.gzh_on_primary, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_on_primary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_light));
                break;
            case BTN_OUTLINE:
                b.setBackgroundTintList(tint(c, android.R.color.transparent));
                b.setStrokeWidth(Math.max(1, dp(c, 1)));
                b.setStrokeColor(tint(c, R.color.gzh_outline, R.color.gzh_outline_variant));
                b.setTextColor(tint(c, R.color.gzh_on_surface, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_primary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_primary));
                break;
            case BTN_DANGER:
                b.setBackgroundTintList(tint(c, android.R.color.transparent));
                b.setStrokeWidth(Math.max(1, dp(c, 1)));
                b.setStrokeColor(tint(c, R.color.gzh_tertiary_container, R.color.gzh_outline_variant));
                b.setTextColor(tint(c, R.color.gzh_tertiary, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_tertiary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_light));
                break;
            case BTN_TONAL:
            default:
                b.setBackgroundTintList(tint(c, R.color.gzh_surface_container_high, R.color.gzh_container_disabled));
                b.setTextColor(tint(c, R.color.gzh_on_surface, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_primary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_light));
                break;
        }

        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(b, lp);
        return b;
    }

    /**
     * GeneralsX @feature Android port tactical-command-ui 20/10/2026
     * The hero action: a BTN_PRIMARY block grown to dominate its screen —
     * the full-width signal-amber launch slab on Setup Home (taller,
     * display face, larger label). Same click contract as {@link #button}.
     */
    static MaterialButton heroButton(LinearLayout parent, int iconRes,
                                     CharSequence label, Runnable onClick) {
        MaterialButton b = button(parent, BTN_PRIMARY, iconRes, label, onClick);
        Context c = parent.getContext();
        b.setMinHeight(dim(c, R.dimen.gzh_button_height_hero));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        b.setTypeface(fontDisplay(c));
        b.setLetterSpacing(0.03f);
        return b;
    }

    /** A horizontal strip that lays its children out at equal width. */
    static LinearLayout buttonRow(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(row, lp);
        return row;
    }

    /**
     * Re-weights a button already added to a {@link #buttonRow} so the row's
     * buttons split its width evenly.
     *
     * MATCH_PARENT height rather than WRAP_CONTENT, deliberately: a
     * translation that wraps to two lines (Ukrainian "Очистити логи" is the
     * one that first caught this) would otherwise make that one button taller
     * than its siblings and break the row's bottom edge. Horizontal padding
     * also comes down, since a third of a phone's width has to hold an icon
     * and a word.
     */
    static void share(MaterialButton button, boolean firstInRow) {
        Context c = button.getContext();
        button.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 10));
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.weight = 1f;
        lp.topMargin = 0;
        if (!firstInRow) {
            lp.setMarginStart(dim(c, R.dimen.gzh_item_gap_tight));
        }
        button.setLayoutParams(lp);
    }

    // ------------------------------------------------------------- segmented

    /**
     * A Material 3 segmented button row: N choices side by side, the selected
     * one filled in the accent. Returns the group; {@code onSelect} fires with
     * the index the user picked (never for the programmatic initial state).
     */
    static MaterialButtonToggleGroup segmented(LinearLayout parent, CharSequence[] labels,
                                               int checkedIndex,
                                               java.util.function.IntConsumer onSelect) {
        Context c = parent.getContext();
        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(c);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);

        final int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialButton b = new MaterialButton(c);
            b.setId(View.generateViewId());
            ids[i] = b.getId();
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            b.setTypeface(fontSemibold(c));
            b.setMaxLines(1);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // GeneralsX @feature Android port tactical-command-ui
            // 20/10/2026 Squared segments (was a 24dp pill radius); the
            // checked segment is the solid amber block below.
            b.setCornerRadius(dim(c, R.dimen.gzh_radius_row));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(dp(c, 46));
            b.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10));
            b.setGravity(Gravity.CENTER);
            b.setElevation(0f);
            b.setStateListAnimator(null);
            b.setStrokeWidth(Math.max(1, dp(c, 1)));
            b.setStrokeColor(checkedTint(c, R.color.gzh_primary, R.color.gzh_outline));
            b.setBackgroundTintList(checkedTint(c, R.color.gzh_primary, android.R.color.transparent));
            b.setTextColor(checkedTint(c, R.color.gzh_on_primary, R.color.gzh_on_surface_variant));
            b.setRippleColor(tint(c, R.color.gzh_ripple_primary));
            group.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        if (checkedIndex >= 0 && checkedIndex < ids.length) {
            group.check(ids[checkedIndex]);
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    onSelect.accept(i);
                    return;
                }
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(group, lp);
        return group;
    }

    // ------------------------------------------------------------------ rows

    /** A title + supporting description + trailing Material switch. */
    static MaterialSwitch switchRow(LinearLayout parent, CharSequence title, CharSequence description) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);

        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_body));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(fontSemibold(c));
        textColumn.addView(titleView);

        TextView descView = new TextView(c);
        descView.setText(description);
        descView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        descView.setTextColor(color(c, R.color.gzh_on_surface_faint));
        descView.setTypeface(fontBody(c));
        descView.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(c, 3);
        textColumn.addView(descView, dlp);

        MaterialSwitch toggle = new MaterialSwitch(c);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(dim(c, R.dimen.gzh_item_gap));
        row.addView(toggle, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap);
        parent.addView(row, lp);
        return toggle;
    }

    /**
     * A tappable list row: leading icon, title, supporting line, trailing
     * chevron. {@link Row#supporting} is exposed because most callers need to
     * rewrite that line when the underlying state changes.
     */
    static final class Row {
        final LinearLayout root;
        final TextView title;
        final TextView supporting;

        private Row(LinearLayout root, TextView title, TextView supporting) {
            this.root = root;
            this.title = title;
            this.supporting = supporting;
        }
    }

    static Row listRow(LinearLayout parent, int iconRes, CharSequence title,
                       CharSequence supporting, Runnable onClick) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        int padH = dim(c, R.dimen.gzh_item_gap);
        int padV = dp(c, 14);
        row.setPadding(padH, padV, padH, padV);

        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // Flat squared panel (was a translucent rounded glass row).
        row.setBackground(panelDrawable(c, dim(c, R.dimen.gzh_radius_row),
            R.color.gzh_surface_container));
        // Khaki accent stripe at the row's start edge.
        View stripe = new View(c);
        stripe.setBackgroundColor(color(c, R.color.gzh_khaki));
        LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(dp(c, 3),
            ViewGroup.LayoutParams.MATCH_PARENT);
        stripeLp.setMarginEnd(dim(c, R.dimen.gzh_item_gap));
        row.addView(stripe, stripeLp);
        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> onClick.run());
            // GeneralsX @bugfix Android port 03/10/2026 A tap on a row showed nothing until its
            // action had visible results -- for "copy this address" that is never. The same ripple
            // as the buttons, clipped to the row's rounded corners.
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(dim(c, R.dimen.gzh_radius_row));
            mask.setColor(0xFFFFFFFF);
            row.setForeground(new android.graphics.drawable.RippleDrawable(
                tint(c, R.color.gzh_ripple_primary), null, mask));
        }

        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
            icon.setImageTintList(tint(c, R.color.gzh_secondary));
            int s = dim(c, R.dimen.gzh_icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.setMarginEnd(dim(c, R.dimen.gzh_item_gap));
            row.addView(icon, ilp);
        }

        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_body));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(fontSemibold(c));
        textColumn.addView(titleView);

        TextView supportingView = new TextView(c);
        supportingView.setText(supporting);
        supportingView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        supportingView.setTextColor(color(c, R.color.gzh_on_surface_variant));
        supportingView.setTypeface(fontBody(c));
        supportingView.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(c, 3);
        textColumn.addView(supportingView, slp);

        if (onClick != null) {
            ImageView chevron = new ImageView(c);
            chevron.setImageDrawable(ContextCompat.getDrawable(c, R.drawable.ic_gzh_chevron));
            chevron.setImageTintList(tint(c, R.color.gzh_on_surface_faint));
            int s = dp(c, 16);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, s);
            clp.setMarginStart(dim(c, R.dimen.gzh_item_gap_tight));
            row.addView(chevron, clp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(row, lp);
        return new Row(row, titleView, supportingView);
    }

    /**
     * A compact "chip": a squared, tinted, non-interactive label. Used for the
     * one-word verdicts (GPU name, active driver) that used to be full
     * sentences of body text.
     */
    static TextView chip(LinearLayout parent, int iconRes, CharSequence label, int textColorRes,
                         int backgroundColorRes) {
        Context c = parent.getContext();
        TextView chip = new TextView(c);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        chip.setTextColor(color(c, textColorRes));
        chip.setTypeface(fontSemibold(c));
        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // Caps + tracking: chips read as instrument readouts now.
        chip.setAllCaps(true);
        chip.setLetterSpacing(0.04f);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        chip.setMaxLines(2);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (iconRes != 0) {
            android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(c, iconRes);
            if (icon != null) {
                int s = dp(c, 15);
                icon.setBounds(0, 0, s, s);
                icon.setTint(color(c, textColorRes));
                chip.setCompoundDrawablesRelative(icon, null, null, null);
                chip.setCompoundDrawablePadding(dp(c, 7));
            }
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        // GeneralsX @feature Android port tactical-command-ui 20/10/2026
        // Squared chip (was a 999dp pill).
        bg.setCornerRadius(dp(c, 2));
        bg.setColor(color(c, backgroundColorRes));
        bg.setStroke(Math.max(1, dp(c, 1)), color(c, R.color.gzh_glass_stroke));
        chip.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(chip, lp);
        return chip;
    }
}

// Rebuild trigger 2026-10-20: fresh CI run after a throttled runner; no functional change.
