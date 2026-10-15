"""How busy the GPU is with work that isn't ours.

Two signals, both read from the kernel (no GPU calls):
  - DRM fdinfo: per-client gfx/compute engine time. Games and other apps show
    up here; our ROCm worker runs through KFD and our own UI is excluded by pid.
  - amdgpu gpu_busy_percent: catches other ROCm/compute users that fdinfo
    misses, but also counts our worker, so it only means something while our
    worker is idle.
"""

import os
import re

_KV = re.compile(r"^(drm-[\w-]+):\s+(\S+)", re.M)
_ENGINES = ("drm-engine-gfx", "drm-engine-compute")


class GpuMonitor:
    def __init__(self):
        self.pdev = None  # "0000:03:00.0"; None counts every GPU
        self.exclude: set[int] = {os.getpid()}
        self._last: dict[str, int] = {}  # drm-client-id -> engine ns
        self._last_t = None

    def set_device(self, pci):
        if pci != self.pdev:
            self.pdev = pci
            self._last, self._last_t = {}, None

    def _busy_path(self):
        if self.pdev:
            p = f"/sys/bus/pci/devices/{self.pdev}/gpu_busy_percent"
            return p if os.path.exists(p) else None
        return None

    def _clients(self):
        out = {}
        for pid in os.listdir("/proc"):
            if not pid.isdigit() or int(pid) in self.exclude:
                continue
            try:
                fds = os.listdir(f"/proc/{pid}/fd")
            except OSError:
                continue
            for fd in fds:
                try:
                    if not os.readlink(f"/proc/{pid}/fd/{fd}").startswith("/dev/dri/"):
                        continue
                    with open(f"/proc/{pid}/fdinfo/{fd}") as f:
                        kv = dict(_KV.findall(f.read()))
                except OSError:
                    continue
                cid = kv.get("drm-client-id")
                if not cid or (self.pdev and kv.get("drm-pdev") != self.pdev):
                    continue
                # one client can sit behind several dup'd fds; count it once
                out[cid] = sum(int(kv[k]) for k in _ENGINES if kv.get(k, "").isdigit())
        return out

    def sample(self, now):
        """(other_fraction, busy_percent). other_fraction is engine time used by
        other processes over the last interval, as a fraction of wall time."""
        clients = self._clients()
        other = 0.0
        if self._last_t is not None and now > self._last_t:
            delta = sum(ns - self._last[c] for c, ns in clients.items() if c in self._last and ns >= self._last[c])
            other = delta / ((now - self._last_t) * 1e9)
        self._last, self._last_t = clients, now
        pct = -1
        path = self._busy_path()
        if path:
            try:
                with open(path) as f:
                    pct = int(f.read().strip())
            except (OSError, ValueError):
                pass
        return other, pct
