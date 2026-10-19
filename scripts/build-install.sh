#!/bin/sh
# Build the relay APK and install it on the TV over ADB, re-apply the permissions TCL's firmware needs, start it.
# Usage (Git Bash, from the repo root):  scripts/build-install.sh
# Env: ADB (adb binary), TV (adb address), JAVA_HOME (JDK 17).
set -e
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"; TV="${TV:-192.168.88.17:5555}"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot}"
A="$ADB -s $TV"
$ADB connect "$TV" >/dev/null
./gradlew --offline -q assembleRelease 2>&1 | grep -v '^warning' || true
$A push app/build/outputs/apk/release/app-release.apk /data/local/tmp/relay.apk >/dev/null
$A shell 'pm install -r /data/local/tmp/relay.apk; rm /data/local/tmp/relay.apk
dumpsys deviceidle whitelist +com.gathin.relay >/dev/null
appops set com.gathin.relay APP_AUTO_START allow; appops set com.gathin.relay SYSTEM_ALERT_WINDOW allow
appops set com.gathin.relay APP_ASSOC_START allow
cmd appops set com.gathin.relay RUN_IN_BACKGROUND allow; cmd appops set com.gathin.relay RUN_ANY_IN_BACKGROUND allow
am broadcast -n com.gathin.relay/.BootReceiver >/dev/null; sleep 2
dumpsys activity services com.gathin.relay | grep -o "isForeground=[a-z]*"'
# Lets this PC reach the relay's TV-only paths at http://127.0.0.1:7000 for testing.
$A forward tcp:7000 tcp:7000 >/dev/null
