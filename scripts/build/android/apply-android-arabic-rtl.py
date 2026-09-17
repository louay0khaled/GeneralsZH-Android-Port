#!/usr/bin/env python3
"""Apply the Android Arabic RTL hook to the legacy sentence renderer.

The Android renderer consumes one WCHAR at a time. This hook prepares Arabic
text once (contextual forms + visual ordering) before the existing layout and
font pipeline runs. The edit is intentionally small and idempotent so it can
be applied from CMake or the Android build helper on every configure.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "Core/Libraries/Source/WWVegas/WW3D2/render2dsentence.cpp"
MARKER = '#include "arabicrtl.h"'


def main() -> int:
    source = SOURCE.read_text(encoding="utf-8")

    if MARKER in source:
        print(f"Arabic RTL hook already applied: {SOURCE}")
        return 0

    android_anchor = "#if defined(__ANDROID__)\n"
    if android_anchor not in source:
        raise SystemExit(
            "ERROR: expected __ANDROID__ include anchor not found; "
            "render2dsentence.cpp changed upstream"
        )

    include_block = android_anchor + MARKER + "\n"
    source = source.replace(android_anchor, include_block, 1)

    call_old = """if(Centered && (WrapWidth > 0 || wcschr(text,L'\\n')))\n\t\tBuild_Sentence_Centered(text, hkX, hkY);\n\telse\n\t\tBuild_Sentence_Not_Centered(text, hkX, hkY);"""

    call_new = """// GeneralsX @feature Android Arabic RTL 17/09/2026\n\t// The legacy renderer is deliberately left unchanged: prepare a visual\n\t// UTF-16 string first, then feed that string through the normal layout,\n\t// wrapping and glyph-atlas code.\n\tconst WCHAR *prepared_text = GeneralsX_Prepare_Arabic_RTL(text);\n\tif (prepared_text == nullptr) {\n\t\tprepared_text = text;\n\t}\n\n\tif(Centered && (WrapWidth > 0 || wcschr(prepared_text,L'\\n')))\n\t\tBuild_Sentence_Centered(prepared_text, hkX, hkY);\n\telse\n\t\tBuild_Sentence_Not_Centered(prepared_text, hkX, hkY);"""

    count = source.count(call_old)
    if count != 1:
        raise SystemExit(
            f"ERROR: expected exactly one Build_Sentence dispatch block, found {count}"
        )

    source = source.replace(call_old, call_new, 1)
    SOURCE.write_text(source, encoding="utf-8", newline="")

    print(f"Applied Android Arabic RTL hook: {SOURCE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
