"""App controller: queue, dispatch to the worker, cache, export. Exposed to QML as `app`."""

import hashlib
import json
import os
import shutil
import threading
import time
import uuid

import numpy as np
import soundfile as sf
from PySide6.QtCore import Property, QObject, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices

from .catalog import MODELS, MODELS_BY_ID, STEM_COLORS, sort_stems
from .jobs_model import Job, JobsModel
from .settings import Settings
from .worker_client import WorkerClient

CACHE_ROOT = os.path.join(os.path.expanduser("~/.cache/stemroller"), "stems")
AUDIO_EXTS = {".mp3", ".wav", ".flac", ".ogg", ".oga", ".opus", ".m4a", ".aac", ".aif", ".aiff",
              ".wma", ".mp4", ".mkv", ".webm", ".mka", ".alac", ".wv", ".ape"}


def _cpu_name():
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip().replace(" 8-Core Processor", "")
    except OSError:
        pass
    return "CPU"


def _fmt_mb(mb):
    return f"{mb / 1024:.1f} GB"


def _dir_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


class Controller(QObject):
    selectedChanged = Signal()
    devicesChanged = Signal()
    vramChanged = Signal()
    workerStateChanged = Signal()
    cacheChanged = Signal()
    toast = Signal(str, str, str)  # message, kind (info|success|error), folder to open ("" = none)
    _exportDone = Signal(str, str, str)
    _cacheSized = Signal(int)

    def __init__(self, audio, peak_store, parent=None):
        super().__init__(parent)
        self.settings = Settings(self)
        self.jobs = JobsModel(self)
        self.audio = audio
        self.peak_store = peak_store
        self._selected_id = ""
        self._set_running("")
        self._devices = [{"id": "auto", "label": "Auto", "sub": "Starting engine…"},
                         {"id": "cpu", "label": "CPU", "sub": _cpu_name()}]
        self._gpus = []
        self._vram = {}  # device -> (free_mb, total_mb)
        self._cache_bytes = 0
        self._exporting = False

        self.worker = WorkerClient(self)
        self.worker.stateChanged.connect(lambda _: (self.workerStateChanged.emit(), self._dispatch()))
        self.worker.hello.connect(self._on_hello)
        self.worker.vram.connect(self._on_vram)
        self.worker.progress.connect(self._on_progress)
        self.worker.done.connect(self._on_done)
        self.worker.failed.connect(self._on_failed)
        self.worker.cancelled.connect(self._on_cancelled)
        self.worker.crashed.connect(self._on_crashed)
        self._exportDone.connect(self._on_export_done)
        self._cacheSized.connect(self._on_cache_sized)
        self.settings.deviceIdChanged.connect(self.vramChanged)
        self.settings.modelIdChanged.connect(self.selectedChanged)
        self.settings.twoStemsChanged.connect(self.selectedChanged)
        self.settings.outputModeChanged.connect(self.selectedChanged)
        self.settings.stemsDirChanged.connect(self.selectedChanged)
        self.audio.loadedChanged.connect(self.selectedChanged)
        self.audio.loadFailed.connect(lambda m: self.toast.emit(f"Could not load stems: {m}", "error", ""))
        self.audio.setVolume(self.settings.get("masterVolume"))
        self.worker.start()
        self.refreshCacheSize()

    # ---- worker events -------------------------------------------------
    def _on_hello(self, msg):
        self._gpus = msg["devices"]
        devs = []
        if self._gpus:
            best = max(self._gpus, key=lambda d: d["total_mb"])
            devs.append({"id": "auto", "label": "Auto", "sub": best["name"]})
            for d in self._gpus:
                devs.append({"id": d["id"], "label": d["name"].replace("AMD ", ""),
                             "sub": f"{d['arch']} · {_fmt_mb(d['total_mb'])}"})
        else:
            devs.append({"id": "auto", "label": "Auto", "sub": "No GPU found, using CPU"})
        devs.append({"id": "cpu", "label": "CPU", "sub": _cpu_name()})
        self._devices = devs
        self.devicesChanged.emit()
        self.selectedChanged.emit()

    def _on_vram(self, device, free_mb, total_mb):
        self._vram[device] = (free_mb, total_mb)
        if device == self._effective_gpu():
            self.vramChanged.emit()

    def _on_progress(self, job_id, pct, stage):
        job = self.jobs.get(job_id)
        if job and job.status == "running":
            self.jobs.update(job_id, progress=max(job.progress, pct), stage=stage)
            if job_id == self._selected_id:
                self.selectedChanged.emit()

    def _on_done(self, job_id, result):
        job = self.jobs.get(job_id)
        self._set_running("")
        if job:
            self.jobs.update(job_id, status="done", progress=1.0, stage="Done", result=result,
                             elapsed=time.monotonic() - job.started)
            if job_id == self._selected_id:
                self._load_selected()
                self.selectedChanged.emit()
            if self.settings.get("autoExport"):
                self._export(job, [s["name"] for s in result["stems"]], mix=False, auto=True)
        self._prune_cache()
        self._dispatch()

    def _on_failed(self, job_id, msg):
        if job_id == self._running_id:
            self._set_running("")
        job = self.jobs.get(job_id)
        if job:
            self.jobs.update(job_id, status="error", stage="Failed", message=msg)
            if job_id == self._selected_id:
                self.selectedChanged.emit()
            self.toast.emit(f"{job.file_name}: {msg}", "error", "")
        self._dispatch()

    def _on_cancelled(self, job_id):
        if job_id == self._running_id:
            self._set_running("")
        if self.jobs.get(job_id):
            self.jobs.update(job_id, status="cancelled", stage="Cancelled", progress=0.0)
            if job_id == self._selected_id:
                self.selectedChanged.emit()
        self._dispatch()

    def _on_crashed(self):
        if self._running_id:
            self._on_failed(self._running_id, "The separation engine crashed; restarting it.")

    def _set_running(self, job_id):
        self._running_id = job_id
        self.workerStateChanged.emit()

    # ---- queue ---------------------------------------------------------
    def _cache_dir(self, path, model_id, two_stems):
        st = os.stat(path)
        key = hashlib.sha1(f"{os.path.realpath(path)}|{st.st_size}|{st.st_mtime_ns}".encode()).hexdigest()[:16]
        return os.path.join(CACHE_ROOT, f"{key}_{model_id}{'_2s' if two_stems else ''}")

    def _enqueue(self, path):
        model_id = self.settings.get("modelId")
        if model_id not in MODELS_BY_ID:
            model_id = "htdemucs"
        two = self.settings.get("twoStems") and "vocals" in MODELS_BY_ID[model_id]["stems"] \
            and len(MODELS_BY_ID[model_id]["stems"]) > 2
        cache_dir = self._cache_dir(path, model_id, two)
        job = Job(id=uuid.uuid4().hex[:12], file=path, file_name=os.path.basename(path),
                  model_id=model_id, model_label=MODELS_BY_ID[model_id]["label"] + (" · 2-stem" if two else ""),
                  two_stems=two, device_id=self.settings.get("deviceId"), cache_dir=cache_dir)
        meta_path = os.path.join(cache_dir, "meta.json")
        if os.path.exists(meta_path):
            try:
                with open(meta_path) as f:
                    meta = json.load(f)
                if all(os.path.exists(s["path"]) for s in meta["stems"]):
                    os.utime(cache_dir)  # LRU touch
                    job.status, job.progress, job.stage = "done", 1.0, "Done (cached)"
                    job.result = {k: meta[k] for k in ("stems", "peaks_path", "samplerate", "duration")}
                    job.result["device"] = meta.get("device", "")
            except (OSError, ValueError, KeyError):
                pass
        self.jobs.add(job)
        return job

    @Slot("QVariantList")
    def addUrls(self, urls):
        paths = []
        for u in urls:
            if isinstance(u, QUrl):
                p = u.toLocalFile()
            else:
                p = QUrl(u).toLocalFile() if str(u).startswith("file:") else str(u)
            if os.path.isdir(p):
                for root, _, files in os.walk(p):
                    paths += [os.path.join(root, f) for f in sorted(files)]
            else:
                paths.append(p)
        paths = [p for p in paths if os.path.splitext(p)[1].lower() in AUDIO_EXTS and os.path.isfile(p)]
        if not paths:
            self.toast.emit("No audio files in that drop.", "info", "")
            return
        first = None
        for p in paths:
            job = self._enqueue(p)
            first = first or job
        if not self._selected_id or not self.jobs.get(self._selected_id) or len(paths) == 1:
            self.select(first.id)
        self._dispatch()

    def _dispatch(self):
        if self._running_id or self.worker.state != "ready":
            return
        job = next((j for j in self.jobs.jobs if j.status == "queued"), None)
        if not job:
            return
        s = self.settings
        ok = self.worker.send("start", id=job.id, file=job.file, model=job.model_id, device=job.device_id,
                              two_stems=job.two_stems, out_dir=job.cache_dir, segment=s.get("segment"),
                              shifts=s.get("shifts"), overlap=s.get("overlap"), fp16=s.get("fp16"))
        if ok:
            self._set_running(job.id)
            self.jobs.update(job.id, status="running", stage="Starting", progress=0.0, message="",
                             started=time.monotonic())
            if job.id == self._selected_id:
                self.selectedChanged.emit()

    @Slot(str)
    def cancelJob(self, job_id):
        job = self.jobs.get(job_id)
        if not job:
            return
        if job.status == "running":
            self.jobs.update(job_id, stage="Cancelling")
            self.worker.send("cancel", id=job_id)
        elif job.status == "queued":
            self.jobs.update(job_id, status="cancelled", stage="Cancelled")
        if job_id == self._selected_id:
            self.selectedChanged.emit()

    @Slot(str)
    def removeJob(self, job_id):
        job = self.jobs.get(job_id)
        if not job:
            return
        if job.status == "running":
            self.cancelJob(job_id)
        row = self.jobs.row_of(job_id)
        self.jobs.remove(job_id)
        self.peak_store.drop(job_id)
        if job_id == self._selected_id:
            nxt = self.jobs.jobs[min(row, len(self.jobs.jobs) - 1)].id if self.jobs.jobs else ""
            self.select(nxt)

    @Slot(str)
    def retryJob(self, job_id):
        job = self.jobs.get(job_id)
        if job and job.status in ("error", "cancelled"):
            self.jobs.update(job_id, status="queued", stage="Waiting", progress=0.0, message="")
            if job_id == self._selected_id:
                self.selectedChanged.emit()
            self._dispatch()

    @Slot()
    def rerunSelected(self):
        job = self.jobs.get(self._selected_id)
        if job:
            new = self._enqueue(job.file)
            self.select(new.id)
            self._dispatch()

    @Slot(str)
    def select(self, job_id):
        if job_id == self._selected_id:
            return
        self._selected_id = job_id
        self._load_selected()
        self.selectedChanged.emit()

    def _load_selected(self):
        job = self.jobs.get(self._selected_id)
        if job and job.status == "done":
            stems = sort_stems([s["name"] for s in job.result["stems"]])
            by_name = {s["name"]: s["path"] for s in job.result["stems"]}
            self.audio.load(job.id, [(n, by_name[n]) for n in stems])
        else:
            self.audio.unload()

    # ---- export --------------------------------------------------------
    def _target_dir(self, job):
        base = os.path.splitext(job.file_name)[0]
        if self.settings.get("outputMode") == "folder":
            return os.path.join(self.settings.get("stemsDir"), base)
        return os.path.join(os.path.dirname(job.file), f"{base} - stems")

    def _export(self, job, names, mix, auto=False):
        if self._exporting:
            self.toast.emit("An export is already running.", "info", "")
            return
        self._exporting = True
        fmt = self.settings.get("exportFormat")
        target = self._target_dir(job)
        base = os.path.splitext(job.file_name)[0]
        loaded = self.audio.loaded_key() == job.id and self.audio.loaded
        if loaded:
            arrays, sr = self.audio.arrays()
            gains = {n: self.audio.stem_gain(n) for n in arrays}
            mix_gains = dict(zip(arrays.keys(), self.audio.mix_gains()))
        else:
            arrays, sr, gains, mix_gains = None, job.result["samplerate"], {}, {}
        paths = {s["name"]: s["path"] for s in job.result["stems"]}

        def read(name):
            if arrays is not None and name in arrays:
                return arrays[name]
            data, _ = sf.read(paths[name], dtype="float32", always_2d=True)
            return data

        def write(path, data):
            if fmt == "mp3":
                sf.write(path, np.clip(data, -1, 1), sr, format="MP3", subtype="MPEG_LAYER_III",
                         bitrate_mode="CONSTANT", compression_level=0.0)
            elif fmt == "flac":
                sf.write(path, np.clip(data, -1, 1), sr, format="FLAC", subtype="PCM_24")
            else:
                subtype = "FLOAT" if np.abs(data).max() > 0.999 else "PCM_24"
                sf.write(path, data, sr, format="WAV", subtype=subtype)

        def work():
            try:
                os.makedirs(target, exist_ok=True)
                if mix:
                    out = None
                    for n in paths:
                        g = mix_gains.get(n, 1.0)
                        if g:
                            d = read(n) * g
                            out = d if out is None else out + d
                    write(os.path.join(target, f"{base} - mix.{fmt}"), out if out is not None else read(next(iter(paths))) * 0)
                    msg = "Exported the current mix"
                else:
                    for n in names:
                        g = gains.get(n, 1.0)
                        d = read(n)
                        write(os.path.join(target, f"{base} - {n}.{fmt}"), d * g if g != 1.0 else d)
                    msg = f"Exported {len(names)} stem{'s' if len(names) != 1 else ''}" if not auto else \
                        f"Auto-exported {len(names)} stems"
                self._exportDone.emit(job.id, f"{msg} to {self._pretty(target)}", target)
            except Exception as e:
                self._exportDone.emit(job.id, f"Export failed: {e}", "")

        threading.Thread(target=work, daemon=True).start()

    def _on_export_done(self, job_id, msg, target):
        self._exporting = False
        if target and self.jobs.get(job_id):
            self.jobs.update(job_id, exported_to=target)
            if job_id == self._selected_id:
                self.selectedChanged.emit()
        self.toast.emit(msg, "success" if target else "error", target)

    @staticmethod
    def _pretty(path):
        home = os.path.expanduser("~")
        return "~" + path[len(home):] if path.startswith(home) else path

    @Slot()
    def exportAll(self):
        job = self.jobs.get(self._selected_id)
        if job and job.status == "done":
            self._export(job, sort_stems([s["name"] for s in job.result["stems"]]), mix=False)

    @Slot(str)
    def exportStem(self, name):
        job = self.jobs.get(self._selected_id)
        if job and job.status == "done":
            self._export(job, [name], mix=False)

    @Slot()
    def exportMix(self):
        job = self.jobs.get(self._selected_id)
        if job and job.status == "done":
            self._export(job, [], mix=True)

    @Slot(str)
    def openFolder(self, path):
        if path and os.path.isdir(path):
            QDesktopServices.openUrl(QUrl.fromLocalFile(path))

    @Slot()
    def openTargetFolder(self):
        job = self.jobs.get(self._selected_id)
        if job:
            t = self._target_dir(job)
            self.openFolder(t if os.path.isdir(t) else os.path.dirname(t))

    # ---- cache ---------------------------------------------------------
    def _referenced(self):
        return {os.path.realpath(j.cache_dir) for j in self.jobs.jobs}

    def _prune_cache(self):
        limit = self.settings.get("cacheLimitGb") * 2**30
        refs = self._referenced()

        def work():
            if not os.path.isdir(CACHE_ROOT):
                self._cacheSized.emit(0)
                return
            entries = []
            for d in os.listdir(CACHE_ROOT):
                p = os.path.join(CACHE_ROOT, d)
                entries.append((os.path.getmtime(p), _dir_size(p), p))
            total = sum(e[1] for e in entries)
            for _, size, p in sorted(entries):
                if total <= limit:
                    break
                if os.path.realpath(p) not in refs:
                    shutil.rmtree(p, ignore_errors=True)
                    total -= size
            self._cacheSized.emit(total)

        threading.Thread(target=work, daemon=True).start()

    @Slot()
    def refreshCacheSize(self):
        threading.Thread(target=lambda: self._cacheSized.emit(_dir_size(CACHE_ROOT) if os.path.isdir(CACHE_ROOT) else 0),
                         daemon=True).start()

    @Slot()
    def clearCache(self):
        refs = self._referenced()
        if os.path.isdir(CACHE_ROOT):
            for d in os.listdir(CACHE_ROOT):
                p = os.path.join(CACHE_ROOT, d)
                if os.path.realpath(p) not in refs:
                    shutil.rmtree(p, ignore_errors=True)
        self.refreshCacheSize()
        self.toast.emit("Cleared cached stems not in the queue.", "info", "")

    def _on_cache_sized(self, n):
        self._cache_bytes = n
        self.cacheChanged.emit()

    # ---- QML-facing properties -----------------------------------------
    def _effective_gpu(self):
        dev = self.settings.get("deviceId")
        if dev == "cpu" or not self._gpus:
            return ""
        if dev == "auto" or not any(g["id"] == dev for g in self._gpus):
            return max(self._gpus, key=lambda d: d["total_mb"])["id"]
        return dev

    def peaks_path_for(self, job_id):
        job = self.jobs.get(job_id)
        return job.result.get("peaks_path") if job and job.status == "done" else None

    def _get_selected(self):
        job = self.jobs.get(self._selected_id)
        if not job:
            return {"valid": False}
        stems = []
        if job.status == "done":
            stems = [{"name": n, "color": STEM_COLORS.get(n, "#9B9DA3")}
                     for n in sort_stems([s["name"] for s in job.result["stems"]])]
        dev = job.result.get("device", "") if job.result else ""
        dev_label = "CPU" if dev == "cpu" else next((g["name"].replace("AMD ", "") for g in self._gpus if g["id"] == dev), dev)
        return {
            "valid": True, "id": job.id, "fileName": job.file_name, "filePath": job.file,
            "folder": self._pretty(os.path.dirname(job.file)),
            "modelId": job.model_id, "modelLabel": job.model_label, "status": job.status,
            "progress": job.progress, "stage": job.stage, "message": job.message,
            "elapsed": job.elapsed, "duration": job.result.get("duration", 0.0) if job.result else 0.0,
            "deviceLabel": dev_label, "stems": stems,
            "audioReady": self.audio.loaded and self.audio.loaded_key() == job.id,
            "target": self._pretty(self._target_dir(job)), "exportedTo": job.exported_to,
            "rerunLabel": self._rerun_label(job),
        }

    def _rerun_label(self, job):
        m = self.settings.get("modelId")
        two = self.settings.get("twoStems") and len(MODELS_BY_ID.get(m, {"stems": []})["stems"]) > 2
        if m == job.model_id and two == job.two_stems:
            return ""
        return MODELS_BY_ID[m]["label"] + (" · 2-stem" if two else "")

    def _get_models(self):
        return [{"id": m["id"], "label": m["label"], "note": m["note"], "family": m["family"],
                 "stems": len(m["stems"])} for m in MODELS]

    def _get_devices(self):
        return self._devices

    def _get_vram(self):
        gpu = self._effective_gpu()
        if not gpu or gpu not in self._vram:
            return {"valid": False}
        free, total = self._vram[gpu]
        return {"valid": True, "free": free, "total": total,
                "text": f"{_fmt_mb(free)} free of {_fmt_mb(total)}"}

    def _get_worker_state(self):
        if self.worker.state == "ready" and self._running_id:
            return "busy"
        return self.worker.state

    def _get_cache_text(self):
        return f"{self._cache_bytes / 2**30:.2f} GB"

    def _get_settings(self):
        return self.settings

    def _get_jobs(self):
        return self.jobs

    selected = Property("QVariantMap", _get_selected, notify=selectedChanged)
    models = Property("QVariantList", _get_models, constant=True)
    devices = Property("QVariantList", _get_devices, notify=devicesChanged)
    vram = Property("QVariantMap", _get_vram, notify=vramChanged)
    workerState = Property(str, _get_worker_state, notify=workerStateChanged)
    cacheText = Property(str, _get_cache_text, notify=cacheChanged)
    settingsObj = Property(QObject, _get_settings, constant=True)
    jobsModel = Property(QObject, _get_jobs, constant=True)

    def shutdown(self):
        self.settings.masterVolume = self.audio.volume
        self.audio.unload()
        self.worker.shutdown()
