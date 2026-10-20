#!/usr/bin/env bash
# End-to-end tests: the app on an emulator connects over SSH to herdr on this machine.
# `--release` tests the minified, signed release build instead of the debug one; other arguments go to Gradle.
#
# Needs: a herdr binary, the AVD (default herdr-e2e) and an SSH key that this machine's sshd accepts
# from localhost only (`from="127.0.0.1,::1"` in authorized_keys): the emulator reaches the host as
# 10.0.2.2 and its connections arrive from 127.0.0.1. The key travels as an instrumentation argument,
# so use a dedicated test key, never a real one.
set -euo pipefail
cd "$(dirname "$0")/.."

build=debug
if [ "${1:-}" = "--release" ]; then
  build=release
  shift
fi

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
HERDR="${HERDR_BIN:-$HOME/.local/bin/herdr}"
KEY="${HERDR_SSH_TEST_KEY:-$HOME/.ssh/herdr_android_test}"
AVD="${HERDR_AVD:-herdr-e2e}"
# The second session stands for another server in the tests with several servers.
SESSIONS=(hdr-e2e hdr-e2e2)
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
# Inside a herdr pane these would point the test server at the user's own session.
unset HERDR_ENV HERDR_SOCKET_PATH HERDR_PANE_ID HERDR_TAB_ID HERDR_WORKSPACE_ID HERDR_BIN_PATH

started_emulator=0
emulator_pid=
herdr_pids=()
cleanup() {
  for i in "${!herdr_pids[@]}"; do
    "$HERDR" --session "${SESSIONS[$i]}" server stop >/dev/null 2>&1 || true
    wait "${herdr_pids[$i]}" 2>/dev/null || true
    "$HERDR" session delete "${SESSIONS[$i]}" >/dev/null 2>&1 || true
  done
  if [ "$started_emulator" = 1 ]; then
    "$ADB" emu kill >/dev/null 2>&1 || true
    # The next run can start the AVD only once this emulator has let go of it.
    wait "$emulator_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# An emulator only: adb may also know a phone paired over Wi-Fi debugging, and Gradle installs the test APKs on
# every device it sees. adb and Gradle both take the device from ANDROID_SERIAL; set it to pick one yourself.
running_emulator() { "$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'; }
if [ -z "${ANDROID_SERIAL:-}" ]; then
  ANDROID_SERIAL=$(running_emulator)
  if [ -z "$ANDROID_SERIAL" ]; then
    # No window and software rendering: the machine's GPU may be busy with other work.
    "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot >/dev/null 2>&1 &
    emulator_pid=$!
    started_emulator=1
    until ANDROID_SERIAL=$(running_emulator); [ -n "$ANDROID_SERIAL" ]; do
      kill -0 "$emulator_pid" 2>/dev/null || { echo "the emulator for $AVD exited before it came up" >&2; exit 1; }
      sleep 2
    done
  fi
fi
export ANDROID_SERIAL
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
# The tests need the screen on and unlocked: it times out during a long build, and the notification tests lock it.
"$ADB" shell svc power stayon true
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell wm dismiss-keyguard

# agent.start runs `claude` from the pane shell's PATH. Tests get a line echo started as argv0 `claude`,
# which herdr recognizes as Claude, instead of a real session.
fakebin="$HOME/.cache/herdr-android-e2e/bin"
mkdir -p "$fakebin"
cat > "$fakebin/claude" <<'EOF'
#!/usr/bin/env bash
exec -a claude python3 -u -c 'import sys
for line in sys.stdin:
    sys.stdout.write(line)
    sys.stdout.flush()'
EOF
chmod +x "$fakebin/claude"

for session in "${SESSIONS[@]}"; do
  PATH="$fakebin:$PATH" "$HERDR" --session "$session" server >/dev/null 2>&1 &
  herdr_pids+=($!)
  for _ in $(seq 1 50); do
    "$HERDR" --session "$session" status --json 2>/dev/null | grep -q '"running":true' && break
    sleep 0.2
  done
done

fingerprint=$(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | awk '{print $2}')
"$ADB" shell rm -rf /data/local/tmp/herdr-e2e
A=-Pandroid.testInstrumentationRunnerArguments
status=0
./gradlew "connected${build^}AndroidTest" "-Pherdr.testBuildType=$build" \
  "$A.sshHost=10.0.2.2" "$A.sshPort=22" "$A.sshUser=${USER:-$(id -un)}" "$A.herdrSession=${SESSIONS[0]}" "$A.herdrSession2=${SESSIONS[1]}" \
  "$A.hostKeyFingerprint=$fingerprint" "$A.sshKey=$(base64 -w0 "$KEY")" "$A.sshPub=$(base64 -w0 "$KEY.pub")" "$@" || status=$?

# Screenshots taken by the tests (see Screens.kt), for a look after the run.
rm -rf app/build/e2e-screens && mkdir -p app/build/e2e-screens
"$ADB" pull /data/local/tmp/herdr-e2e/. app/build/e2e-screens >/dev/null 2>&1 || true

# A crashed instrumentation still ends in BUILD SUCCESSFUL, only with no tests run: count that as a failure.
ran=0
for n in $(grep -ho '<testsuite [^>]*' app/build/outputs/androidTest-results/connected/"$build"/*.xml 2>/dev/null | grep -o ' tests="[0-9]*"' | tr -dc '0-9\n'); do
  ran=$((ran + n))
done
if [ "$status" = 0 ] && [ "$ran" = 0 ]; then
  echo "e2e: no tests ran; did the instrumentation crash? See adb logcat." >&2
  status=1
fi
exit "$status"
