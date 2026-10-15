"""Separation worker process.

Connects to the UI's local socket and speaks newline-delimited JSON:

  UI -> worker   start {id, file, model, device, two_stems, out_dir, segment, shifts, overlap, fp16}
                 cancel {id}
                 release {}   drop cached models and free VRAM
                 quit {}
  worker -> UI   hello {devices[], torch, hip}
                 vram {device, free_mb, total_mb}
                 progress {id, pct, stage}
                 done {id, stems[], peaks_path, samplerate, duration, out_dir}
                 error {id, msg}
                 cancelled {id}

Runs one job at a time; the UI owns the queue.
"""

import argparse
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback

# Fast MIOpen kernel selection: avoids multi-minute first-run tuning on RDNA4.
os.environ.setdefault("MIOPEN_FIND_MODE", "FAST")
os.environ.setdefault("TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL", "1")
os.environ.setdefault("MIOPEN_LOG_LEVEL", "2")  # silence workspace warnings

PROGRESS_INTERVAL = 1 / 30  # doc: at most 30 progress updates per second
MODEL_DIR = os.path.join(os.path.expanduser("~/.cache/stemify"), "models")


class Conn:
    def __init__(self, path):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(path)
        self.lock = threading.Lock()
        self.rfile = self.sock.makefile("r", encoding="utf-8")

    def send(self, type_, **payload):
        line = json.dumps({"type": type_, **payload}) + "\n"
        with self.lock:
            self.sock.sendall(line.encode())

    def __iter__(self):
        for line in self.rfile:
            line = line.strip()
            if line:
                yield json.loads(line)


class Job:
    def __init__(self, msg, conn):
        from stemify.catalog import MODELS_BY_ID
        self.id = msg["id"]
        self.file = msg["file"]
        self.model = MODELS_BY_ID[msg["model"]]
        self.device = msg.get("device", "auto")
        self.two_stems = bool(msg.get("two_stems"))
        self.out_dir = msg["out_dir"]
        self.segment = float(msg.get("segment") or 0)
        self.shifts = int(msg.get("shifts") or 0)
        self.overlap = float(msg.get("overlap", 0.25))
        self.fp16 = bool(msg.get("fp16", True))
        # audio-separator expresses overlap as a divisor of the chunk (step = chunk / overlap);
        # RoFormer's windowed overlap-add wants at least 4 (75%).
        self.overlap_int = max(4, round(1 / max(0.05, 1 - self.overlap)))
        self.cancel_event = threading.Event()
        self._conn = conn
        self._last_sent = 0.0

    def check(self):
        if self.cancel_event.is_set():
            from stemify.worker.backends import Cancelled
            raise Cancelled()

    def report(self, stage, pct, force=False):
        now = time.monotonic()
        if force or now - self._last_sent >= PROGRESS_INTERVAL:
            self._last_sent = now
            self._conn.send("progress", id=self.id, pct=round(float(pct), 4), stage=stage)


