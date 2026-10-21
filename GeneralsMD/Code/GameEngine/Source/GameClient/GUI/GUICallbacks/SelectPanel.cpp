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
#include "GameClient/GameWindow.h"
#include "GameClient/GameWindowManager.h"
#include "GameClient/GUICallbacks.h"
#include "GameClient/Image.h"
#include "GameClient/InGameUI.h"
#include "GameClient/WindowLayout.h"

static NameKeyType s_buttonRowID = NAMEKEY_INVALID;
static NameKeyType s_buttonID[5];
static NameKeyType s_backgroundID = NAMEKEY_INVALID;
// GeneralsX @feature Android port 21/10/2026 Round 6: the merged group
// windows, cached here as well (GroupPanel.cpp keeps its own copies for
// its logic) because this file now moves and tints them with the column.
static NameKeyType s_groupHandleID = NAMEKEY_INVALID;
static NameKeyType s_groupRowID = NAMEKEY_INVALID;
static NameKeyType s_buttonGroupID[10];

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
	s_backgroundID = TheNameKeyGenerator->nameToKey("SelectPanel.wnd:PanelBackground");
	s_groupHandleID = TheNameKeyGenerator->nameToKey("GroupPanel.wnd:ButtonHandle");
	s_groupRowID = TheNameKeyGenerator->nameToKey("GroupPanel.wnd:GroupRow");
	char buf[40];
	for (Int i = 0; i < 10; ++i) {
		sprintf(buf, "GroupPanel.wnd:ButtonGroup%d", i);
		s_buttonGroupID[i] = TheNameKeyGenerator->nameToKey(buf);
	}
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

// GeneralsX @feature Android port 21/10/2026 Round 6: tray-art tracking --
// the last "<Side>ProTraySmall" name we tried to resolve for this layout
// ("" = none tried yet), and whether it was found and set. Keyed on the
// NAME, not on success, so an install without ControlBar Pro pays the
// lookup once per faction instead of per frame; SelectPanelInit clears it
// because a recreated layout's background window starts imageless again.
static char s_lastTrayAttempt[64] = "";
static Bool s_trayArtApplied = FALSE;

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

// ControlBar Pro's per-faction tray image name, prefix-matched from the
// live scheme side so sub-general sides resolve to their parent faction's
// art: "America*"/"China*"/"GLA*" -> "AmericaProTraySmall" /
// "ChinaProTraySmall" / "GlaProTraySmall" (the exact MappedImage names from
// <Faction>PowersPro.ini in the Pro archive). Any other side yields "" --
// no tray art applies and the authored flat fill stays.
static void resolveTrayImageName(char *out)
{
	out[0] = '\0';
	AsciiString side = currentLocalPlayerSide();
	const char *prefix = nullptr;
	if (strncmp(side.str(), "America", 7) == 0) {
		prefix = "America";
	} else if (strncmp(side.str(), "China", 5) == 0) {
		prefix = "China";
	} else if (strncmp(side.str(), "GLA", 3) == 0) {
		prefix = "Gla";
	}
	if (prefix) {
		sprintf(out, "%sProTraySmall", prefix);
	}
}

// GeneralsX @feature Android port 21/10/2026 Round 6: this is now the
// panel's whole scheme-ART refresh, not just its tint: the live scheme
// border colors go on the column AND on the merged group windows, and the
// background window gets the live faction's Pro tray image when the game
// data provides one. Still fully change-driven -- once the colors are
// applied and the tray name is settled, a call costs two color compares
// and one string compare.
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
	char trayName[64];
	resolveTrayImageName(trayName);
	if (borderColor == s_appliedBorderColor && actionColor == s_appliedActionColor
		&& strcmp(trayName, s_lastTrayAttempt) == 0) {
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

	// GeneralsX @feature Android port 21/10/2026 Round 6: tray art. Resolve
	// the live faction's ProTraySmall against the mapped-image collection
	// (the .big archives mounted at startup provide it when ControlBar Pro
	// is installed) and set it as the background window's slot-0 enabled
	// image -- the same construction as Pro's own GenPowersShortcutBar
	// parent. Not found (no Pro, or a side with no tray): the authored
	// NoImage + flat fill stays, and if a previous faction's tray is still
	// set it is cleared back to that. The attempt is recorded either way
	// (see s_lastTrayAttempt above), so this runs once per faction.
	if (strcmp(trayName, s_lastTrayAttempt) != 0) {
		GameWindow *background = TheWindowManager->winGetWindowFromId(nullptr, s_backgroundID);
		if (background) {
			const Image *tray = nullptr;
			if (trayName[0] != '\0' && TheMappedImageCollection) {
				tray = TheMappedImageCollection->findImageByName(trayName);
			}
			if (tray) {
				background->winSetEnabledImage(0, tray);
				s_trayArtApplied = TRUE;
			} else if (s_trayArtApplied) {
				background->winSetEnabledImage(0, nullptr);
				s_trayArtApplied = FALSE;
			}
			strcpy(s_lastTrayAttempt, trayName);
		}
	}

	s_appliedBorderColor = borderColor;
	s_appliedActionColor = actionColor;
}

