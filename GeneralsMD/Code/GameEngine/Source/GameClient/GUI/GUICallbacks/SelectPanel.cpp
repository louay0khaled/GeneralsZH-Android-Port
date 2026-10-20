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
///////////////////////////////////////////////////////////////////////////////////////////////////

#include "PreRTS.h"

#include "Common/GXSafeArea.h"
#include "Common/NameKeyGenerator.h"
#include "GameClient/Color.h"
#include "GameClient/ControlBar.h"
#include "GameClient/Display.h"
#include "GameClient/GameText.h"
#include "GameClient/GameWindow.h"
#include "GameClient/GameWindowManager.h"
#include "GameClient/GUICallbacks.h"
#include "GameClient/InGameUI.h"
#include "GameClient/WindowLayout.h"

static NameKeyType s_buttonRowID = NAMEKEY_INVALID;
static NameKeyType s_buttonID[5];

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
}

//-------------------------------------------------------------------------------------------------
void SelectPanelInit(WindowLayout *layout, void *userData)
{
	(void)layout;
	(void)userData;
	// The layout is recreated for every match in the same process, so the
	// one-shot caption/tint state starts over with it (the follow
	// function re-applies both on the first visible frame).
	s_buttonTextApplied = FALSE;
	s_appliedBorderColor = GAME_COLOR_UNDEFINED;
	s_appliedActionColor = GAME_COLOR_UNDEFINED;
}

//-------------------------------------------------------------------------------------------------
void SelectPanelUpdate(WindowLayout *layout, void *userData)
{
	(void)layout;
	(void)userData;
	// Nothing per-frame: the buttons are plain GBM_SELECTED push buttons,
	// handled in SelectPanelSystem below. The update callback exists
	// because the layout names one (and ControlBar::update() runs it).
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
