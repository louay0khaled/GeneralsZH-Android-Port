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

// FILE: SelectPanel.cpp //////////////////////////////////////////////////////////////////////////
// GeneralsX @feature Android port 20/10/2026
//
// Unit-category selection touch panel: five buttons stacked with the
// control-group panel (GroupPanel.wnd, the template this file follows)
// that REPLACE the current selection with
// every on-screen unit of one category belonging to the local player:
//
//   Infantry / Ground vehicles / Jets / Helicopters / All army
//
// All the actual selection work lives in
// InGameUI::selectPanelCategoryAcrossScreen() -- screen scope only,
// locally controlled units only, max-select cap and
// KINDOF_IGNORES_SELECT_ALL respected there. Selection is client-local
// display state, so none of this touches lockstep determinism; the only
// simulation-visible effect is the same MSG_CREATE_SELECTED_GROUP the
// double-click and control-group paths already produce.
//
// SelectPanel.wnd is a loose file (Window\SelectPanel.wnd) rather than
// part of the game's own .big archives, exactly like GroupPanel.wnd --
// see GameWindowManagerScript.cpp's Window\ path resolution.
//
// GeneralsX @feature Android port 21/10/2026 Round 5 restyle, from device
// feedback on the round above: the panel is now a vertical column on the
// LEFT edge, stacked directly above the group panel (its bottom meets the
// group panel's top), styled after ControlBar Pro: charcoal fills at
// A160-style translucency in the .wnd, and borders tinted at runtime from
// the LIVE ControlBarScheme colors (TheControlBar->getBorderColor() and
// the command-button action border color), so on a game folder that runs
// a scheme override -- ControlBarPro.big included -- the panel wears
// that scheme's exact faction trim with nothing copied out of the .big.
// Captions are GX: labels (s_gxEnglishDefaults in GameText.cpp plus the
// bundled languages/<lang>/generals.str packs), set by this file itself:
// the layout's custom system callback drops the GGM_SET_LABEL that would
// otherwise deliver the fetched string (see applyButtonTextOnce below).
//
// GeneralsX @feature Android port 21/10/2026 Round 6, from device feedback
// on the round above: (1) the group panel is MERGED into this layout --
// SelectPanel.wnd now also contains GroupPanel.wnd's ButtonHandle,
// GroupRow and ButtonGroup0..9 (window names and the GroupPanelSystem
// callback byte-identical, so GroupPanel.cpp's logic is untouched); this
// file drives the merged windows' follow translation, scheme tint and
// per-frame update (GroupPanelUpdate is forwarded from SelectPanelUpdate).
// (2) The scheme refresh also applies ControlBar Pro's tray art: a
// background window behind the column gets the live faction's
// "<Side>ProTraySmall" mapped image when the installed game data provides
// it (ControlBarPro.big does), exactly how Pro's own shortcut bar is
// constructed (a USER window with an IMAGE status behind plain buttons);
// without Pro the authored flat fill stays. Nothing is copied out of the
// .big -- the name is resolved against the live mapped-image collection.
//
// GeneralsX @feature Android port 21/10/2026 Round 7, from device feedback
// on the round above: (1) the merge silently broke the "=" handle -- in
// this engine a push button's click is delivered to its PARENT window's
// system function (createGadget sets m_owner = parent), so the handle's
// GBM_SELECTED now lands here in SelectPanelSystem instead of in
// GroupPanelSystem; SelectPanelSystem forwards the group controls'
// GBM_SELECTED / GBM_SELECTED_RIGHT messages to GroupPanelSystem, which
// restores the exact pre-merge delivery (GroupPanel.cpp still untouched).
// (2) The panel is re-anchored: instead of a calibrated offset to
// ControlBarParent, the follow reads the LIVE rect of ControlBar.wnd:
// LeftHUD (the minimap window, the same rect Radar draws from) every
// frame and parks the merged stack flush on top of it -- bottom edge on
// the minimap's top edge, left edges aligned, column sized to the
// minimap's width with the five buttons centered inside it. (3) The
// scheme refresh also put the live scheme's button face on the buttons
// themselves -- REMOVED in Round 9, see below.
//
// GeneralsX @feature Android port 21/10/2026 Round 9, from the device
// verdict on the round above: item (3) never rendered on a real
// install. The face name it resolved ("SCBigButton", the scheme's
// QueueButtonImage value) provably resolves to nothing in the mapped-
// image collection of a real install -- the name exists only as scheme
// text; no MappedImage by that name is loaded -- so the face path was
// dead code, and no wide labeled-button face art exists in stock or
// ControlBar Pro data anyway (Pro's faced buttons are small icon
// buttons with dedicated art; SCBigButton itself is the build queue's
// 60x48 disabled placeholder). Round 9 removes the face path entirely:
// the buttons keep the plain draw with the live scheme border/action
// colors the refresh writes, and the follow widens them to fill the
// column (a small proportional inset) so the Pro tray behind them
// reads as a frame around the buttons instead of leaking its striped
// interior around narrower centered boxes.
//
// GeneralsX @feature Android port 21/10/2026 Round 10, from the device
// verdict on the round above: (1) the buttons STILL showed a double
// frame -- investigation proved the "inner rectangle" is the metallic
// frame baked inside the Pro tray PICTURE itself, showing through the
// buttons' translucent (A160) fills; no button code draws it, and
// widening could never fix it. (2) The Infantry button was dead: the
// Round 7 follow translated the panel's CHILDREN up to the minimap but
// never moved the layout ROOT, and the window manager's input gate
// (findWindowUnderMouse) discards taps outside the root's region before
// any child is tested -- button 0 sat entirely above the root. Drawing
// has no such gate, so it looked normal. Round 10 therefore rebuilds
// the stack: the tray and PanelBackground are GONE; the panel wears its
// own shipped art (GXSelectPanel.ini + faction atlases under
// Data\Textures, resolved through the HandCreated mapped-image bucket,
// so it looks identical with or without ControlBar Pro); the follow
// anchors the ROOT window on the minimap and never translates children
// again; the header carries a collapse arrow that hides the whole
// column (root + header cap + arrow stay). If the GX art is missing at
// runtime the panel degrades to a flat tier (plain draw, opaque scheme
// fills) instead of rendering broken. The helicopter classification
// fix that ships alongside lives in InGameUI.cpp.
///////////////////////////////////////////////////////////////////////////////////////////////////

