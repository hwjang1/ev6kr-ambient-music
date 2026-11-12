#!/system/bin/sh
# EV6 KR ambient light: try the car's own ambient light path (IUSMService.setAmbientLight, what the
# vehicle settings app sends on 260902), nothing installed. Writes AMBIENT-USM/ (the one before is
# kept as AMBIENT-USM.1, ...):
#   - the vehicle settings app's files, to work out how it drives the light (a manufacturer app: keep
#     the copy private)
#   - system settings whose names look like the ambient light's (where the owner's colour may be kept)
#   - the USM ambient light switches and dual light values, before and after
#   - the test: red (brightness 10), blue (10), green (5), purple (3), white (10), each held 6 s, with
#     the matching logcat lines (USMService, SystemService, MicomD)
# Leave the stick in until the head unit shows "USM 시험 끝" (about 1 minute). The owner's own colour
# isn't put back (the car doesn't report it): pick it again in the vehicle settings afterwards.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib ANDROID_ROOT=/system ANDROID_DATA=/data
[ -n "$BOOTCLASSPATH" ] || export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
H=$(pwd)
O=$H/AMBIENT-USM
if [ -d "$O" ]; then
    N=1
    while [ -e "$O.$N" ]; do N=$((N + 1)); done
    mv "$O" "$O.$N" 2>/dev/null || rm -r "$O" 2>/dev/null
fi
mkdir -p "$O" || exit 1
S=$O/summary.txt
: > "$S"
say() { echo "$1" >> "$S"; }
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
    true
}
stage() { say "[stage $1 done $(date)]"; sync; }
probe() { CLASSPATH=$H/usmprobe.jar app_process /system/bin UsmProbe "$@" >> "$S" 2>&1; }

say "ev6kr-ambient-usm 1"
say "date $(date)"
say "ro.build.product=$(getprop ro.build.product)"
toast "USM 무드램프 시험 준비 중: '시험 끝'이 뜰 때까지 스틱을 빼지 마세요"

# ---- 1: the vehicle settings app and settings that may hold the owner's colour
P=$(pm path com.android.vehiclesettings 2>/dev/null)
P=${P#package:}
say "vehicle settings app: $P"
if [ -f "$P" ]; then
    mkdir -p "$O/vehiclesettings"
    cat "$P" > "$O/vehiclesettings/${P##*/}"
    [ -f "${P%.apk}.odex" ] && cat "${P%.apk}.odex" > "$O/vehiclesettings/${P##*/}.odex"
    ls -l "$O/vehiclesettings" >> "$S" 2>&1
fi
content query --uri content://settings/system 2>/dev/null | grep -i -E 'ambient|mood|lamp|light|colou?r|sml|usm' > "$O/settings-system.txt"
say "system settings that look like the ambient light's: $(grep -c . "$O/settings-system.txt") (settings-system.txt)"
stage 1

# ---- 2: the test
logcat -v time USMService:V USMManager:V SystemService:V MicomD:V AndroidRuntime:E '*:S' > "$O/logcat-test.txt" 2>&1 &
LP=$!
say ""
say "--- before"
probe dump
step() {   # <what to look for> <r> <g> <b> <brightness>
    local what=$1
    shift
    toast "USM 시험: $what"
    say "$(date) $what"
    probe set "$@"
    sleep 6
}
step "빨간색 (밝기 10)" 255 50 29 10
step "파란색 (밝기 10)" 16 67 244 10
step "초록색 (밝기 5)" 104 221 98 5
step "보라색 (밝기 3)" 155 33 239 3
step "흰색 (밝기 10)" 255 255 255 10
say ""
say "--- after"
probe dump
kill "$LP" 2>/dev/null
stage 2
say "end"
sync
toast "USM 시험 끝: 스틱을 빼고, 차량 설정에서 원래 무드램프 색을 다시 골라 주세요"
exit 0
