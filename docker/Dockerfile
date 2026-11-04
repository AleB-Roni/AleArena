# Minecraft-Server (itzg) + alles, was AleArena braucht:
# ffmpeg, yt-dlp, Google Chrome, Xvfb (virtueller Bildschirm) und PulseAudio (Ton)
FROM itzg/minecraft-server:java25

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ffmpeg xvfb pulseaudio pulseaudio-utils \
      fonts-liberation fonts-noto-color-emoji ca-certificates curl \
 && curl -fsSL https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb -o /tmp/chrome.deb \
 && apt-get install -y --no-install-recommends /tmp/chrome.deb \
 && rm /tmp/chrome.deb \
 && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux -o /usr/local/bin/yt-dlp \
 && chmod +x /usr/local/bin/yt-dlp \
 && mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix \
 && rm -rf /var/lib/apt/lists/*