#include "PreRTS.h"

#include "Common/GXSafeArea.h"
#include "Common/NameKeyGenerator.h"
#include "Common/Player.h"
#include "Common/PlayerList.h"
#include "Common/PlayerTemplate.h"
#include "GameClient/Color.h"
#include "GameClient/ControlBar.h"
#include "GameClient/Display.h"
#include "GameClient/GameText.h"
#include "GameClient/GadgetPushButton.h"
#include "GameClient/GameWindow.h"
#include "GameClient/GameWindowManager.h"
#include "GameClient/GUICallbacks.h"
#include "GameClient/Image.h"
#include "GameClient/InGameUI.h"
#include "GameClient/WindowLayout.h"

static NameKeyType s_buttonRowID = NAMEKEY_INVALID;
static NameKeyType s_buttonID[5];
// GeneralsX @feature Android port 21/10/2026 Round 10: the layout root
// (the follow anchors IT now, never the children), the two backplate
// windows that replaced the Pro tray, and the header's collapse toggle.
static NameKeyType s_parentID = NAMEKEY_INVALID;
static NameKeyType s_headerPlateID = NAMEKEY_INVALID;
static NameKeyType s_columnPlateID = NAMEKEY_INVALID;
static NameKeyType s_collapseID = NAMEKEY_INVALID;
// GeneralsX @feature Android port 21/10/2026 Round 6: the merged group
// windows, cached here as well (GroupPanel.cpp keeps its own copies for
// its logic) because this file anchors and tints them with the column
// (Round 10: anchored through the layout root, never moved directly).
static NameKeyType s_groupHandleID = NAMEKEY_INVALID;
static NameKeyType s_groupRowID = NAMEKEY_INVALID;
static NameKeyType s_buttonGroupID[10];
// GeneralsX @feature Android port 21/10/2026 Round 7: the minimap window
// the merged panel anchors to (the follow reads its live rect every
// frame -- the same window Radar caches for the same reason).
static NameKeyType s_leftHudID = NAMEKEY_INVALID;

// Button order in SelectPanel.wnd -> InGameUI panel category.
static const char *s_buttonNames[5] =
{
	"SelectPanel.wnd:ButtonInfantry",
	"SelectPanel.wnd:ButtonGround",
	"SelectPanel.wnd:ButtonJets",
	"SelectPanel.wnd:ButtonHelicopters",
	"SelectPanel.wnd:ButtonAllArmy",
};
static const Int s_buttonCategory[5] =
{
	InGameUI::PANEL_SELECT_INFANTRY,
	InGameUI::PANEL_SELECT_GROUND_VEHICLES,
	InGameUI::PANEL_SELECT_JETS,
	InGameUI::PANEL_SELECT_HELICOPTERS,
	InGameUI::PANEL_SELECT_ALL_ARMY,
};
// String-table labels for the same five buttons, in the same order. The
// English text lives in GameText.cpp's s_gxEnglishDefaults; translations
// live in the bundled language packs (languages/<lang>/generals.str) and
// win over the default automatically because fetch() consults the loaded
// pack first.
static const char *s_buttonLabels[5] =
{
	"GX:SelectInfantry",
	"GX:SelectVehicles",
	"GX:SelectJets",
	"GX:SelectHelicopters",
	"GX:SelectAllArmy",
};

// GeneralsX @feature Android port 21/10/2026 Round 10: the panel's own
// art, shipped with the APK as Data\INI\MappedImages\HandCreated\
// GXSelectPanel.ini plus one texture atlas per faction family
// (GXSelectPanelUSA/China/GLA_512.tga). HandCreated is the last bucket
// ImageCollection::load reads (with overwrite), so these names resolve
// on any install, with or without ControlBar Pro. The five buttons are
// full-face DrawOne buttons whose faces carry the local player's
// faction colors; the state suffixes are "" (enabled), "H" (hilite),
// "P" (pressed = hilite+selected) and "D" (disabled), the slot pattern
// GadgetPushButton.h's getters read in W3DGadgetPushButtonImageDrawOne.
// Stems are in s_buttonNames order (ButtonGround's art is "Vehicles").
static const char *s_buttonArtStems[5] =
{
	"GXBtnInfantry",
	"GXBtnVehicles",
	"GXBtnJets",
	"GXBtnHelicopters",
	"GXBtnAllArmy",
};
static const char *s_artStateSuffix[4] = { "", "H", "P", "D" };

// Art tiers: 0 = not decided yet for this layout, 1 = Tier B (the GX
// art above), 2 = Tier Flat (plain draw + opaque scheme fills). The
// decision is made by ensurePanelArt() below; Tier Flat is terminal
// for a layout's lifetime (the draw-func swap is one-way).
static Int s_artTier = 0;
// The faction family whose faces are currently on the buttons/plates
// ("USA" / "China" / "GLA"); "" while Tier B was never applied. The
// follow re-runs the application when the live family changes
// (observer/faction switch, or an init that ran before the local
// player's template existed).
static char s_appliedFamily[8] = "";
// Resolved faces for the applied family + the faction-neutral art.
// Indices follow s_artStateSuffix; the arrow sets add slot [4] =
// disabled-selected, which simply mirrors the "D" face.
static const Image *s_buttonFace[5][4];
static const Image *s_plateFace[2];	// 0 = header, 1 = column
static const Image *s_digitFace[4];
static const Image *s_handleFace[4];
static const Image *s_arrowFace[2][5];	// 0 = Up (expanded), 1 = Down (collapsed)

