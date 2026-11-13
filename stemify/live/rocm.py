"""ROCm setup that has to happen before torch is imported.

The ROCm torch wheels ship no kernels for the RDNA2 APU iGPUs (gfx1035/1036/1037, e.g. the
Radeon 610M on Ryzen 7000/9000 desktop chips), so touching one crashes the process. Running them
as gfx1030 works. The override is set per KFD node, so a discrete GPU next to it keeps its own
kernels; a single global HSA_OVERRIDE_GFX_VERSION would break an RDNA4 card.
"""

import glob
import os
import sys

KFD_NODES = "/sys/class/kfd/kfd/topology/nodes"
# gfx target version (major*10000 + minor*100 + stepping) -> override that runs it.
APU_OVERRIDES = {100305: "10.3.0", 100306: "10.3.0", 100307: "10.3.0"}


def _gfx_versions(key="node"):
    """{kfd node id (or PCI bus, key="bus"): gfx_target_version} for every GPU node."""
    out = {}
    for path in glob.glob(os.path.join(KFD_NODES, "*", "properties")):
        node = int(path.split(os.sep)[-2])
        try:
            with open(path) as f:
                props = dict(line.split()[:2] for line in f if len(line.split()) >= 2)
        except OSError:
            continue
        v = int(props.get("gfx_target_version", 0))
        if v:
            out[node if key == "node" else int(props.get("location_id", 0)) >> 8] = v
    return out


_prepared = None


def prepare_env():
    """Sets per-node overrides for APU iGPUs. Returns the variables it set."""
    global _prepared
    if _prepared is not None:
        return _prepared
    if "torch" in sys.modules:
        raise RuntimeError("stemify.live.rocm.prepare_env() must run before torch is imported")
    if os.environ.get("HSA_OVERRIDE_GFX_VERSION"):
        return {}  # the user picked a global override; respect it
    done = {}
    for node, version in sorted(_gfx_versions().items()):
        target = APU_OVERRIDES.get(version)
        key = f"HSA_OVERRIDE_GFX_VERSION_{node}"
        if target and key not in os.environ:
            os.environ[key] = done[key] = target
    _prepared = done
    return done


def setup_device(dev):
    """Per-device switches, after torch is imported. AOTriton's fused attention is built only
    for a few datacenter/RDNA3 targets, so anything else uses the plain math kernel."""
    import torch

    if dev.type != "cuda":
        return
    index = dev.index if dev.index is not None else torch.cuda.current_device()
    props = torch.cuda.get_device_properties(index)
    arch = props.gcnArchName.split(":")[0]
    if arch.startswith("gfx103"):
        torch.backends.cuda.enable_flash_sdp(False)
        torch.backends.cuda.enable_mem_efficient_sdp(False)
    if _gfx_versions("bus").get(props.pci_bus_id) in APU_OVERRIDES:
        _BROKEN_FFT.add(index)
        _patch_demucs_for_apu()


def is_apu(dev):
    """True for an iGPU running under the gfx1030 override (slow, and needs the workarounds)."""
    import torch

    if dev.type != "cuda":
        return False
    props = torch.cuda.get_device_properties(dev)
    return _gfx_versions("bus").get(props.pci_bus_id) in APU_OVERRIDES


_BROKEN_FFT = set()  # cuda device indexes whose complex FFT / small reductions are wrong


def _on_cpu(fn):
    def wrapped(x, *args, **kwargs):
        if x.device.type == "cuda" and x.device.index in _BROKEN_FFT:
            return fn(x.cpu(), *args, **kwargs).to(x.device)
        return fn(x, *args, **kwargs)
    wrapped.__wrapped__ = fn
    return wrapped


def _patch_demucs_for_apu():
    """Running gfx1036 as gfx1030 gives wrong answers from complex FFTs and from reductions
    over a handful of elements (istft's window check, demucs' pad1d assert). Both live in the
    spectrogram helpers, which are cheap, so they run on the CPU; the network stays on the GPU."""
    import demucs.hdemucs
    import demucs.htdemucs

    for mod in (demucs.hdemucs, demucs.htdemucs):
        for name in ("spectro", "ispectro", "pad1d"):
            fn = getattr(mod, name, None)
            if fn is not None and not hasattr(fn, "__wrapped__"):
                setattr(mod, name, _on_cpu(fn))
