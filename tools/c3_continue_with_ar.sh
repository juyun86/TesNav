#!/usr/bin/env bash

# Optional read-only video service; never delay or gate the original launch.
if [ -f /data/tenengkai-ar-source/start_ar_source.sh ]; then
  /bin/bash /data/tenengkai-ar-source/start_ar_source.sh >>/data/tenengkai-ar-source/bootstrap.log 2>&1 &
fi
cd /data/openpilot
exec ./launch_openpilot.sh