// Collapse state for the header arrow toggle (SelectPanelSystem flips
// it, the follow consumes it). Per-match: reset by SelectPanelInit and
// by the follow's layout-generation check. Not persisted.
static Bool s_columnCollapsed = FALSE;
// While collapsed the follow force-hides the column every call (see
// SelectPanelFollowControlBar); this pair snapshots GroupRow's hidden
// bit at collapse entry so expansion restores exactly the state
// GroupPanel owns -- its expanded flag cannot change while collapsed
// because the "=" handle that toggles it is hidden with the column.
static Bool s_collapseForceActive = FALSE;
static Bool s_groupRowHiddenBeforeCollapse = FALSE;
// The last layout root the follow saw; a different pointer means a new
// layout generation (layouts are recreated per match), which re-arms
// the per-match resets in case SelectPanelInit never ran -- at head
// nothing calls this layout's runInit(), so the follow is the only
// guaranteed per-match entry point (see SelectPanelInit).
static GameWindow *s_lastRootSeen = nullptr;

//-------------------------------------------------------------------------------------------------
static void cacheWidgetIDs()
{
	if (s_buttonRowID != NAMEKEY_INVALID) {
		return; // already cached -- GWM_CREATE fires once per window in this layout
	}
	s_buttonRowID = TheNameKeyGenerator->nameToKey("SelectPanel.wnd:ButtonRow");
	for (Int i = 0; i < 5; ++i) {
		s_buttonID[i] = TheNameKeyGenerator->nameToKey(s_buttonNames[i]);
	}
	s_parentID = TheNameKeyGenerator->nameToKey("SelectPanel.wnd:SelectPanelParent");
	s_headerPlateID = TheNameKeyGenerator->nameToKey("SelectPanel.wnd:HeaderPlate");
	s_columnPlateID = TheNameKeyGenerator->nameToKey("SelectPanel.wnd:ColumnPlate");
	s_collapseID = TheNameKeyGenerator->nameToKey("SelectPanel.wnd:ButtonCollapse");
	s_groupHandleID = TheNameKeyGenerator->nameToKey("GroupPanel.wnd:ButtonHandle");
	s_groupRowID = TheNameKeyGenerator->nameToKey("GroupPanel.wnd:GroupRow");
	char buf[40];
	for (Int i = 0; i < 10; ++i) {
		sprintf(buf, "GroupPanel.wnd:ButtonGroup%d", i);
		s_buttonGroupID[i] = TheNameKeyGenerator->nameToKey(buf);
	}
	s_leftHudID = TheNameKeyGenerator->nameToKey("ControlBar.wnd:LeftHUD");
}

//-------------------------------------------------------------------------------------------------
static Int findSelectButtonIndex(Int controlID)
{
	for (Int i = 0; i < 5; ++i) {
		if (controlID == s_buttonID[i]) {
			return i;
		}
	}
	return -1;
}

//-------------------------------------------------------------------------------------------------
// GeneralsX @feature Android port 21/10/2026 Round 7: the merged group
// controls (the "=" handle and the ten digit buttons), whose click
// messages SelectPanelSystem forwards to GroupPanelSystem (see the
// GBM_SELECTED cases there for why the forward exists at all).
static Bool isGroupControlID(Int controlID)
{
	if (controlID == s_groupHandleID) {
		return TRUE;
	}
	for (Int i = 0; i < 10; ++i) {
		if (controlID == s_buttonGroupID[i]) {
			return TRUE;
		}
	}
	return FALSE;
}

//-------------------------------------------------------------------------------------------------
static void handleSelectButton(Int index)
{
	if (!TheInGameUI || index < 0 || index >= 5) {
		return;
	}
	TheInGameUI->selectPanelCategoryAcrossScreen(s_buttonCategory[index]);
}

// GeneralsX @feature Android port 21/10/2026 Round 5: the captions and the
// scheme tint are applied from the follow function below (its first
// visible frame), not from GWM_CREATE or SelectPanelInit: window ids are
// not reliable during GWM_CREATE (see SelectPanelSystem), and the control
// bar's scheme colors only exist once a match has selected a scheme for
// the local player's faction.
//
// The captions cannot arrive the stock way. createWindow() fetches the
// .wnd TEXT through TheGameText and delivers it with GadgetButtonSetText,
// which posts GGM_SET_LABEL -- handled by GadgetPushButtonSystem, the
// system function these buttons do NOT have (SelectPanelSystem replaced
// it), so the fetched text is silently dropped and the literal .wnd
// string is what renders. Setting the text directly with winSetText()
// writes the exact field GGM_SET_LABEL would have written
// (WinInstanceData::m_text), so the buttons localize through the port's
// GX: labels exactly like the force-attack button does.
static Bool s_buttonTextApplied = FALSE;

static void applyButtonTextOnce()
{
	if (s_buttonTextApplied || !TheWindowManager || !TheGameText) {
		return;
	}
	Int applied = 0;
	for (Int i = 0; i < 5; ++i) {
		GameWindow *button = TheWindowManager->winGetWindowFromId(nullptr, s_buttonID[i]);
		if (button) {
			button->winSetText(TheGameText->fetch(s_buttonLabels[i]));
			++applied;
		}
	}
	if (applied == 5) {
		s_buttonTextApplied = TRUE;
	}
}

// The tint reads the LIVE scheme state off the control bar: getBorderColor()
// is the current faction scheme's CommandBarBorderColor -- on a game folder
// running ControlBarPro.big that is the Pro scheme's own value for the
// faction being played, because the engine loaded the scheme from the .big
// itself. The authored .wnd border (GroupPanel gold) stays as the fallback
// until a scheme exists, and the refresh is change-driven: the scheme is
// fixed for a match's faction, so in practice this reapplies once per match
// (and on observer/faction switches), never per frame.
static Color s_appliedBorderColor = GAME_COLOR_UNDEFINED;
static Color s_appliedActionColor = GAME_COLOR_UNDEFINED;

// The live local player's scheme side ("America", "ChinaTankGeneral", ...),
// or "" when no local player/template is around yet (shell, observer) --
// the same source the ControlBar's own scheme matching reads.
static AsciiString currentLocalPlayerSide()
{
	if (ThePlayerList) {
		if (Player *player = ThePlayerList->getLocalPlayer()) {
			if (const PlayerTemplate *pt = player->getPlayerTemplate()) {
				return pt->getSide();
			}
		}
	}
	return AsciiString();
}