def gpu_marketing_names():
    """gfx arch -> marketing name from rocminfo (torch reports a generic name on ROCm)."""
    names = {}
    if not shutil.which("rocminfo"):
        return names
    try:
        out = subprocess.run(["rocminfo"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return names
    arch = None
    for line in out.splitlines():
        m = re.match(r"\s*Name:\s+(gfx\w+)\s*$", line)
        if m:
            arch = m.group(1)
        m = re.match(r"\s*Marketing Name:\s+(.+?)\s*$", line)
        if m and arch:
            names.setdefault(arch, m.group(1))
            arch = None
    return names


def pci_address(props):
    bus = getattr(props, "pci_bus_id", None)
    if bus is None:
        return None
    return f"{props.pci_domain_id:04x}:{bus:02x}:{props.pci_device_id:02x}.0"


def sysfs_vram_paths(props):
    pci = pci_address(props)
    if pci is None:
        return None
    base = f"/sys/bus/pci/devices/{pci}"
    total, used = base + "/mem_info_vram_total", base + "/mem_info_vram_used"
    return (total, used) if os.path.exists(used) else None


def enumerate_devices():
    import torch
    devices = []
    if torch.cuda.is_available():
        names = gpu_marketing_names() if torch.version.hip else {}
        for i in range(torch.cuda.device_count()):
            p = torch.cuda.get_device_properties(i)
            if getattr(p, "is_integrated", False):
                continue  # skip APU iGPUs (e.g. the 9800X3D's gfx1036)
            arch = getattr(p, "gcnArchName", "").split(":")[0]
            devices.append({
                "id": f"cuda:{i}",
                "name": names.get(arch, p.name),
                "arch": arch,
                "total_mb": p.total_memory // 2**20,
                "pci": pci_address(p),
                "_sysfs": sysfs_vram_paths(p),
            })
    return devices


def vram_loop(conn, devices):
    import torch
    while True:
        for d in devices:
            try:
                if d["_sysfs"]:
                    total = int(open(d["_sysfs"][0]).read()) // 2**20
                    used = int(open(d["_sysfs"][1]).read()) // 2**20
                    free = total - used
                else:
                    free, total = (v // 2**20 for v in torch.cuda.mem_get_info(int(d["id"].split(":")[1])))
                conn.send("vram", device=d["id"], free_mb=free, total_mb=total)
            except Exception:
                pass
        time.sleep(1.0)


def resolve_device(choice, devices):
    import torch
    if choice == "cpu" or not devices:
        return torch.device("cpu")
    if choice != "auto" and any(d["id"] == choice for d in devices):
        return torch.device(choice)
    best = max(devices, key=lambda d: d["total_mb"])
    return torch.device(best["id"])


def run_job(job, backends, devices, conn):
    import numpy as np
    import torch
    from stemify.worker.audio_io import save_peaks, write_stem

    device = resolve_device(job.device, devices)
    backend = backends[job.model["family"]]
    os.makedirs(job.out_dir, exist_ok=True)
    try:
        stems, sr = backend.run(job, device)
    except torch.OutOfMemoryError:
        torch.cuda.empty_cache()
        raise RuntimeError("Out of GPU memory. Lower the segment size in Settings or pick CPU.")

    if job.two_stems and "vocals" in stems and len(stems) > 2:
        rest = [a for k, a in stems.items() if k != "vocals"]
        stems = {"vocals": stems["vocals"], "instrumental": np.sum(rest, axis=0)}

    job.report("Writing stems", 0.94, force=True)
    out = []
    for name, audio in stems.items():
        job.check()
        path = os.path.join(job.out_dir, f"{name}.wav")
        write_stem(path, audio, sr)
        out.append({"name": name, "path": path})
    job.report("Building waveforms", 0.98, force=True)
    peaks_path = os.path.join(job.out_dir, "peaks.npz")
    save_peaks(peaks_path, stems, sr)
    duration = next(iter(stems.values())).shape[1] / sr
    meta = {"source": job.file, "model": job.model["id"], "two_stems": job.two_stems,
            "stems": out, "peaks_path": peaks_path, "samplerate": sr, "duration": duration,
            "device": str(device), "finished": time.time()}
    with open(os.path.join(job.out_dir, "meta.json"), "w") as f:
        json.dump(meta, f)
    if device.type == "cuda":
        torch.cuda.empty_cache()
    return {"stems": out, "peaks_path": peaks_path, "samplerate": sr, "duration": duration,
            "out_dir": job.out_dir, "device": str(device)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--socket", required=True)
    args = ap.parse_args()

    try:
        os.nice(5)  # UI first; the GPU does the heavy lifting anyway
    except OSError:
        pass
    conn = Conn(args.socket)
    conn.send("booting")

    import torch
    torch.set_num_threads(max(1, (os.cpu_count() or 4) - 2))
    from stemify.worker.backends import Cancelled, DemucsBackend, RoformerBackend

    devices = enumerate_devices()
    conn.send("hello", devices=[{k: v for k, v in d.items() if not k.startswith("_")} for d in devices],
              torch=torch.__version__, hip=torch.version.hip)
    threading.Thread(target=vram_loop, args=(conn, devices), daemon=True).start()

    jobs: queue.Queue = queue.Queue()
    current: dict = {}

    def reader():
        try:
            for msg in conn:
                t = msg.get("type")
                if t in ("start", "release"):
                    jobs.put(msg)
                elif t == "cancel":
                    job = current.get("job")
                    if job and job.id == msg.get("id"):
                        job.cancel_event.set()
                elif t == "quit":
                    break
        except (OSError, ValueError):
            pass
        # UI went away or asked us to quit: no point finishing work nobody will read.
        os._exit(0)

    threading.Thread(target=reader, daemon=True).start()

    backends = {"demucs": DemucsBackend(), "roformer": RoformerBackend(MODEL_DIR)}
    while True:
        msg = jobs.get()
        if msg.get("type") == "release":
            for b in backends.values():
                b.release()
            continue
        try:
            job = Job(msg, conn)
        except Exception as e:
            conn.send("error", id=msg.get("id"), msg=f"Bad job: {e}")
            continue
        current["job"] = job
        try:
            result = run_job(job, backends, devices, conn)
            conn.send("done", id=job.id, **result)
        except Cancelled:
            shutil.rmtree(job.out_dir, ignore_errors=True)
            conn.send("cancelled", id=job.id)
        except Exception as e:
            traceback.print_exc()
            shutil.rmtree(job.out_dir, ignore_errors=True)
            conn.send("error", id=job.id, msg=str(e) or e.__class__.__name__)
        finally:
            current.pop("job", None)
            sys.stdout.flush()


if __name__ == "__main__":
    main()