// GeneralsX @feature Android port 20/10/2026 Follow-the-bar pairing, same
// calibrate-once design as GroupPanel's (see GroupPanel.cpp for the full
// rationale): the offset between this panel's button column and the real
// ControlBarParent is captured once in ControlBar::init(), while both are
// still at their authored resting positions, and is then tracked every
// frame so the panel slides with the bar. Only the column needs moving --
// the five buttons are its children and move with it, and the layout's
// parent window is an invisible no-input frame, same as GroupPanelParent.
// GeneralsX @feature Android port 21/10/2026 Round 5: the column now sits
// on the left edge like the group panel does, so the follow math gains
// the same display-cutout inset GroupPanel applies
// (GXSafeArea::leftPx() plus a little) -- the round-4 row was authored
// mid-screen and deliberately had none.
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

void SelectPanelFollowControlBar(Int barScreenX, Int barScreenY, Bool visible)
{
	if (!visible || !s_followOffsetCalibrated) {
		return;
	}
	if (!TheWindowManager) {
		return;
	}

	// Round 5 (21/10/2026): first visible frame applies the localized
	// captions and the live scheme tint; both are no-ops afterwards
	// unless the scheme colors change (see the helpers above).
	applyButtonTextOnce();
	refreshSchemeTint();

	GameWindow *row = TheWindowManager->winGetWindowFromId(nullptr, s_buttonRowID);
	if (!row) {
		return;
	}

	Int targetX = barScreenX + s_followOffsetX + GXSafeArea::leftPx() + TheDisplay->getWidth() / 100;
	Int targetY = barScreenY + s_followOffsetY;

	Int curX, curY;
	row->winGetScreenPosition(&curX, &curY);
	Int dx = targetX - curX;
	Int dy = targetY - curY;
	if (dx == 0 && dy == 0) {
		return;
	}

	Int rx, ry;
	row->winGetPosition(&rx, &ry);
	row->winSetPosition(rx + dx, ry + dy);

	// GeneralsX @feature Android port 21/10/2026 Round 6: the merged group
	// windows are the column's siblings under SelectPanelParent, so they
	// do not inherit its motion the way the five buttons (its children)
	// do -- translate them by the same delta, which is exactly what
	// GroupPanelFollowControlBar used to do for its own layout. Their
	// authored positions (and so their offset to the column) are fixed,
	// so one delta keeps the whole merged panel rigid.
	GameWindow *groupHandle = TheWindowManager->winGetWindowFromId(nullptr, s_groupHandleID);
	if (groupHandle) {
		Int hx, hy;
		groupHandle->winGetPosition(&hx, &hy);
		groupHandle->winSetPosition(hx + dx, hy + dy);
	}
	GameWindow *groupRow = TheWindowManager->winGetWindowFromId(nullptr, s_groupRowID);
	if (groupRow) {
		Int gx, gy;
		groupRow->winGetPosition(&gx, &gy);
		groupRow->winSetPosition(gx + dx, gy + dy);
	}
}

//-------------------------------------------------------------------------------------------------
void SelectPanelInit(WindowLayout *layout, void *userData)
{
	(void)layout;
	(void)userData;
	// The layout is recreated for every match in the same process, so the
	// one-shot caption/tint/tray state starts over with it (the follow
	// function re-applies all of it on the first visible frame).
	s_buttonTextApplied = FALSE;
	s_appliedBorderColor = GAME_COLOR_UNDEFINED;
	s_appliedActionColor = GAME_COLOR_UNDEFINED;
	s_lastTrayAttempt[0] = '\0';
	s_trayArtApplied = FALSE;
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

			Int index = findSelectButtonIndex(controlID);
			if (index >= 0) {
				handleSelectButton(index);
				return MSG_HANDLED;
			}
			break;
		}

	}

	return MSG_IGNORED;
}