// GeneralsX @feature Android port 21/10/2026 Round 10: the art family
// for the live local player's side, prefix-matched from the same side
// string the Round 6 tray resolution read (above), so sub-general
// sides resolve to their parent faction's art: "America*" -> "USA",
// "China*" -> "China", "GLA*" -> "GLA". A side that exists but matches
// none of these still gets the "USA" set (the authored default); only
// a not-yet-readable side (no local player/template) yields "", which
// defers the art decision to a later frame instead of locking it in.
static const char *localPlayerFamily()
{
	AsciiString side = currentLocalPlayerSide();
	if (side.str()[0] == '\0') {
		return "";
	}
	if (strncmp(side.str(), "China", 5) == 0) {
		return "China";
	}
	if (strncmp(side.str(), "GLA", 3) == 0) {
		return "GLA";
	}
	return "USA";
}

// GeneralsX @feature Android port 21/10/2026 Round 6: this is the
// panel's scheme-TINT refresh: the live scheme border colors go on the
// column AND on the merged group windows. Still fully change-driven --
// once the colors are applied, a call costs two color compares.
// GeneralsX @feature Android port 21/10/2026 Round 9: Round 7 also had
// this refresh apply a scheme button FACE to the buttons; that path is
// removed (see the Round 9 note in the file header) -- it never resolved
// on a real install, so the buttons simply keep the plain draw with the
// border/action colors written here.
// GeneralsX @feature Android port 21/10/2026 Round 10: Round 6 also had
// this refresh resolve the faction's Pro tray image onto a background
// window; the tray is deleted (it was the double frame), so nothing
// here touches images anymore -- the panel's own GX faces are applied
// by ensurePanelArt() below. The border writes stay: they are what the
// plain draw reads, so they still carry the look in Tier Flat.
static void refreshSchemeTint()
{
	if (!TheWindowManager || !TheControlBar) {
		return;
	}
	Color borderColor = TheControlBar->getBorderColor();
	if (borderColor == GAME_COLOR_UNDEFINED) {
		return; // no scheme applied yet -- keep the authored colors
	}
	Color actionColor = TheControlBar->getCommandButtonBorderActionColor();
	if (actionColor == GAME_COLOR_UNDEFINED) {
		actionColor = borderColor;
	}
	if (borderColor == s_appliedBorderColor && actionColor == s_appliedActionColor) {
		return;
	}

	// All nine draw-data parts carry the same border in this template
	// (same as the .wnd), so every slot is set: enabled/disabled borders
	// take the faction trim, the hilite border takes the scheme's action
	// color (selection is an action; Pro schemes mark those green).
	GameWindow *row = TheWindowManager->winGetWindowFromId(nullptr, s_buttonRowID);
	if (row) {
		for (Int slot = 0; slot < 9; ++slot) {
			row->winSetEnabledBorderColor(slot, borderColor);
			row->winSetDisabledBorderColor(slot, borderColor);
			row->winSetHiliteBorderColor(slot, borderColor);
		}
	}
	for (Int i = 0; i < 5; ++i) {
		GameWindow *button = TheWindowManager->winGetWindowFromId(nullptr, s_buttonID[i]);
		if (!button) {
			continue;
		}
		for (Int slot = 0; slot < 9; ++slot) {
			button->winSetEnabledBorderColor(slot, borderColor);
			button->winSetDisabledBorderColor(slot, borderColor);
			button->winSetHiliteBorderColor(slot, actionColor);
		}
	}

	// GeneralsX @feature Android port 21/10/2026 Round 6: the merged group
	// windows take the same live tint -- the group row like the button row
	// (faction trim on every border), the handle and group buttons like
	// the select buttons (trim normally, action color on hilite). Their
	// authored colors stay as the fallback, exactly as before the merge.
	GameWindow *groupRow = TheWindowManager->winGetWindowFromId(nullptr, s_groupRowID);
	if (groupRow) {
		for (Int slot = 0; slot < 9; ++slot) {
			groupRow->winSetEnabledBorderColor(slot, borderColor);
			groupRow->winSetDisabledBorderColor(slot, borderColor);
			groupRow->winSetHiliteBorderColor(slot, borderColor);
		}
	}
	GameWindow *groupHandle = TheWindowManager->winGetWindowFromId(nullptr, s_groupHandleID);
	if (groupHandle) {
		for (Int slot = 0; slot < 9; ++slot) {
			groupHandle->winSetEnabledBorderColor(slot, borderColor);
			groupHandle->winSetDisabledBorderColor(slot, borderColor);
			groupHandle->winSetHiliteBorderColor(slot, actionColor);
		}
	}
	for (Int i = 0; i < 10; ++i) {
		GameWindow *groupButton = TheWindowManager->winGetWindowFromId(nullptr, s_buttonGroupID[i]);
		if (!groupButton) {
			continue;
		}
		for (Int slot = 0; slot < 9; ++slot) {
			groupButton->winSetEnabledBorderColor(slot, borderColor);
			groupButton->winSetDisabledBorderColor(slot, borderColor);
			groupButton->winSetHiliteBorderColor(slot, actionColor);
		}
	}

	s_appliedBorderColor = borderColor;
	s_appliedActionColor = actionColor;
}

