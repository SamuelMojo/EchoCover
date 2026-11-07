#!/usr/bin/env bash
# Installs the F1 server on a Linux box (Amazon Linux 2023 or Ubuntu/Debian) and runs it as a
# systemd service that starts on boot. Run it from this folder: ./setup.sh
set -euo pipefail
cd "$(dirname "$0")"
DIR="$PWD"

# FastF1 needs Python 3.10 or newer. Amazon Linux 2023's default python3 is 3.9.
PY=""
for v in python3.13 python3.12 python3.11 python3.10 python3; do
  if command -v "$v" >/dev/null && "$v" -c 'import sys; sys.exit(sys.version_info < (3, 10))'; then
    PY="$v"
    break
  fi
done
if [ -z "$PY" ]; then
  if command -v dnf >/dev/null; then
    sudo dnf install -y python3.11
    PY=python3.11
  else
    echo "Please install Python 3.10 or newer, then run this again." >&2
    exit 1
  fi
fi
echo "Using $($PY --version)"

# Ubuntu/Debian ship venv support as a separate package.
if ! "$PY" -m venv .venv 2>/dev/null; then
  sudo apt-get update && sudo apt-get install -y python3-venv
  "$PY" -m venv .venv
fi
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -r requirements.txt

if [ ! -f f1server.env ]; then
  cat > f1server.env <<'EOF'
# Settings for the F1 server. After editing: sudo systemctl restart f1server

# F1 TV account, for exact car positions on the map (without it they're estimated from timing).
#F1_USERNAME=you@example.com
#F1_PASSWORD=your-password

# Optional shared secret. Put the same value in F1_TOKEN in the app's Config.kt.
#F1SERVER_TOKEN=
EOF
fi
chmod 600 f1server.env

sudo tee /etc/systemd/system/f1server.service >/dev/null <<EOF
[Unit]
Description=Echo Cover F1 server
After=network-online.target
Wants=network-online.target

[Service]
User=$(id -un)
WorkingDirectory=$DIR
EnvironmentFile=$DIR/f1server.env
ExecStart=$DIR/.venv/bin/python $DIR/server.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable f1server
sudo systemctl restart f1server

sleep 5
if curl -fs localhost:8765/health >/dev/null; then
  echo "F1 server is running on port 8765. Logs: journalctl -u f1server -f"
else
  echo "The service didn't answer yet. Check: journalctl -u f1server -n 50" >&2
fi
