#!/usr/bin/env bash
# Screenshots of the app for posts about it: DemoScreens on the e2e emulator, into app/build/screenshots/. Needs
# what scripts/e2e.sh needs. For the run the demo's projects live in ~/projects on this machine, so there must be none.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=app/build/screenshots
A=-Pandroid.testInstrumentationRunnerArguments
scripts/e2e.sh "$A.class=dev.artalex.herdr.e2e.DemoScreens" "$A.demoScreens=true"
rm -rf "$OUT" && mkdir -p "$OUT"
for shot in app/build/e2e-screens/demo-*.png; do
  cp "$shot" "$OUT/${shot##*/demo-}"
done
echo "screenshots: $OUT"