// GeneralsX @feature Android port 21/10/2026 Round 10: resolve one
// complete art set -- the given family's 22 names (5 buttons x 4
// states + 2 plates) plus the 16 faction-neutral names (digits, handle,
// collapse arrows) -- against the mapped-image collection, filling the
// face tables above. Returns FALSE on the first missing name: a
// DrawOne button with a missing state image draws nothing for that
// state, so a partial set is worse than none.
static Bool resolveArtSet(const char *family)
{
	if (!TheMappedImageCollection) {
		return FALSE;
	}
	char name[64];
	for (Int i = 0; i < 5; ++i) {
		for (Int s = 0; s < 4; ++s) {
			sprintf(name, "%s%s%s", s_buttonArtStems[i], family, s_artStateSuffix[s]);
			s_buttonFace[i][s] = TheMappedImageCollection->findImageByName(name);
			if (!s_buttonFace[i][s]) {
				return FALSE;
			}
		}
	}
	sprintf(name, "GXPlateHeader%s", family);
	s_plateFace[0] = TheMappedImageCollection->findImageByName(name);
	if (!s_plateFace[0]) {
		return FALSE;
	}
	sprintf(name, "GXPlateColumn%s", family);
	s_plateFace[1] = TheMappedImageCollection->findImageByName(name);
	if (!s_plateFace[1]) {
		return FALSE;
	}
	for (Int s = 0; s < 4; ++s) {
		sprintf(name, "GXDigit%s", s_artStateSuffix[s]);
		s_digitFace[s] = TheMappedImageCollection->findImageByName(name);
		if (!s_digitFace[s]) {
			return FALSE;
		}
		sprintf(name, "GXHandle%s", s_artStateSuffix[s]);
		s_handleFace[s] = TheMappedImageCollection->findImageByName(name);
		if (!s_handleFace[s]) {
			return FALSE;
		}
	}
	static const char *arrowStems[2] = { "GXArrowUp", "GXArrowDown" };
	for (Int a = 0; a < 2; ++a) {
		for (Int s = 0; s < 4; ++s) {
			sprintf(name, "%s%s", arrowStems[a], s_artStateSuffix[s]);
			s_arrowFace[a][s] = TheMappedImageCollection->findImageByName(name);
			if (!s_arrowFace[a][s]) {
				return FALSE;
			}
		}
		s_arrowFace[a][4] = s_arrowFace[a][3]; // disabled-selected mirrors "D"
	}
	return TRUE;
}

// Put one button's four state faces on it through the gadget setters.
// For a DrawOne button the setters are exact: they write enabled /
// hilite / hilite-selected / disabled slots 0/0/1/0 (the slots
// W3DGadgetPushButtonImageDrawOne reads); the extra slots they clear
// (enabled 5/6, 2/3) are DrawThree territory DrawOne never reads --
// enabled slot 5 staying null is precisely what keeps the draw on the
// DrawOne path.
static void applyButtonFaces(GameWindow *button, const Image *faces[4])
{
	if (!button) {
		return;
	}
	GadgetButtonSetEnabledImage(button, faces[0]);
	GadgetButtonSetHiliteImage(button, faces[1]);
	GadgetButtonSetHiliteSelectedImage(button, faces[2]);
	GadgetButtonSetDisabledImage(button, faces[3]);
	GadgetButtonSetDisabledSelectedImage(button, faces[3]);
}

// The collapse arrow's face for the current collapse state. Tier B:
// the Up set while expanded, the Down set while collapsed (authored in
// the .wnd as Up; this swap is the only runtime image change the panel
// ever makes outside a tier/family application). Tier Flat: a text
// glyph of the same meaning ("^" expanded, "v" collapsed).
static void applyArrowFace()
{
	if (!TheWindowManager) {
		return;
	}
	GameWindow *collapse = TheWindowManager->winGetWindowFromId(nullptr, s_collapseID);
	if (!collapse) {
		return;
	}
	if (s_artTier == 2) {
		UnicodeString glyph;
		glyph.translate(AsciiString(s_columnCollapsed ? "v" : "^"));
		collapse->winSetText(glyph);
		return;
	}
	if (s_artTier != 1) {
		return; // undecided yet -- ensurePanelArt calls this once it decides
	}
	const Image **faces = s_arrowFace[s_columnCollapsed ? 1 : 0];
	GadgetButtonSetEnabledImage(collapse, faces[0]);
	GadgetButtonSetHiliteImage(collapse, faces[1]);
	GadgetButtonSetHiliteSelectedImage(collapse, faces[2]);
	GadgetButtonSetDisabledImage(collapse, faces[3]);
	GadgetButtonSetDisabledSelectedImage(collapse, faces[4]);
}

// Tier B application: the resolved family's faces onto the five select
// buttons and the two plates, the neutral faces onto the digits and
// the "=" handle. The plates take their image through winSetEnabledImage
// slot 0 -- the exact call the Round 6 tray code used for
// PanelBackground; a USER window's default draw reads slot 0 only.
static void applyTierB(GameWindow *headerPlate, GameWindow *columnPlate)
{
	for (Int i = 0; i < 5; ++i) {
		applyButtonFaces(TheWindowManager->winGetWindowFromId(nullptr, s_buttonID[i]),
			s_buttonFace[i]);
	}
	if (headerPlate) {
		headerPlate->winSetEnabledImage(0, s_plateFace[0]);
	}
	if (columnPlate) {
		columnPlate->winSetEnabledImage(0, s_plateFace[1]);
	}
	for (Int i = 0; i < 10; ++i) {
		applyButtonFaces(TheWindowManager->winGetWindowFromId(nullptr, s_buttonGroupID[i]),
			s_digitFace);
	}
	applyButtonFaces(TheWindowManager->winGetWindowFromId(nullptr, s_groupHandleID),
		s_handleFace);
	applyArrowFace();
}

// Force one window's authored fill colors opaque (alpha 255) in all
// three draw-data sets, keeping the authored RGB: Tier Flat's fills
// must never let the game world show through (the Round 10 lesson of
// the tray). Border colors are not touched here -- refreshSchemeTint
// owns those, in both tiers.
static void forceFillsOpaque(GameWindow *window)
{
	if (!window) {
		return;
	}
	for (Int slot = 0; slot < 9; ++slot) {
		window->winSetEnabledColor(slot, window->winGetEnabledColor(slot) | 0xFF000000);
		window->winSetDisabledColor(slot, window->winGetDisabledColor(slot) | 0xFF000000);
		window->winSetHiliteColor(slot, window->winGetHiliteColor(slot) | 0xFF000000);
	}
}

