FROM python:3.12-alpine
RUN apk add --no-cache ffmpeg
COPY stemserve.py /app/stemserve.py
ENV STEMS_DIR=/stems CACHE_DIR=/cache PORT=8765 PYTHONUNBUFFERED=1
EXPOSE 8765
# Unraid shares belong to nobody:users.
USER 99:100
CMD ["python", "/app/stemserve.py"]
