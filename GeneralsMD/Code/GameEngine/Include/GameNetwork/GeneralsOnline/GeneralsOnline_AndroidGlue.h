#pragma once

// Entry point for the Android Online-button handler.
bool TryStartGeneralsOnline();

// Returns the launcher-selected server id used by the native OnlineServices
// manager. Android uses "playgenerals" or "generalsx"; non-Android callers
// receive "generalsx" as the safe default.
const char* GeneralsOnline_GetSelectedServerId();
