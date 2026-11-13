"""The "Stemify Live" output device: a PipeWire null sink that apps can pick like any speaker.

The sink is created by a pw-cli child process with object.linger=false, so PipeWire removes it
as soon as that process goes away. The child gets SIGKILL when we exit for any reason (even a
crash or SIGKILL of our own), so a stale "Stemify Live" never stays behind.
"""

import ctypes
import json
import signal
import subprocess
import time

NAME = "stemify_live"
DESCRIPTION = "Stemify Live"
PR_SET_PDEATHSIG = 1


def _die_with_parent():
    ctypes.CDLL("libc.so.6", use_errno=True).prctl(PR_SET_PDEATHSIG, signal.SIGKILL)


def pw_dump():
    out = subprocess.run(["pw-dump"], capture_output=True, text=True, timeout=10).stdout
    return json.loads(out or "[]")


def find_node(name, dump=None):
    """The node's pw-dump object, or None."""
    for o in dump if dump is not None else pw_dump():
        if o.get("type") == "PipeWire:Interface:Node" and o["info"]["props"].get("node.name") == name:
            return o
    return None


def default_sink_name():
    out = subprocess.run(["pactl", "get-default-sink"], capture_output=True, text=True, timeout=5).stdout
    return out.strip()


class VirtualSink:
    """A stereo null sink at 48 kHz, alive while this object is open.

    [latency] is the quantum the sink asks the graph for (frames at 48 kHz). The graph runs at
    the smallest quantum any active node requests, so while Live runs the whole graph does.
    """

    def __init__(self, name=NAME, description=DESCRIPTION, latency=256, rate=48000):
        self.name = name
        self.description = description
        self.latency = latency
        self.rate = rate
        self._proc = None
        self.node_id = None

    def open(self, timeout=5.0):
        if self._proc is not None:
            return self
        if find_node(self.name) is not None:
            raise RuntimeError(f"a node named {self.name!r} already exists (another Stemify Live running?)")
        props = (f'factory.name=support.null-audio-sink node.name={self.name} '
                 f'node.description="{self.description}" media.class=Audio/Sink '
                 f'object.linger=false audio.position=[FL FR] audio.rate={self.rate} '
                 f'node.latency={self.latency}/{self.rate} monitor.channel-volumes=false')
        self._proc = subprocess.Popen(["pw-cli"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL, text=True, preexec_fn=_die_with_parent)
        self._proc.stdin.write(f"create-node adapter {{ {props} }}\n")
        self._proc.stdin.flush()
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            node = find_node(self.name)
            if node is not None:
                self.node_id = node["id"]
                return self
            if self._proc.poll() is not None:
                break
            time.sleep(0.05)
        self.close()
        raise RuntimeError(f"PipeWire did not create the {self.description!r} sink")

    def close(self):
        if self._proc is not None:
            self._proc.kill()
            self._proc.wait()
            self._proc = None
            self.node_id = None

    def __enter__(self):
        return self.open()

    def __exit__(self, *exc):
        self.close()
