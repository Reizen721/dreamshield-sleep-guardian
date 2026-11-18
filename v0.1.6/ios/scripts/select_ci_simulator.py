"""Choose an existing compatible iPad simulator; never download extra runtimes."""
import json
import re
import sys


def version_tuple(value):
    if not re.fullmatch(r"\d+(?:\.\d+){0,2}", value):
        raise ValueError("Invalid SDK version")
    parts = tuple(int(part) for part in value.split("."))
    return parts + (0,) * (3 - len(parts))


def choose_ipad(payload, sdk_version):
    maximum = version_tuple(sdk_version)
    candidates = []
    for runtime, devices in payload.get("devices", {}).items():
        match = re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-(\d+(?:-\d+){0,2})", runtime)
        if not match:
            continue
        runtime_version = version_tuple(match.group(1).replace("-", "."))
        if not (version_tuple("17") <= runtime_version <= maximum):
            continue
        for device in devices:
            name = device.get("name", "")
            udid = device.get("udid", "")
            if (device.get("isAvailable") is True and name.startswith("iPad")
                    and re.fullmatch(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", udid)):
                candidates.append((runtime_version, name, udid))
    if not candidates:
        raise ValueError("No installed iPad simulator compatible with this Xcode SDK and minimum iPadOS 17")
    return sorted(candidates)[-1][2]


if __name__ == "__main__":
    try:
        print(choose_ipad(json.load(sys.stdin), sys.argv[1]))
    except (ValueError, IndexError, TypeError) as error:
        raise SystemExit(f"Simulator selection failed: {error}") from error
