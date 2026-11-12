#!/system/bin/sh
# Music ambient light (ambient-music-kr) diagnosis stick. Writes AMBIENT-DIAG/ (the one before is
# kept as AMBIENT-DIAG.1, .2, ...). Read only, apart from the light test in part 3:
#   - what is installed now (the mod, its app, HMG-API's vehicle service) and the owner's answers
#   - the logs that outlive an attempt: HMG-API's vehicle service (every ambient_light command and its
#     answer), HMG-API's log (the CarPlay mod's DiPO lines), ev6kr.usbap.sync's, what logcat holds now,
#     the audio service's state
#   - the light test: the owner's own ambient_light commands through HMG-API's vehicle service, no app,
#     no microphone (red, blue, brightness 10, brightness 3, green, then the owner's light back). When
#     no mod runs the vehicle service, it is started here for the test and stopped after it.
#     An AMBIENT-NO-LIGHT file next to this script skips the test.
# Leave the stick in until the head unit shows "무드램프 진단 완료" (about 1-2 minutes). Restart the head
# unit before using CarPlay again (the test's vehicle service registers listeners with car services).
# The logs can hold your phone's name and Bluetooth/Wi-Fi addresses: share them privately only.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib ANDROID_ROOT=/system ANDROID_DATA=/data
[ -n "$BOOTCLASSPATH" ] || export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
H=$(pwd)
O=$H/AMBIENT-DIAG
if [ -d "$O" ]; then
    N=1
    while [ -e "$O.$N" ]; do N=$((N + 1)); done
    mv "$O" "$O.$N" 2>/dev/null || rm -r "$O" 2>/dev/null
fi
mkdir -p "$O" || exit 1
S=$O/summary.txt
: > "$S"
say() { echo "$1" >> "$S"; }
grab() { [ -f "$1" ] && cat "$1" > "$O/$2" 2>/dev/null; true; }
toast() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
    true
}
stage() { say "[stage $1 done $(date)]"; sync; }
NOSERIAL='serialno|command line|SerialNumber'
PKG=kr.ev6.ambientmusic
MOD=ambient-music-kr

say "ev6kr-ambient-diag 1"
say "date $(date)"
say "up $(cat /proc/uptime)"
say "ro.build.product=$(getprop ro.build.product)"
toast "무드램프 진단 중: '진단 완료'가 뜰 때까지 스틱을 빼지 마세요"

# ---- 1: what is installed now
say ""
say "--- NEMESIS packages"
nemesis pkg-list >> "$S" 2>&1
say ""
say "--- HMG-API"
say "$(hmgapid ping 2>&1)"
hmgapid engine status >> "$S" 2>&1
say "mods folder:"
ls -l /data/hmgapi/mods >> "$S" 2>&1
say "the app: $(pm path $PKG 2>&1)"
dumpsys package $PKG 2>/dev/null | grep -E 'versionName|enabled=|pkgFlags|firstInstallTime|lastUpdateTime|grantedPermissions|android.permission' >> "$S"
for f in apps.txt commands.txt vehicle.txt services.txt services.status apps.json apps.installed; do
    [ -f /data/hmgapi/state/$f ] && { say "== state/$f"; cat /data/hmgapi/state/$f >> "$S"; }
done
say "== owner's answers (settings/allow)"
ls -l /data/hmgapi/settings/allow >> "$S" 2>&1
say "== processes"
ps 2>/dev/null | grep -E 'app_process|ambientmusic|hmgapi|dipo|iap2|logcat' >> "$S"
for p in /proc/[0-9]*; do
    case "$(cat $p/cmdline 2>/dev/null)" in *hmgapi.vehicle.Hub*) say "vehicle service running: pid ${p##*/}" ;; esac
done
stage 1

# ---- 2: the logs that outlive an attempt, the audio service now
mkdir -p "$O/hmgapi-logs"
for f in /data/hmgapi/logs/hmgapi/*; do
    [ -f "$f" ] || continue
    case "${f##*/}" in micom.log*) continue ;; esac   # the MICOM recorder's frames: not needed here
    grep -v -i -E "$NOSERIAL" "$f" > "$O/hmgapi-logs/${f##*/}" 2>/dev/null
done
[ -d /data/hmgapi/logs/$MOD ] && { mkdir -p "$O/mod-logs"; for f in /data/hmgapi/logs/$MOD/*; do [ -f "$f" ] && cat "$f" > "$O/mod-logs/${f##*/}"; done; }
ls -l /data/hmgapi/logs/hmgapi /data/hmgapi/logs >> "$S" 2>&1
grab /data/nemesis/ev6kr.usbap.sync.log apsync.log
grab /data/nemesis/ev6kr.usbap.sync.log.old apsync.old.log
D=/data/nemesis/ev6kr.usbap.sync.logcat.txt
for n in "" .1 .2 .3 .4; do
    [ -f "$D$n" ] && grep -v -i -E "$NOSERIAL" "$D$n" > "$O/debug-logcat$n.txt" 2>/dev/null
done
logcat -d -v time 2>&1 | grep -v -i -E "$NOSERIAL" > "$O/logcat-now.txt"
dumpsys media.audio_flinger > "$O/audio-flinger.txt" 2>&1
dumpsys audio > "$O/audio.txt" 2>&1
dumpsys activity services $PKG > "$O/app-services.txt" 2>&1
stage 2

# ---- 3: the light test (the owner's own commands: no question on screen, nothing kept)
if [ -f "$H/AMBIENT-NO-LIGHT" ]; then
    say "light test skipped (AMBIENT-NO-LIGHT)"
else
    say ""
    say "--- light test"
    VPID=""
    hmgapid vehicle gear.park > "$O/vehicle-check.txt" 2>&1
    RC=$?
    say "vehicle service check: rc $RC, $(cat "$O/vehicle-check.txt")"
    if [ "$RC" = 69 ]; then
        say "no mod runs the vehicle service: starting it for the test"
        CLASSPATH=/system/framework/hmgapi-hook.jar app_process /system/bin hmgapi.vehicle.Hub > "$O/vehicle-test.log" 2>&1 &
        VPID=$!
        W=0
        while [ $W -lt 30 ]; do
            case "$(cat "$O/vehicle-test.log" 2>/dev/null)" in *listening*) break ;; esac
            sleep 1
            W=$((W + 1))
        done
        say "vehicle service pid $VPID, up after ${W} s"
        sleep 5   # the car reports the owner's light (requestSMLStatus) so the release can put it back
    fi
    say "readings: $(hmgapid vehicle moving gear.park speed 2>&1)"
    step() {   # <what to look for> <args...>
        local what=$1
        shift
        toast "무드램프 시험: $what"
        say "ambient_light $* -> $(hmgapid vehicle-do ambient_light "$@" 2>&1) ($(date))"
        sleep 4
    }
    step "빨간색으로 바뀌는지 보세요" color ff321d
    step "파란색으로 바뀌는지 보세요" color 1043f4
    step "가장 밝게 (10)" brightness 10
    step "어둡게 (3)" brightness 3
    step "초록색으로 바뀌는지 보세요" color 68dd62
    step "원래 조명으로 돌아오는지 보세요" release
    if [ -n "$VPID" ]; then
        kill "$VPID" 2>/dev/null
        sleep 1
        say "vehicle service for the test stopped"
    fi
fi
stage 3
say "end"
sync
toast "무드램프 진단 완료: 스틱을 빼고, 카플레이를 쓰기 전에 헤드유닛을 재시작해 주세요"
exit 0
