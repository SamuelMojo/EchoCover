# F1 server

A small Python service that powers the Echo's live F1 dashboard. It uses
[FastF1](https://docs.fastf1.dev) to check the F1 calendar. When a session is on, it connects to F1's
live timing feed and serves JSON that the Echo polls once a second:

- `GET /f1`: whether a session is live, lap count (or time left), track status, and the
  leaderboard with each car's position on the map. Between sessions it gives the next session
  on the calendar instead, for the home screen countdown.
- `GET /f1/track`: the circuit outline, from the MultiViewer circuit data that FastF1 uses.

FastF1 is Python-only and can't run on the Echo itself, so this runs somewhere else that stays on.
An AWS `t4g.micro` works well; the server uses about 150 MB of RAM and very little CPU.

## Car positions: estimated or exact

Since 2025, F1's live feed only sends car GPS positions to signed-in **F1 TV** subscribers. This
also applies to FastF1's historical data. Without an account you still get lap count, leaderboard,
gaps, pit stops and flags. The server then **estimates** where each car is from its mini-sector
times (about 22 timing loops per lap). It learns where each loop sits from clean laps and
remembers this per circuit in `loops.json`. The first time it sees a track, positions are rough
for the first couple of minutes of running. After that, and in every later session there, they
start out close.

With an F1 TV account, put `F1_USERNAME` and `F1_PASSWORD` in `f1server.env` (see below). The map
then uses the real positions.

## Deploy to your EC2 instance

1. **Open the port.** In the EC2 console, open the instance's security group. Add an inbound rule:
   Custom TCP, port `8765`. For the source, pick *My IP*, or *Anywhere* if you also set
   `F1SERVER_TOKEN`. Your home IP can change, so *My IP* may need updating now and then.

2. **Copy this folder up** (from the `EchoCover 2` folder on your Mac). Use `ec2-user@` on Amazon
   Linux or `ubuntu@` on Ubuntu:

   ```
   rsync -av --exclude .venv --exclude cache --exclude __pycache__ --exclude loops.json \
       -e "ssh -i ~/path/to/key.pem" f1server/ ec2-user@<instance-ip>:f1server/
   ```

   `--exclude loops.json` keeps the loop positions the instance has learned.

3. **Install and start it** on the instance:

   ```
   ssh -i ~/path/to/key.pem ec2-user@<instance-ip>
   cd f1server && chmod +x setup.sh && ./setup.sh
   ```

   This installs Python 3.10+ if needed, then sets up a `systemd` service that starts on boot and
   restarts if it crashes.

4. **Optional settings** go in `~/f1server/f1server.env`. Edit it, then run
   `sudo systemctl restart f1server`.

5. **Point the app at it.** In `app/src/main/java/com/echocover/Config.kt` set
   `F1_SERVER = "http://<instance-ip>:8765"` (and `F1_TOKEN` if you set one). Rebuild and install
   the APK.

Check it from your Mac with `curl http://<instance-ip>:8765/f1`. It returns `{"live":false}`
outside sessions. Logs on the instance: `journalctl -u f1server -f`.

The instance's public IP changes if you stop and start it. Attach an Elastic IP if you want a
fixed address.

## Run locally / test without a live session

```
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python server.py                       # follow live sessions, port 8765
.venv/bin/python server.py --record recordings   # ... and save the raw feed to replay later
.venv/bin/python server.py --replay recordings/2026-mexico-race.jsonl --skip 2400
```

`--replay` plays a saved feed as if it were live, so you can try the dashboard any time.
`--skip SECONDS` jumps ahead. `--speed 2` plays faster. The included recording is the 2026 Mexico
City GP from the red flag onward; `--skip 2400` starts just after the standing restart. The Android
emulator reaches your Mac at `http://10.0.2.2:8765`.