// Tier Flat conversion (one-way for this layout): every button in the
// stack goes back to the plain push-button draw with the IMAGE status
// bit cleared -- the same pair of calls createGadget itself uses to
// pick a button's draw function -- the fills go opaque, and the two
// plates hide for good (their only content was the GX art; the button
// row's own authored fill, forced opaque too, becomes the backing).
static void applyTierFlat(GameWindow *headerPlate, GameWindow *columnPlate, GameWindow *row)
{
	GameWinDrawFunc plainDraw = TheWindowManager->getPushButtonDrawFunc();
	for (Int i = 0; i < 5; ++i) {
		GameWindow *button = TheWindowManager->winGetWindowFromId(nullptr, s_buttonID[i]);
		if (button) {
			button->winSetDrawFunc(plainDraw);
			button->winClearStatus(WIN_STATUS_IMAGE);
			forceFillsOpaque(button);
		}
	}
	for (Int i = 0; i < 10; ++i) {
		GameWindow *button = TheWindowManager->winGetWindowFromId(nullptr, s_buttonGroupID[i]);
		if (button) {
			button->winSetDrawFunc(plainDraw);
			button->winClearStatus(WIN_STATUS_IMAGE);
			forceFillsOpaque(button);
		}
	}
	GameWindow *handle = TheWindowManager->winGetWindowFromId(nullptr, s_groupHandleID);
	if (handle) {
		handle->winSetDrawFunc(plainDraw);
		handle->winClearStatus(WIN_STATUS_IMAGE);
		forceFillsOpaque(handle);
	}
	GameWindow *collapse = TheWindowManager->winGetWindowFromId(nullptr, s_collapseID);
	if (collapse) {
		collapse->winSetDrawFunc(plainDraw);
		collapse->winClearStatus(WIN_STATUS_IMAGE);
		forceFillsOpaque(collapse);
	}
	forceFillsOpaque(row);
	if (headerPlate) {
		headerPlate->winHide(TRUE);
	}
	if (columnPlate) {
		columnPlate->winHide(TRUE);
	}
	applyArrowFace();
}

// GeneralsX @feature Android port 21/10/2026 Round 10: decide the art
// tier and apply it. Probe order: the local player's family set first;
// if it is incomplete and the family is not USA, the USA set (the
// authored default); if no complete set exists, Tier Flat. While the
// tier is undecided, or whenever the live family changes under Tier B,
// this re-runs; once Tier Flat is chosen it never runs again for the
// layout. Called from SelectPanelInit AND from the follow's first
// visible frame -- at head nothing invokes this layout's LAYOUTINIT
// (ControlBar::update calls runUpdate() only), so the follow call is
// the one that guarantees the decision actually happens; both call
// sites are safe because everything here is state-guarded.
static void ensurePanelArt()
{
	if (!TheWindowManager || !TheMappedImageCollection) {
		return;
	}
	const char *family = localPlayerFamily();
	if (s_artTier == 2) {
		return; // Tier Flat is terminal for this layout
	}
	if (s_artTier == 1 && family[0] != '\0' && strcmp(family, s_appliedFamily) == 0) {
		return; // Tier B already wearing exactly this family
	}
	if (family[0] == '\0') {
		return; // no local player side yet -- defer, never lock in a guess
	}
	GameWindow *headerPlate = TheWindowManager->winGetWindowFromId(nullptr, s_headerPlateID);
	GameWindow *columnPlate = TheWindowManager->winGetWindowFromId(nullptr, s_columnPlateID);
	GameWindow *row = TheWindowManager->winGetWindowFromId(nullptr, s_buttonRowID);
	if (!headerPlate || !columnPlate || !row) {
		return; // windows not fetchable yet -- a later call retries
	}

	const char *useFamily = family;
	if (!resolveArtSet(useFamily)) {
		if (strcmp(family, "USA") != 0 && resolveArtSet("USA")) {
			useFamily = "USA";
		} else {
			s_artTier = 2;
			s_appliedFamily[0] = '\0';
			applyTierFlat(headerPlate, columnPlate, row);
			return;
		}
	}
	s_artTier = 1;
	strcpy(s_appliedFamily, useFamily);
	applyTierB(headerPlate, columnPlate);
}

// GeneralsX @feature Android port 20/10/2026 Follow-the-bar pairing:
// SelectPanelFollowControlBar is called from ControlBar::update() every
// frame with the real bar's screen position and the panel's visibility
// decision, and that contract stands through every restyle below.
// GeneralsX @feature Android port 21/10/2026 Round 7: placement no
// longer derives from the calibrated ControlBarParent offset this block
// used to capture. The merged panel is anchored to the minimap instead:
// ControlBar.wnd:LeftHUD is fetched by name (Radar caches the same
// window for the same reason -- its authored rect differs between the
// stock and scheme-override ControlBar.wnd files, so the LIVE rect is
// the only reliable anchor) and the stack is parked flush on top of it
// each frame. That absorbs scheme authoring resolutions, GXUiScale's
// ControlBar.wnd scaling, the bar's slide animations and resolution
// changes with no calibration at all. The calibrate entry point stays
// (ControlBar::init still calls it) but its offsets no longer feed
// placement.
static Bool s_followOffsetCalibrated = FALSE;
static Int s_followOffsetX = 0;
static Int s_followOffsetY = 0;

void SelectPanelCalibrateFollowOffset(Int barScreenX, Int barScreenY)
{
	if (!TheWindowManager) {
		return;
	}
	GameWindow *row = TheWindowManager->winGetWindowFromId(nullptr, s_buttonRowID);
	if (!row) {
		return;
	}
	Int rowX, rowY;
	row->winGetScreenPosition(&rowX, &rowY);
	s_followOffsetX = rowX - barScreenX;
	s_followOffsetY = rowY - barScreenY;
	s_followOffsetCalibrated = TRUE;
}

// GeneralsX @feature Android port 21/10/2026 Round 10: per-match reset,
// run when the follow first sees a layout root it has not seen before
// (layouts are recreated per match, so a new root pointer IS a new
// match). This mirrors SelectPanelInit's resets exactly. It exists
// because at head nothing calls this layout's runInit() -- ControlBar::
// update() calls runUpdate() but never runInit() on m_selectPanelLayout
// -- so SelectPanelInit cannot be relied on to run at all; the follow
// is the only guaranteed per-match entry point. When SelectPanelInit
// DOES run (any future caller), the two are idempotent duplicates.
static void onNewLayoutGeneration(GameWindow *root)
{
	s_buttonTextApplied = FALSE;
	s_appliedBorderColor = GAME_COLOR_UNDEFINED;
	s_appliedActionColor = GAME_COLOR_UNDEFINED;
	s_artTier = 0;
	s_appliedFamily[0] = '\0';
	s_columnCollapsed = FALSE;
	s_collapseForceActive = FALSE;
	// Same Round 7.1 reasoning as in SelectPanelInit.
	GroupPanelInit(root ? root->winGetLayout() : nullptr, nullptr);
}

