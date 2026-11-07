"""Managed child process: pinned runtime never enters manager/UI processes."""
import json
import os
import signal
import sys
import threading
import time
import traceback

from .assets import VERSION, activate_runtime, directory, verify


def report(stop):
  root = directory()
  while not stop.is_set():
    path = root / 'status.tmp'
    updated_at = time.time()  # noqa: TID251 - existing store status contract uses epoch time.
    path.write_text(json.dumps({'state': 'Local ready', 'version': VERSION, 'updated_at': updated_at}))
    os.replace(path, root / 'status.json')
    stop.wait(1)


def main():
  stop = threading.Event()
  reporter = threading.Thread(target=report, args=(stop,), daemon=True)
  failed = False
  def terminate(signum, frame):
    raise SystemExit(0)
  signal.signal(signal.SIGTERM, terminate)
  try:
    verify()
    activate_runtime()
    from .loop import main as run

    # Start readiness reporting only after the first successful model frame.
    run(ready=reporter.start)
  except Exception:
    traceback.print_exc()
    failed = True
  finally:
    stop.set()
    if reporter.ident is not None:
      reporter.join(.2 if failed else 2)
    try:
      (directory() / 'status.json').unlink(missing_ok=True)
    except OSError:
      traceback.print_exc()
    if failed:
      # The manager owns teardown/replacement. Fatal GPU paths must not enter
      # tinygrad's USB atexit finalizers and delay release to the local model.
      sys.stderr.flush()
      os._exit(1)


if __name__ == '__main__':
  main()
