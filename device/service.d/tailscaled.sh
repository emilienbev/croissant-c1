#!/system/bin/sh
#
# /data/adb/service.d/tailscaled.sh  —  Magisk late_start boot hook.
#
# Runs tailscaled as a system daemon, deliberately independent of any app:
# if the R1 app breaks, the tailnet stays up and the device stays reachable.
#
# Split routing only. Tailscale's table 52 holds peer routes, not a default
# route, so only tailnet-destined traffic enters the tunnel; everything else
# keeps using Android's normal wifi path. No exit node, no subnet routes.

export PATH=/sbin:/system/bin:/system/xbin:/vendor/bin:$PATH

# Magisk runs service.d scripts under BusyBox in standalone ASH mode, where
# BusyBox applets SHADOW everything on PATH. BusyBox's `ip` applet cannot
# resolve routing-table names ("invalid argument 'wlan0' to 'table ID'") and
# does not understand `mark` on `route get`. Both failures are silent-ish and
# only happen at boot, never when testing by hand. Always use the real binary.
IP=/system/bin/ip

# tailscaled picks its log-state dir from os.UserCacheDir(), i.e. $HOME/.cache.
# service.d runs with HOME=/ in the GLOBAL mount namespace, where / is a
# read-only rootfs, so that path is uncreatable and tailscaled dies with
# "panic: no safe place found to store log state". It does NOT fail when run by
# hand, because `su` puts you in Magisk's namespace where / is writable — so
# this only ever reproduces on a real boot. Pin it explicitly instead.
export TS_LOGS_DIR=/data/adb/tailscale
export HOME=/data/adb/tailscale

TSDIR=/data/adb/tailscale
LOG=$TSDIR/tailscaled.log
SOCK=$TSDIR/tailscaled.sock
MARK=0x80000/0xff0000
PROBE=1.1.1.1            # route-lookup target only — no packets are ever sent
MAXLOG=10485760          # 10 MiB

# --- the Android routing fix ------------------------------------------------
#
# Android routes by fwmark and keeps its default route in a per-interface
# table (e.g. "wlan0"), leaving "main" and "default" essentially empty.
# Tailscale marks its underlay sockets 0x80000 to bypass its own tunnel, then
# installs:
#     5210 fwmark 0x80000 lookup main
#     5230 fwmark 0x80000 lookup default
#     5250 fwmark 0x80000 unreachable
# On Android 5210/5230 never match, so 5250 blackholes every underlay packet
# and the tunnel never carries traffic. 0x80000 is also netd's
# PERMISSION_SYSTEM bit, so it cannot simply be moved out of the way.
# (TS_FORCE_LINUX_BIND_TO_DEVICE looks like the fix and is not — verified.)
#
# We insert two rules ahead of 5250:
#     5235 -> tailnet-destined bypass traffic stays fail-fast (5250's purpose)
#     5240 -> all other bypass traffic goes to the real underlay table
# Priorities 5235/5240 are ours; tailscaled never touches them, so they
# survive its router reconfigures.

# dummy0 is Android's "no network" placeholder and carries a default route of
# its own before wifi associates. service.d runs that early, so selecting the
# first default route blindly latches the bypass rule onto a blackhole -- and
# because such a rule still *resolves*, a naive health check then reports OK
# forever. Exclude it explicitly.
underlay_dev() {
  $IP -4 route show table all 2>/dev/null |
    awk '/^default /{d="";for(i=1;i<=NF;i++) if($i=="dev")d=$(i+1);
         if(d!="" && d!="dummy0" && d!="lo"){print d; exit}}'
}

underlay_table() {
  $IP -4 route show table all 2>/dev/null |
    awk -v d="$1" '/^default /{t="";v="";
      for(i=1;i<=NF;i++){if($i=="dev")v=$(i+1); if($i=="table")t=$(i+1)}
      if(v==d){print t; exit}}'
}

apply_rules() {
  dev=$(underlay_dev);           [ -n "$dev" ] || return 1
  tbl=$(underlay_table "$dev");  [ -n "$tbl" ] || return 1
  $IP rule del pref 5235 2>/dev/null
  $IP rule del pref 5240 2>/dev/null
  $IP rule add pref 5235 fwmark $MARK to 100.64.0.0/10 unreachable
  $IP rule add pref 5240 fwmark $MARK lookup "$tbl"
  # No IPv6 equivalent: Android's only IPv6 default route is on dummy0, its
  # blackhole placeholder. Letting v6 hit 5250 makes tailscale fail fast and
  # fall back to the IPv4 underlay, which is the correct outcome.
  return 0
}

