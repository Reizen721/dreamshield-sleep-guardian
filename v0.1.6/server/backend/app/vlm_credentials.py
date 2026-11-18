"""Optional local key-file support for the desktop host; never logs credentials."""
from pathlib import Path


def key_from_file(filename):
    if not filename:
        return ""
    try:
        path = Path(filename)
        if not path.is_absolute() or not path.is_file() or path.stat().st_size > 8192:
            return ""
        with path.open("r", encoding="utf-8-sig") as stream:
            key = stream.read(8193).strip()
        if (not key or len(key) > 4096 or any(c.isspace() for c in key)
                or '"' in key or "'" in key or key == "PASTE_YOUR_API_KEY_HERE"
                or key.startswith(("DEEPSEEK_API_KEY=", "OPENAI_API_KEY=", "VLM_API_KEY="))):
            return ""
        return key
    except (OSError, UnicodeError, ValueError):
        return ""
