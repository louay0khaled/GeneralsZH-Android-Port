#pragma once

#include "win.h"

// Prepares UTF-16 text for the legacy per-glyph renderer used by the Android
// port. Arabic needs contextual shaping and right-to-left visual ordering
// before the renderer can draw it one code point at a time.
//
// The returned pointer is valid until the next call on the same thread.
// Non-Arabic strings are returned unchanged.
const WCHAR *GeneralsX_Prepare_Arabic_RTL(const WCHAR *text);
