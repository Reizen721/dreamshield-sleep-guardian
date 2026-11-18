#!/bin/bash
# Run on a Mac with Xcode 16+ and an installed iOS Simulator runtime.
# Usage: bash scripts/verify_build.sh [SIMULATOR_UDID]
# No account, device, license acceptance or SDK installation is automated.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Xcode compilation requires macOS; use validate_project.py for static checks on Windows." >&2
  exit 2
fi
if ! xcrun --find xcodebuild >/dev/null 2>&1; then
  echo "Select a full Xcode installation in Xcode > Settings > Locations first." >&2
  exit 2
fi
python3 scripts/validate_project.py
python3 -m unittest discover -s scripts -p 'test_*.py' -v
xcodebuild -project DreamShield.xcodeproj -scheme DreamShield -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO build
if [[ -n "${1:-}" ]]; then
  xcodebuild -project DreamShield.xcodeproj -scheme DreamShield -configuration Debug \
    -destination "platform=iOS Simulator,id=$1" -derivedDataPath build/DerivedData \
    CODE_SIGNING_ALLOWED=NO test
else
  echo "Build complete. XCTest was NOT run. Obtain an installed simulator UDID from:"
  echo "  xcrun simctl list devices available"
  echo "Then run: bash scripts/verify_build.sh SIMULATOR_UDID"
fi