void SelectPanelFollowControlBar(Int barScreenX, Int barScreenY, Bool visible)
{
	// Placement anchors on the minimap's live rect (below), so the bar
	// position arguments are not used for placement; the signature stays
	// because ControlBar::update calls it this way.
	(void)barScreenX;
	(void)barScreenY;
	if (!TheWindowManager) {
		return;
	}

	GameWindow *root = TheWindowManager->winGetWindowFromId(nullptr, s_parentID);
	if (root && root != s_lastRootSeen) {
		s_lastRootSeen = root;
		onNewLayoutGeneration(root);
	}

	if (!visible) {
		return;
	}

	// First visible frame applies the localized captions, decides and
	// applies the art tier/family, and applies the live scheme tint;
	// all three are change-driven no-ops afterwards (see the helpers
	// above).
	applyButtonTextOnce();
	ensurePanelArt();
	refreshSchemeTint();

	GameWindow *leftHud = (s_leftHudID != NAMEKEY_INVALID)
		? TheWindowManager->winGetWindowFromId(nullptr, s_leftHudID) : nullptr;
	GameWindow *row = TheWindowManager->winGetWindowFromId(nullptr, s_buttonRowID);
	GameWindow *groupHandle = TheWindowManager->winGetWindowFromId(nullptr, s_groupHandleID);
	GameWindow *groupRow = TheWindowManager->winGetWindowFromId(nullptr, s_groupRowID);
	if (!leftHud || !root || !row || !groupHandle) {
		// No anchor (or no panel) this frame -- keep the last placement
		// exactly as it stands rather than jumping somewhere wrong.
		return;
	}
	GameWindow *headerPlate = TheWindowManager->winGetWindowFromId(nullptr, s_headerPlateID);
	GameWindow *columnPlate = TheWindowManager->winGetWindowFromId(nullptr, s_columnPlateID);
	GameWindow *collapse = TheWindowManager->winGetWindowFromId(nullptr, s_collapseID);

	Int hudX, hudY, hudW, hudH;
	leftHud->winGetScreenPosition(&hudX, &hudY);
	leftHud->winGetSize(&hudW, &hudH);

	// GeneralsX @feature Android port 21/10/2026 Round 10: anchor the
	// ROOT, never the children (the Round 7 child-translation follow is
	// what killed the Infantry button -- see the header note). One
	// delta moves the whole stack rigidly: root's left edge on the
	// minimap's left edge, root's bottom edge flush on the minimap's
	// top edge. The root's own authored width is NEVER resized -- its
	// 366-design-px span is what keeps the digits row's rightward
	// extent inside the root's input gate in both collapse states.
	Int rootScreenX, rootScreenY, rootW, rootH;
	root->winGetScreenPosition(&rootScreenX, &rootScreenY);
	root->winGetSize(&rootW, &rootH);
	Int dx = hudX - rootScreenX;
	Int dy = hudY - (rootScreenY + rootH);
	if (dx != 0 || dy != 0) {
		Int rootX, rootY;
		root->winGetPosition(&rootX, &rootY);
		root->winSetPosition(rootX + dx, rootY + dy);
	}

	// Width sync, column windows only: the plates and the button row
	// take the minimap's live width. The five buttons are NOT resized --
	// their DrawOne faces are authored at an exact size and stretching
	// them would distort the baked glyphs -- instead each is re-centered
	// in the row whenever the width changes. The collapse arrow keeps
	// its authored width and is re-pinned to the column's right edge.
	// The group row and handle keep their authored geometry entirely
	// (they ride the root). All writes are change-driven, as before.
	Int rowW, rowH;
	row->winGetSize(&rowW, &rowH);
	if (rowW != hudW) {
		row->winSetSize(hudW, rowH);
	}
	if (headerPlate) {
		Int plateW, plateH;
		headerPlate->winGetSize(&plateW, &plateH);
		if (plateW != hudW) {
			headerPlate->winSetSize(hudW, plateH);
		}
	}
	if (columnPlate) {
		Int plateW, plateH;
		columnPlate->winGetSize(&plateW, &plateH);
		if (plateW != hudW) {
			columnPlate->winSetSize(hudW, plateH);
		}
	}
	for (Int i = 0; i < 5; ++i) {
		GameWindow *button = TheWindowManager->winGetWindowFromId(nullptr, s_buttonID[i]);
		if (!button) {
			continue;
		}
		Int btnW, btnH, bx, by;
		button->winGetSize(&btnW, &btnH);
		button->winGetPosition(&bx, &by);
		Int targetX = (hudW - btnW) / 2;
		if (targetX < 0) {
			targetX = 0;
		}
		if (bx != targetX) {
			button->winSetPosition(targetX, by);
		}
	}
	if (collapse) {
		Int collapseW, collapseH, cx, cy;
		collapse->winGetSize(&collapseW, &collapseH);
		collapse->winGetPosition(&cx, &cy);
		Int targetX = hudW - collapseW;
		if (targetX < 0) {
			targetX = 0;
		}
		if (cx != targetX) {
			collapse->winSetPosition(targetX, cy);
		}
	}

	// Collapse enforcement, applied on EVERY call (not change-driven):
	// ControlBar::update calls this follow BEFORE the layout's
	// runUpdate, and GroupPanel's only GroupRow visibility write is in
	// its system function at input-event time -- the handle that fires
	// it is itself hidden while collapsed, so GroupPanel's expanded
	// state cannot change under us; the every-call force is belt and
	// braces so no ordering of those writes can ever show the column
	// while collapsed. The header plate and the arrow are never hidden
	// here: collapsed, the block is just its cap sitting on the minimap.
	if (s_columnCollapsed) {
		if (!s_collapseForceActive) {
			s_groupRowHiddenBeforeCollapse = groupRow ? groupRow->winIsHidden() : TRUE;
			s_collapseForceActive = TRUE;
		}
		if (columnPlate && s_artTier != 2) {
			columnPlate->winHide(TRUE);
		}
		row->winHide(TRUE);
		if (groupRow) {
			groupRow->winHide(TRUE);
		}
		groupHandle->winHide(TRUE);
	} else {
		if (s_collapseForceActive) {
			// Expansion: hand GroupRow back exactly as GroupPanel left
			// it (its state was frozen while collapsed, see above).
			if (groupRow) {
				groupRow->winHide(s_groupRowHiddenBeforeCollapse);
			}
			s_collapseForceActive = FALSE;
		}
		if (columnPlate && s_artTier != 2) {
			columnPlate->winHide(FALSE);
		}
		row->winHide(FALSE);
		groupHandle->winHide(FALSE);
		// GroupRow's hidden state is GroupPanel's own business whenever
		// the column is not collapsed -- never touched here.
	}
}

