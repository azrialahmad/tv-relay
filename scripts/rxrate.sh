#!/bin/sh
# Per-second Wi-Fi receive rate on the TV (Mbps), then the average. Usage: scripts/rxrate.sh <seconds>
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"; TV="${TV:-192.168.88.17:5555}"
$ADB -s "$TV" shell "p=\$(awk '/wlan0/{print \$2}' /proc/net/dev); s=\$p; for i in \$(seq 1 $1); do sleep 1; c=\$(awk '/wlan0/{print \$2}' /proc/net/dev); echo \$(( (c-p)*8/1000000 )); p=\$c; done; echo avg \$(( (c-s)*8/1000000/$1 ))" | tr '\n' ' '; echo
