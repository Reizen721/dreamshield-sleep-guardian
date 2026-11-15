"""Separate bundled read-only resources from user-owned writable data."""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Mapping


def resolve_data_dir(resource_dir: Path, environment: Mapping[str, str] | None = None,
                     *, frozen: bool | None = None) -> Path:
    environment = os.environ if environment is None else environment
    override = environment.get("DREAMSHIELD_DATA_DIR", "").strip()
    if override:
        path = Path(override).expanduser()
        if not path.is_absolute():
            raise ValueError("DREAMSHIELD_DATA_DIR must be an absolute path")
        return path.resolve()

    is_frozen = bool(getattr(sys, "frozen", False)) if frozen is None else frozen
    if is_frozen:
        # A frozen executable must never create personal data inside its
        # read-only bundle, extraction directory or installation directory.
        local = environment.get("LOCALAPPDATA", "").strip()
        base = Path(local) if local and Path(local).is_absolute() else Path.home() / "AppData" / "Local"
        return (base / "DreamShield" / "data").resolve()

    # Preserve the established development/server data directory unchanged.
    return Path(resource_dir).resolve() / "data"
