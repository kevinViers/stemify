"""CLI harness for the worker: spawns it, speaks the IPC protocol, prints progress.

  stemroller-worker-cli song.mp3 --model htdemucs [--device cuda:0] [--two-stems] [--cancel-after 3]
"""

import argparse
import hashlib
import json
import os
import socket
import subprocess
import sys
import tempfile
import time

from stemroller.catalog import MODELS_BY_ID


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--model", default="htdemucs", choices=list(MODELS_BY_ID))
    ap.add_argument("--device", default="auto")
    ap.add_argument("--two-stems", action="store_true")
    ap.add_argument("--shifts", type=int, default=0)
    ap.add_argument("--segment", type=float, default=0)
    ap.add_argument("--overlap", type=float, default=0.25)
    ap.add_argument("--fp32", action="store_true")
    ap.add_argument("--out", default=None, help="output root (default: a temp dir)")
    ap.add_argument("--cancel-after", type=float, default=None, help="send cancel after N seconds")
    args = ap.parse_args()

    out_root = args.out or tempfile.mkdtemp(prefix="stemroller-cli-")
    sock_path = os.path.join(tempfile.mkdtemp(), "w.sock")
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(sock_path)
    server.listen(1)

    t0 = time.monotonic()
    proc = subprocess.Popen([sys.executable, "-m", "stemroller.worker", "--socket", sock_path])
    conn, _ = server.accept()
    rfile = conn.makefile("r")

    def send(**msg):
        conn.sendall((json.dumps(msg) + "\n").encode())

    pending = []
    for i, f in enumerate(args.files):
        jid = f"cli{i}"
        out_dir = os.path.join(out_root, hashlib.sha1(f.encode()).hexdigest()[:10] + "_" + args.model)
        pending.append(jid)
        send(type="start", id=jid, file=os.path.abspath(f), model=args.model, device=args.device,
             two_stems=args.two_stems, out_dir=out_dir, segment=args.segment, shifts=args.shifts,
             overlap=args.overlap, fp16=not args.fp32)

    job_started = None
    n_progress = 0
    cancel_sent = False
    rc = 0
    conn.settimeout(0.2)
    buf = ""
    while pending:
        if args.cancel_after and job_started and not cancel_sent and time.monotonic() - job_started > args.cancel_after:
            send(type="cancel", id=pending[0])
            cancel_sent = True
            print("\n-> cancel sent")
        try:
            chunk = conn.recv(65536).decode()
        except socket.timeout:
            continue
        if not chunk:
            print("worker exited unexpectedly")
            rc = 1
            break
        buf += chunk
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            msg = json.loads(line)
            t = msg["type"]
            el = time.monotonic() - t0
            if t == "hello":
                print(f"[{el:6.1f}s] hello torch={msg['torch']} hip={msg['hip']} devices={msg['devices']}")
            elif t == "progress":
                n_progress += 1
                job_started = job_started or time.monotonic()
                print(f"\r[{el:6.1f}s] {msg['id']} {msg['stage']:<20} {msg['pct'] * 100:5.1f}%", end="", flush=True)
            elif t == "done":
                dur = time.monotonic() - job_started
                print(f"\n[{el:6.1f}s] done {msg['id']} in {dur:.1f}s ({msg['duration'] / dur:.1f}x realtime) "
                      f"on {msg['device']}, {n_progress} progress msgs ({n_progress / dur:.1f}/s)")
                for s in msg["stems"]:
                    print("   ", s["name"], s["path"])
                pending.remove(msg["id"])
                job_started, n_progress = None, 0
            elif t in ("error", "cancelled"):
                print(f"\n[{el:6.1f}s] {t} {msg}")
                pending.remove(msg["id"])
                job_started, n_progress = None, 0
                rc = rc or (1 if t == "error" else 0)
    send(type="quit")
    proc.wait(timeout=10)
    sys.exit(rc)


if __name__ == "__main__":
    main()
