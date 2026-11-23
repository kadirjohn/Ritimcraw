#!/bin/bash
# White-window device replay: pair + connect + install the current debug APK
# + capture ritim-trace into ww-replay.log (project root) for analysis.
# Usage: tools/ww-device-replay.sh <pairHost:port> <pairCode> <connectHost:port>
set -u
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
cd "$(dirname "$0")/.."
PAIR="${1:-}"; CODE="${2:-}"; CONN="${3:-}"
if [ -n "$PAIR" ] && [ -n "$CODE" ]; then "$ADB" pair "$PAIR" "$CODE"; fi
if [ -n "$CONN" ]; then
  for i in 1 2 3; do "$ADB" connect "$CONN" | tee /dev/stderr | grep -q "connected" && break; sleep 2; done
fi
"$ADB" devices -l
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
"$ADB" logcat -c
"$ADB" shell getprop ro.product.model > ww-replay.log
"$ADB" shell dumpsys SurfaceFlinger | grep -i "GLES:" >> ww-replay.log
"$ADB" shell monkey -p com.ritim.ritimcraw -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo ">>> Uygulama acildi. GPU modunda, zebra KAPALI, genis (16:9) klibi ac ve 10 sn oynat; sonra Ctrl+C."
"$ADB" logcat -v time -s ritim-trace:* RitimPreview:* AndroidRuntime:E | tee -a ww-replay.log