# Which table rule 5240 currently points at (empty if the rule is absent).
rule_table() {
  $IP rule show 2>/dev/null |
    awk '/^5240:/{for(i=1;i<=NF;i++) if($i=="lookup"){print $(i+1); exit}}'
}

# Health is "5240 points at the table that carries the default route RIGHT NOW",
# not merely "5240 exists". Existence is not enough: a rule aimed at a stale or
# placeholder table still resolves, so an existence check reports healthy while
# every underlay packet disappears.
rules_ok() {
  want=$(underlay_table "$(underlay_dev)")
  [ -n "$want" ] && [ "$(rule_table)" = "$want" ] &&
    $IP route get $PROBE mark 0x80000 >/dev/null 2>&1
}

start_daemon() {
  [ -f "$LOG" ] && mv -f "$LOG" "$LOG.1"
  rm -f "$SOCK"
  "$TSDIR/tailscaled" \
    --state="$TSDIR/tailscaled.state" \
    --statedir="$TSDIR" \
    --socket="$SOCK" \
    --port=41641 >"$LOG" 2>&1 &
}

# --- the Wi-Fi boot race ------------------------------------------------------
# WifiService initialises before the MTK Wi-Fi HAL and wpa_supplicant are ready,
# and when that happens it gives up: Wi-Fi is wanted (global wifi_on is 1) yet
# stays disabled until someone asks again, which works every time once the boot
# is over. So: wait for the boot
# to complete, then while Wi-Fi is wanted but disabled, ask again every few
# seconds. The supervisor loop below keeps asking, so a HAL that dies later is
# covered too; the app's own Wi-Fi switch sets wifi_on to 0, so it is never
# fought. Nothing here needs to be quick (2026-09-16): it needs to end
# with Wi-Fi up, and tailscaled only starts once it is.
BOOTLOG=$TSDIR/boot.log
GETPROP=/system/bin/getprop
CMD=/system/bin/cmd

# `cmd` hands its stdin, stdout and stderr to system_server over binder, and the
# kernel refuses the transaction ("Failed transaction (2147483646)") when any of
# them is a file system_server may not touch, which every file under /data/adb
# is. So every `cmd` here gets /dev/null on all three, and the boot log is
# written by this shell, never by cmd (verified 2026-09-16).
wifi_wanted()  { [ "$($CMD settings get global wifi_on 2>/dev/null </dev/null)" = "1" ]; }
wifi_enabled() { $CMD wifi status 2>/dev/null </dev/null | grep -q '^Wifi is enabled'; }

# Ask for Wi-Fi if it is wanted and not up; say so in the boot log. Returns 0 if nothing was needed.
wifi_nudge() {
  wifi_wanted && ! wifi_enabled || return 0
  echo "$(date '+%F %T') wifi wanted but disabled: enabling ($1)" >>"$BOOTLOG"
  $CMD wifi set-wifi-enabled enabled </dev/null >/dev/null 2>&1
  return 1
}

ensure_wifi() {
  i=0
  while [ "$($GETPROP sys.boot_completed)" != "1" ] && [ $i -lt 300 ]; do i=$((i+1)); sleep 1; done
  sleep 5
  i=0
  while [ $i -lt 12 ]; do
    wifi_nudge "boot, try $i" && return 0
    i=$((i+1)); sleep 5
  done
}

# --- supervisor -------------------------------------------------------------
# Backgrounded so Magisk's late_start is not held open. Wi-Fi first (above),
# then a REAL underlay, then tailscaled; apply_rules retries until wifi is
# actually associated, so boot ordering does not need to be guessed.

(
  echo "$(date '+%F %T') boot: waiting for the boot to complete and wifi" >"$BOOTLOG"
  ensure_wifi
  # Wait for a REAL underlay before doing anything, so tailscaled never starts
  # into broken routing -- if it does, its control-plane dials fail and it backs
  # off for minutes, long after the routing is repaired.
  i=0
  while [ $i -lt 180 ]; do
    [ -n "$(underlay_dev)" ] && break
    i=$((i+1)); sleep 1
  done
  apply_rules
  start_daemon

  while true; do
    sleep 15
    wifi_nudge "later"
    pidof tailscaled >/dev/null 2>&1 || start_daemon
    # Re-point the rule if the underlay moved (wifi reconnect, interface change).
    # tailscaled is restarted afterwards because it may already be deep in
    # connection backoff from the period when routing was wrong.
    if ! rules_ok && apply_rules; then
      kill $(pidof tailscaled) 2>/dev/null
      sleep 1
      start_daemon
    fi
    if [ -f "$LOG" ]; then
      sz=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
      [ "$sz" -gt "$MAXLOG" ] && mv -f "$LOG" "$LOG.1"
    fi
  done
) &