//-------------------------------------------------------------------------------------------------
void SelectPanelInit(WindowLayout *layout, void *userData)
{
	(void)layout;
	(void)userData;
	// The layout is recreated for every match in the same process, so the
	// one-shot caption/tint/art state starts over with it (the follow
	// function re-applies all of it on the first visible frame).
	s_buttonTextApplied = FALSE;
	s_appliedBorderColor = GAME_COLOR_UNDEFINED;
	s_appliedActionColor = GAME_COLOR_UNDEFINED;
	// GeneralsX @feature Android port 21/10/2026 Round 10: art tier and
	// collapse start over with the layout too (expanded by default).
	s_artTier = 0;
	s_appliedFamily[0] = '\0';
	s_columnCollapsed = FALSE;
	s_collapseForceActive = FALSE;
	s_lastRootSeen = nullptr;
	// Probe and apply the GX art now (the tier/family decision); if the
	// side or the windows are not readable yet, ensurePanelArt defers
	// and the follow completes it on the first visible frame. NOTE: at
	// head nothing calls this layout's runInit(), so this function may
	// never run -- the follow's layout-generation check mirrors every
	// reset here for exactly that reason (see onNewLayoutGeneration).
	ensurePanelArt();
	// GeneralsX @bugfix Android port 21/10/2026 Round 7 addendum: the
	// merged layout declares only this init, so GroupPanelInit is never
	// called anymore -- call it from here so the group panel's per-match
	// state (row-expanded flag, hold timers) resets with the layout too.
	// Without this, a match that ended with the group row expanded left
	// the flag set while ControlBar::init hid the row, and the next
	// match's first "=" tap toggled it shut on the hidden row: a silent
	// no-op that looks exactly like a dead button.
	GroupPanelInit(layout, userData);
}

//-------------------------------------------------------------------------------------------------
void SelectPanelUpdate(WindowLayout *layout, void *userData)
{
	// The select buttons themselves need nothing per-frame (plain
	// GBM_SELECTED push buttons, handled in SelectPanelSystem below).
	// GeneralsX @feature Android port 21/10/2026 Round 6: the merged
	// layout also carries the group windows, whose hold-gesture visuals
	// (radial clock wipe while a press is being held) live in
	// GroupPanelUpdate() and used to run off the separate GroupPanel
	// layout's runUpdate() -- forward to it so they keep running.
	GroupPanelUpdate(layout, userData);
}

//-------------------------------------------------------------------------------------------------
void SelectPanelShutdown(WindowLayout *layout, void *userData)
{
	(void)userData;
	if (layout) {
		layout->hide(TRUE);
	}
}

//-------------------------------------------------------------------------------------------------
WindowMsgHandledType SelectPanelSystem(GameWindow *window, UnsignedInt msg,
																			 WindowMsgData mData1, WindowMsgData mData2)
{
	(void)window;
	(void)mData2;

	switch (msg) {

		case GWM_CREATE:
		{
			// Only hashes names here -- same reasoning as GroupPanelSystem:
			// for a WINDOWTYPE=USER window the real window id is assigned
			// after GWM_CREATE has already been dispatched, so nothing in
			// here may rely on winGetWindowId() of any window yet.
			cacheWidgetIDs();
			break;
		}

		case GWM_DESTROY:
			break;

		case GBM_SELECTED:
		{
			GameWindow *control = (GameWindow *)mData1;
			Int controlID = control->winGetWindowId();

			// GeneralsX @feature Android port 21/10/2026 Round 10: the
			// header's collapse arrow. It is a direct child of the layout
			// root, so its clicks are delivered to THIS system function
			// (a push button reports to its parent window's system
			// function -- the same createGadget owner rule behind the
			// Round 7 forward below, no forwarding needed here). Flip
			// the state and swap the arrow's face; the follow does the
			// actual hiding on its next call.
			if (controlID == s_collapseID) {
				s_columnCollapsed = !s_columnCollapsed;
				applyArrowFace();
				return MSG_HANDLED;
			}

			Int index = findSelectButtonIndex(controlID);
			if (index >= 0) {
				handleSelectButton(index);
				return MSG_HANDLED;
			}

			// GeneralsX @bugfix Android port 21/10/2026 Round 7: a push
			// button delivers its clicks to its PARENT window's system
			// function (createGadget sets the button's owner to its
			// parent), and since the Round 6 merge the "=" handle's
			// parent is SelectPanelParent -- this function -- instead of
			// GroupPanelParent, so its clicks landed here and were
			// silently dropped. Forward the group controls' messages to
			// GroupPanelSystem with the original arguments: exactly the
			// delivery they had before the merge. The digit buttons'
			// parent (GroupRow) kept GroupPanelSystem, so they do not
			// arrive here; they are covered anyway in case that changes.
			if (isGroupControlID(controlID)) {
				return GroupPanelSystem(window, msg, mData1, mData2);
			}
			break;
		}

		case GBM_SELECTED_RIGHT:
		{
			// Same forward for right / two-finger clicks: before the
			// merge these reached GroupPanelSystem through the handle's
			// parent as well. GroupPanelSystem force-assigns on the
			// digits here and ignores the handle, exactly as it did then.
			GameWindow *control = (GameWindow *)mData1;
			Int controlID = control->winGetWindowId();

			if (isGroupControlID(controlID)) {
				return GroupPanelSystem(window, msg, mData1, mData2);
			}
			break;
		}

	}

	return MSG_IGNORED;
}
