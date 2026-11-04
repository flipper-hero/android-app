#!/usr/bin/env bash
set -euo pipefail

artifact=${1:?Pass the Play APK or app bundle}
unzip -tq "$artifact" >/dev/null
case "$artifact" in
    *.apk) dex_pattern='classes*.dex' ;;
    *.aab) dex_pattern='base/dex/*.dex' ;;
    *) echo 'Expected an APK or AAB' >&2; exit 1 ;;
esac
unzip -Z1 "$artifact" | grep -E '\.dex$' >/dev/null
dex_text=$(unzip -p "$artifact" "$dex_pattern" | strings)
if printf '%s\n' "$dex_text" | grep -E 'badusb_execute|rpc_raw|set_engagement_mode|generate_engagement_report|marauder_bridge\.fap'; then
    echo 'Full-edition tools found in the Play artifact:' >&2
    exit 1
fi
echo 'Play artifact excludes full-edition tools.'
