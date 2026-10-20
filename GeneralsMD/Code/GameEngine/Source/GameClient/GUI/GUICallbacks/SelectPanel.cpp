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
// Unit-category selection touch panel: a row of five always-visible
// buttons docked next to the control-group panel (GroupPanel.wnd, the
// template this file follows) that REPLACE the current selection with
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
///////////////////////////////////////////////////////////////////////////////////////////////////

#include "PreRTS.h"

#include "Common/NameKeyGenerator.h"
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

// GeneralsX @feature Android port 20/10/2026 Follow-the-bar pairing, same
// calibrate-once design as GroupPanel's (see GroupPanel.cpp for the full
// rationale): the offset between this panel's button row and the real
// ControlBarParent is captured once in ControlBar::init(), while both are
// still at their authored resting positions, and is then tracked every
// frame so the panel slides with the bar. Only the row needs moving --
// the five buttons are its children and move with it, and the layout's
// parent window is an invisible no-input frame, same as GroupPanelParent.
// Unlike the group panel there is no extra GXSafeArea inset here: that one
// exists because the group panel's handle sits at the bar's left edge,
// under the display cutout, while this row is authored mid-screen.
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
	GameWindow *row = TheWindowManager->winGetWindowFromId(nullptr, s_buttonRowID);
	if (!row) {
		return;
	}

	Int targetX = barScreenX + s_followOffsetX;
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
