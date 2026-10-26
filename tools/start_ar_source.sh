#!/usr/bin/env bash
# Recreate only this service's registration: /home and /var/lib are volatile on AGNOS.
set -eu
ar_source_base=/data/tenengkai-ar-source
export XDG_RUNTIME_DIR=/run/user/1000
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus
mkdir -p /home/comma/.config/systemd/user
ar_source_unit=/home/comma/.config/systemd/user/tenengkai-ar-source.service
if [ -e "$ar_source_unit" ] || [ -L "$ar_source_unit" ]; then
  [ "$(readlink -f "$ar_source_unit")" = "$ar_source_base/tenengkai-ar-source.service" ] || exit 1
else
  ln -s "$ar_source_base/tenengkai-ar-source.service" "$ar_source_unit"
fi
sudo -n loginctl enable-linger comma
for ar_source_attempt in {1..20}; do
  if systemctl --user daemon-reload; then
    systemctl --user enable --now tenengkai-ar-source.service
    exit 0
  fi
  sleep 1
done
exit 1
