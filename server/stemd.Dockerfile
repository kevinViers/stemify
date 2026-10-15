# stemd: separates new music into stems on the server's NVIDIA GPU (see stemd.py).
# Build from the repo root, since it needs the stemroller package:
#   docker build -f server/stemd.Dockerfile -t stemd .
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
# cu126 runs on NVIDIA driver 525 and up. TORCH_INDEX=cpu builds a small GPU-less image for testing.
ARG TORCH_INDEX=cu126
RUN pip install --no-cache-dir torch==2.14.1 torchvision==0.29.1 --index-url https://download.pytorch.org/whl/${TORCH_INDEX}
COPY server/stemd-requirements.txt /tmp/
# Everything is pinned, so --no-deps keeps pip from swapping torch for a non-CUDA build.
# gcc is only for diffq, which has no wheel.
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev \
 && pip install --no-cache-dir --no-deps -r /tmp/stemd-requirements.txt && pip check \
 && apt-get purge -y gcc libc6-dev && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
COPY stemroller/__init__.py stemroller/catalog.py stemroller/library_core.py /app/stemroller/
COPY stemroller/worker/__init__.py stemroller/worker/__main__.py stemroller/worker/audio_io.py \
     stemroller/worker/backends.py /app/stemroller/worker/
COPY server/stemd.py /app/server/
ENV MUSIC_DIR=/music STEMS_DIR=/stems STATE_DIR=/state MODELS_DIR=/models \
    TORCH_HOME=/models/torch HF_HOME=/models/hf NUMBA_CACHE_DIR=/tmp/numba HOME=/tmp \
    PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PYTHONWARNINGS=ignore::FutureWarning
# Unraid shares belong to nobody:users.
USER 99:100
CMD ["python", "/app/server/stemd.py"]
