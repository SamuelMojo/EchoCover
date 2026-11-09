"""
Echo Cover F1 server.

Watches the F1 calendar with FastF1 and, while a session is on, follows F1's live timing
feed and serves a small JSON summary that the Echo dashboard polls:

    GET /f1        live flag, lap count, leaderboard, track status and car positions
    GET /f1/track  the current circuit's outline, normalised to a 0..1 box

Car positions come from F1's live Position feed when an F1 TV account is configured
(F1_USERNAME / F1_PASSWORD). Without one F1 only sends timing data, so each car is placed
on the map from its mini-sector times instead.

    python server.py                                   # follow live sessions
    python server.py --record recordings               # ... and save the raw feed
    python server.py --replay recordings/x.jsonl       # replay a saved feed (for testing)
"""

import argparse
import hmac
import json
import logging
import math
import os
import statistics
import threading
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import fastf1
import requests
from fastf1._api import parse as parse_feed_value
from fastf1.exceptions import F1AuthError
from fastf1.internals.f1account import get_token
from fastf1.livetiming.client import SignalRClient
from fastf1.mvapi.api import get_circuit
from signalrcore.hub_connection_builder import HubConnectionBuilder

HERE = os.path.dirname(os.path.abspath(__file__))
log = logging.getLogger("f1server")

# Only the feeds the dashboard needs.
TOPICS = ["Heartbeat", "SessionInfo", "SessionStatus", "DriverList", "TimingData",
          "LapCount", "TrackStatus", "ExtrapolatedClock", "TimingAppData", "Position.z"]

# How long each kind of session can run, for deciding when to connect (minutes).
SESSION_MINUTES = {"Practice": 75, "Qualifying": 90, "Sprint Qualifying": 80,
                   "Sprint Shootout": 80, "Sprint": 80, "Race": 180}
CONNECT_EARLY = timedelta(minutes=15)
IDLE_RECONNECT_S = 90        # F1 sends a heartbeat every 15 s
FINISHED_LINGER_S = 600      # keep showing the final order for a while after the flag
LIVE_POSITIONS_FRESH_S = 10
SLOW_PACE = 1.15             # a car slower than this x its best lap is treated as going slowly
LOOP_SAMPLES = 15            # clean laps remembered per timing loop
LOOP_SAVE_EVERY_S = 60
LOOPS_FILE = os.path.join(HERE, "loops.json")

TYRES = {"SOFT": "S", "MEDIUM": "M", "HARD": "H", "INTERMEDIATE": "I", "WET": "W"}

TRACK_STATUS = {"1": "GREEN", "2": "YELLOW", "4": "SAFETY CAR", "5": "RED FLAG",
                "6": "VSC", "7": "VSC ENDING"}


# ---------- Small helpers ----------

def merge(base, update):
    """Applies one of F1's partial updates. Lists arrive as {"index": value} dicts."""
    if isinstance(base, dict) and isinstance(update, dict):
        for k, v in update.items():
            base[k] = merge(base[k], v) if k in base else v
        return base
    if isinstance(base, list) and isinstance(update, dict):
        for k, v in update.items():
            if not str(k).isdigit():
                continue
            i = int(k)
            if i < len(base):
                base[i] = merge(base[i], v)
            elif i == len(base):
                base.append(v)
        return base
    return update


def indexed(seq):
    """(index, value) pairs of a list, or of a list that arrived as an {"index": value} dict."""
    if isinstance(seq, list):
        return list(enumerate(seq))
    if isinstance(seq, dict):
        return sorted((int(k), v) for k, v in seq.items() if str(k).isdigit())
    return []


def seconds(text):
    """'1:20.364' / '80.364' / '01:02:03' -> seconds, or None."""
    if not text or not isinstance(text, str):
        return None
    try:
        total = 0.0
        for part in text.strip().lstrip("+").split(":"):
            total = total * 60 + float(part)
        return total
    except ValueError:
        return None


def utc_epoch(text):
    """F1 timestamps carry 7 fractional digits, which older Pythons refuse."""
    try:
        main, _, frac = text.rstrip("Z").partition(".")
        dt = datetime.fromisoformat(main).replace(tzinfo=timezone.utc)
        return dt.timestamp() + (float("0." + frac) if frac else 0.0)
    except (AttributeError, ValueError):
        return None


# ---------- Track outline ----------

class Track:
    """A circuit outline from the MultiViewer API (via FastF1), rotated the way F1 shows it.

    The outline was sampled at a steady rate around one lap from the start/finish line, so
    a point's index is proportional to time into the lap. That's what lets timing-based
    estimates be drawn straight onto it."""

    def __init__(self, track_id, data):
        self.id = track_id
        self.name = data.get("circuitName", "")
        angle = math.radians(data.get("rotation", 0))
        self._cos, self._sin = math.cos(angle), math.sin(angle)
        pts = [self._rotate(x, y) for x, y in zip(data["x"], data["y"])]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        self._minx, self._maxy = min(xs), max(ys)
        self._span = max(max(xs) - self._minx, self._maxy - min(ys)) or 1.0
        self.w = (max(xs) - self._minx) / self._span
        self.h = (self._maxy - min(ys)) / self._span
        self.points = [self._normalise(x, y) for x, y in pts]

    def _rotate(self, x, y):
        return x * self._cos - y * self._sin, x * self._sin + y * self._cos

    def _normalise(self, x, y):
        # Screen coordinates: y grows downwards.
        return round((x - self._minx) / self._span, 4), round((self._maxy - y) / self._span, 4)

    def place(self, x, y):
        """Raw F1 position coordinates -> normalised map coordinates."""
        return self._normalise(*self._rotate(x, y))

    def at_fraction(self, f):
        """Point reached after fraction f (0..1) of a lap's time."""
        n = len(self.points) - 1
        pos = min(max(f, 0.0), 1.0) * n
        i = min(int(pos), n - 1)
        t = pos - i
        (x0, y0), (x1, y1) = self.points[i], self.points[i + 1]
        return round(x0 + (x1 - x0) * t, 4), round(y0 + (y1 - y0) * t, 4)

    def as_json(self):
        return {"id": self.id, "name": self.name, "w": round(self.w, 4), "h": round(self.h, 4),
                "points": self.points}


def load_track(circuit_key, year):
    # The current season's map can lag behind; fall back to earlier years.
    for y in range(year, year - 4, -1):
        try:
            data = get_circuit(year=y, circuit_key=circuit_key)
        except Exception:
            log.exception("Track request failed")
            data = None
        if data and data.get("x"):
            log.info("Loaded %s track (%s map)", data.get("circuitName"), y)
            return Track(f"{circuit_key}-{year}", data)
    return None


# ---------- Live state ----------

class Progress:
    """Where one car is in its lap, measured in timing mini-sectors."""
    __slots__ = ("seg", "seg_at", "exact", "pace", "lap_start", "crossings")

    def __init__(self, seg=0, seg_at=None, exact=True):
        self.seg = seg              # mini-sectors completed this lap
        self.seg_at = seg_at        # when the last one was completed
        self.exact = exact          # False if seg_at is only when we connected
        self.pace = None            # lap time at the car's recent speed, from its mini-sectors
        self.lap_start = None       # when this lap started, if we saw it
        self.crossings = {}         # mini-sector -> time completed, this lap


class LoopMemory:
    """Where each timing loop sits on each circuit, kept on disk. Without it every session
    starts by guessing the loops' positions until a few clean laps have been timed."""

    def __init__(self, path):
        self.path = path
        try:
            with open(path) as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {}

    def load(self, key):
        return {int(seg): deque(fractions, maxlen=LOOP_SAMPLES)
                for seg, fractions in (self.data.get(key) or {}).items()}

    def save(self, key, samples):
        self.data[key] = {str(seg): [round(x, 5) for x in fractions]
                          for seg, fractions in sorted(samples.items())}
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w") as f:
                json.dump(self.data, f)
            os.replace(tmp, self.path)
        except OSError:
            log.exception("Couldn't save loop positions")


class LiveState:
    """F1's live timing merged into one picture, plus what's needed to put cars on the map."""

    def __init__(self, clock=time.time, memory=None):
        self.clock = clock
        self.lock = threading.RLock()
        self.track = None
        self.next_session = None    # next session on the calendar, for the home screen countdown
        self.memory = memory        # LoopMemory, or None to learn every session from scratch
        self.memory_key = None
        self.reset()

    def reset(self):
        with self.lock:
            self._save_loops()
            self.memory_key = None
            self.loops_changed = False
            self.loops_saved_at = 0.0
            self.topics = {}
            self.connected = False
            self.last_message_at = 0.0
            self.finished_at = None
            self.session_changed = False
            self.positions = {}         # racing number -> raw (x, y) from Position.z
            self.positions_at = 0.0
            self.progress = {}          # racing number -> Progress
            self.samples = {}           # mini-sector boundary -> recent lap fractions

    # --- Feed input ---

    def apply(self, topic, data, snapshot=False):
        now = self.clock()
        with self.lock:
            self.last_message_at = now
            if topic.endswith(".z"):
                if topic == "Position.z":
                    self._apply_positions(parse_feed_value(data, zipped=True), now)
                return
            if not isinstance(data, dict):
                return
            if topic == "SessionInfo":
                old = self.topics.get("SessionInfo", {}).get("Key")
                if not snapshot and old is not None and data.get("Key") not in (None, old):
                    self.session_changed = True     # new session: reconnect for a clean snapshot
            if topic == "TimingData" and not snapshot:
                self._track_progress(data.get("Lines") or {}, now)
            if snapshot or topic not in self.topics:
                self.topics[topic] = data
            else:
                merge(self.topics[topic], data)
            if topic == "TimingData" and snapshot:
                self._progress_from_snapshot(now)
            if topic == "SessionStatus":
                status = self.topics["SessionStatus"].get("Status")
                if status == "Finished":
                    self.finished_at = self.finished_at or now
                elif status in ("Started", "Aborted", "Inactive"):
                    self.finished_at = None

    def _apply_positions(self, data, now):
        samples = (data or {}).get("Position") or []
        if not samples:
            return
        for num, e in (samples[-1].get("Entries") or {}).items():
            if "X" in e and "Y" in e:
                self.positions[num] = (e["X"], e["Y"])
        self.positions_at = now

    def _segment_counts(self):
        for line in (self.topics.get("TimingData", {}).get("Lines") or {}).values():
            counts = [len(indexed(s.get("Segments"))) for _, s in indexed(line.get("Sectors"))]
            if counts and all(counts):
                return counts
        return []

    def _progress_from_snapshot(self, now):
        counts = self._segment_counts()
        for num, line in (self.topics["TimingData"].get("Lines") or {}).items():
            done = 0
            for _, sector in indexed(line.get("Sectors")):
                for _, seg in indexed(sector.get("Segments")):
                    if not seg.get("Status"):
                        break
                    done += 1
                else:
                    continue
                break
            self.progress[num] = Progress(done % max(sum(counts), 1), now, exact=False)

    def _track_progress(self, lines, now):
        """Notes when each car completes a mini-sector or a lap."""
        counts = self._segment_counts()
        if not counts:
            return
        total = sum(counts)
        offsets = [sum(counts[:i]) for i in range(len(counts))]
        bounds = self._boundaries(counts)
        for num, line in lines.items():
            if not isinstance(line, dict):
                continue
            passed, cleared = 0, False
            for s, sector in indexed(line.get("Sectors")):
                if not isinstance(sector, dict) or s >= len(counts):
                    continue
                for j, seg in indexed(sector.get("Segments")):
                    if isinstance(seg, dict) and seg.get("Status"):
                        passed = max(passed, offsets[s] + j + 1)
                    elif isinstance(seg, dict) and "Status" in seg:
                        cleared = True
            if "NumberOfLaps" in line:
                passed = total
            p = self.progress.get(num)
            if cleared and not passed and p is not None and p.seg == 0 and p.lap_start is not None:
                # F1 clears last lap's mini-sectors as the car reaches the first timing loop.
                # That loop's own status is only sent when it changes, so the clear is often
                # the only sign the car has passed it.
                passed = 1
            if not passed:
                continue
            p = p or self.progress.setdefault(num, Progress())
            if passed >= total:
                # The last mini-sector and the lap count both report the same line crossing.
                if p.lap_start is not None and now - p.lap_start < 10:
                    continue
                self._note_pace(p, total, now, bounds)
                self._learn(p, now)
                p.seg, p.seg_at, p.exact, p.lap_start, p.crossings = 0, now, True, now, {}
            elif passed > p.seg:
                self._note_pace(p, passed, now, bounds)
                p.seg, p.seg_at, p.exact = passed, now, True
                p.crossings[passed] = now
            elif passed < p.seg - 3:
                # Missed a line crossing (reconnect, pit lane): resync without a lap start.
                p.seg, p.seg_at, p.exact, p.lap_start, p.crossings = passed, now, True, None, {}
                p.pace = None

    @staticmethod
    def _note_pace(p, seg, now, bounds):
        """Updates the car's recent pace from the mini-sector(s) it just completed."""
        if not p.exact or p.seg_at is None or seg <= p.seg or now <= p.seg_at:
            return
        width = bounds[seg] - bounds[p.seg]
        if width > 0:
            pace = (now - p.seg_at) / width
            p.pace = pace if p.pace is None else (p.pace + pace) / 2

    def _learn(self, p, now):
        """After a clean lap, records where each mini-sector boundary falls as a lap fraction."""
        if p.lap_start is None or not p.crossings:
            return
        lap = now - p.lap_start
        ref = self._typical_lap()
        if not ref or not (0.95 * ref < lap < 1.2 * ref):
            return      # standing start, pit stop, safety car or a slow lap: not representative
        for seg, t in p.crossings.items():
            self.samples.setdefault(seg, deque(maxlen=LOOP_SAMPLES)).append((t - p.lap_start) / lap)
        self.loops_changed = True
        if now - self.loops_saved_at > LOOP_SAVE_EVERY_S:
            self._save_loops()

    def _recall_loops(self, counts):
        """Once the circuit is known, starts from the loop positions earlier sessions there
        learned (a circuit's loops are the same all weekend, and usually year to year)."""
        if self.memory is None or self.memory_key is not None:
            return
        circuit = ((self.topics.get("SessionInfo") or {}).get("Meeting") or {}).get("Circuit") or {}
        if not circuit.get("Key"):
            return
        self.memory_key = f"{circuit['Key']}/{'-'.join(map(str, counts))}"
        remembered = self.memory.load(self.memory_key)
        for seg, fractions in self.samples.items():
            remembered.setdefault(seg, deque(maxlen=LOOP_SAMPLES)).extend(fractions)
        self.samples = remembered
        if remembered:
            log.info("Using remembered timing-loop positions for %s",
                     circuit.get("ShortName") or circuit["Key"])

    def _save_loops(self):
        if self.memory is not None and self.memory_key and getattr(self, "loops_changed", False):
            self.memory.save(self.memory_key, self.samples)
            self.loops_changed = False
            self.loops_saved_at = self.clock()

    def _typical_lap(self):
        """Median personal best lap. Unlike the last lap, it isn't thrown by red flags."""
        laps = [seconds((line.get("BestLapTime") or {}).get("Value"))
                for line in (self.topics.get("TimingData", {}).get("Lines") or {}).values()]
        laps = [x for x in laps if x]
        return statistics.median(laps) if laps else None

    def _boundaries(self, counts):
        """Lap fraction at which each mini-sector ends: learned where possible, otherwise
        spread evenly through each sector using recent sector times."""
        self._recall_loops(counts)
        typical = self._typical_lap()
        shares = []
        for line in (self.topics.get("TimingData", {}).get("Lines") or {}).values():
            sectors = dict(indexed(line.get("Sectors")))
            times = [seconds((sectors.get(s) or {}).get("Value"))
                     or seconds((sectors.get(s) or {}).get("PreviousValue"))
                     for s in range(len(counts))]
            if all(times) and (typical is None or 0.9 * typical < sum(times) < 1.3 * typical):
                shares.append([t / sum(times) for t in times])
        if shares:
            share = [statistics.median(col) for col in zip(*shares)]
            share = [x / sum(share) for x in share]
        else:
            share = [1 / len(counts)] * len(counts)
        bounds, start = [0.0], 0.0
        for s, n in enumerate(counts):
            bounds += [start + share[s] * (j + 1) / n for j in range(n)]
            start += share[s]
        for seg, fractions in self.samples.items():
            if len(fractions) >= 3 and 0 < seg < len(bounds) - 1:
                bounds[seg] = statistics.median(fractions)
        bounds[-1] = 1.0
        for i in range(1, len(bounds)):
            bounds[i] = max(bounds[i], bounds[i - 1])
        return bounds

    def _estimate(self, num, line, bounds, typical, now):
        """Lap fraction for a car: its last mini-sector plus time since, held at the next
        boundary until the car actually reaches it (so stopped cars don't run ahead).

        Racing, the car's best lap is the steadiest guide to its speed. When it's clearly
        going slower (safety car, VSC, a slow lap) its recent mini-sector pace is used instead,
        otherwise every car would race ahead and bunch up on the next timing loop."""
        p = self.progress.get(num)
        if p is None or p.seg_at is None:
            return None
        lap = seconds((line.get("BestLapTime") or {}).get("Value")) or typical
        if p.pace and p.pace > SLOW_PACE * lap:
            lap = min(p.pace, 3 * lap)
        seg = min(p.seg, len(bounds) - 2)
        room = bounds[seg + 1] - bounds[seg]
        return bounds[seg] + min((now - p.seg_at) / lap, room)

    # --- Output ---

    def circuit(self):
        with self.lock:
            info = self.topics.get("SessionInfo") or {}
            key = ((info.get("Meeting") or {}).get("Circuit") or {}).get("Key")
            year = (info.get("StartDate") or "")[:4]
            return (key, int(year)) if key and year.isdigit() else None

    def is_live(self, now):
        status = (self.topics.get("SessionStatus") or {}).get("Status")
        if not self.connected or "TimingData" not in self.topics:
            return False
        if status in ("Inactive", "Started", "Aborted"):
            return True
        return status == "Finished" and now - (self.finished_at or now) < FINISHED_LINGER_S

    def _tyre(self, num):
        """The car's current tyre: compound, laps on the set (including earlier sessions) and
        whether it was new when fitted. Empty until F1 has said which compound it's on."""
        app = ((self.topics.get("TimingAppData") or {}).get("Lines") or {}).get(num) or {}
        stints = indexed(app.get("Stints"))
        if not stints:
            return {}
        stint = stints[-1][1] or {}
        compound = TYRES.get(str(stint.get("Compound") or "").upper())
        if not compound:
            return {}
        laps = stint.get("TotalLaps")
        return {"tyre": compound,
                "tyreLaps": int(laps) if isinstance(laps, (int, float)) else None,
                "tyreNew": str(stint.get("New")).lower() == "true"}

    def summary(self):
        now = self.clock()
        with self.lock:
            if not self.is_live(now):
                return {"live": False, "next": self.next_session}
            t = self.topics
            info = t.get("SessionInfo") or {}
            race = info.get("Type") == "Race"
            timing = t.get("TimingData") or {}
            lines = timing.get("Lines") or {}
            driver_list = t.get("DriverList") or {}
            track = self.track
            counts = self._segment_counts()
            bounds = self._boundaries(counts) if counts else None
            typical = self._typical_lap() or 90.0
            live_positions = now - self.positions_at < LIVE_POSITIONS_FRESH_S
            part = timing.get("SessionPart")

            drivers = []
            for num, line in sorted(lines.items(), key=lambda kv: int(kv[1].get("Line") or 99)):
                d = driver_list.get(num) or {}
                row = {
                    "pos": int(line.get("Position") or line.get("Line") or 0),
                    "num": num,
                    "tla": d.get("Tla") or num,
                    "color": d.get("TeamColour") or "888888",
                    "inPit": bool(line.get("InPit")),
                    "out": bool(line.get("Retired") or line.get("Stopped") or line.get("KnockedOut")),
                    "x": None,
                    "y": None,
                }
                row.update(self._tyre(num))
                if race:
                    row["gap"] = line.get("GapToLeader") or ""
                    row["interval"] = (line.get("IntervalToPositionAhead") or {}).get("Value") or ""
                else:
                    best = line.get("BestLapTime") or {}
                    diff = line.get("TimeDiffToFastest") or ""
                    if part:    # qualifying: times for the current Q1/Q2/Q3
                        best = dict(indexed(line.get("BestLapTimes"))).get(part - 1) or best
                        diff = (dict(indexed(line.get("Stats"))).get(part - 1) or {}).get(
                            "TimeDiffToFastest", diff)
                    row["gap"] = best.get("Value") or ""
                    row["interval"] = diff or ""
                if track and not row["inPit"] and not row["out"]:
                    if live_positions and num in self.positions:
                        row["x"], row["y"] = track.place(*self.positions[num])
                    elif bounds:
                        f = self._estimate(num, line, bounds, typical, now)
                        if f is not None:
                            row["x"], row["y"] = track.at_fraction(f)
                drivers.append(row)

            laps = t.get("LapCount") or {}
            clock = t.get("ExtrapolatedClock") or {}
            remaining = seconds(clock.get("Remaining"))
            if remaining is not None and clock.get("Extrapolating"):
                remaining -= now - (utc_epoch(clock.get("Utc")) or now)
            track_status = (t.get("TrackStatus") or {}).get("Status", "1")

            return {
                "live": True,
                "sessionKey": info.get("Key"),
                "event": (info.get("Meeting") or {}).get("Name", ""),
                "session": info.get("Name", ""),
                "race": race,
                "part": part,
                "status": (t.get("SessionStatus") or {}).get("Status"),
                "lap": laps.get("CurrentLap"),
                "totalLaps": laps.get("TotalLaps"),
                "remaining": max(0, int(remaining)) if remaining is not None else None,
                "trackStatus": track_status,
                "trackStatusText": TRACK_STATUS.get(track_status, ""),
                "trackId": track.id if track else None,
                "positions": "live" if live_positions else "estimated",
                "drivers": drivers,
            }


# ---------- F1 live timing connection ----------

class LiveFeed:
    """F1's SignalR Core live timing stream, connected the same way as FastF1's SignalRClient."""

    def __init__(self, state, record_dir=None):
        self.state = state
        self.record_dir = record_dir
        self.open = False
        self._conn = None
        self._record = None

    def start(self):
        # Pre-negotiate to get the load balancer cookie, like FastF1's client.
        r = requests.options(SignalRClient._negotiate_url, timeout=15)
        options = {"verify_ssl": True,
                   "headers": {"Cookie": f"AWSALBCORS={r.cookies['AWSALBCORS']}"}}
        if os.environ.get("F1_USERNAME") and os.environ.get("F1_PASSWORD"):
            try:
                get_token()     # sign in now so a bad password shows up in the log straight away
                options["access_token_factory"] = lambda: get_token().value
            except F1AuthError as exc:
                log.warning("F1 sign-in failed, continuing without live positions: %s", exc)
        else:
            log.info("No F1 account configured: car positions will be estimated from timing")

        if self.record_dir:
            os.makedirs(self.record_dir, exist_ok=True)
            name = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H%M.jsonl")
            self._record = open(os.path.join(self.record_dir, name), "a")

        opened = threading.Event()
        self._conn = (HubConnectionBuilder()
                      .with_url(SignalRClient._connection_url, options=options)
                      .configure_logging(logging.WARNING)
                      .build())
        self._conn.on_open(opened.set)
        self._conn.on_close(self._closed)
        self._conn.on("feed", self._on_feed)
        self._conn.start()
        if not opened.wait(20):
            self.stop()
            raise ConnectionError("live timing connection timed out")
        self.open = True
        with self.state.lock:
            self.state.connected = True
            self.state.last_message_at = self.state.clock()
        self._conn.send("Subscribe", [TOPICS], on_invocation=self._on_snapshot)
        log.info("Connected to F1 live timing")

    def stop(self):
        self.open = False
        try:
            if self._conn:
                self._conn.stop()
        except Exception:
            pass
        if self._record:
            self._record.close()
            self._record = None

    def _closed(self):
        self.open = False
        log.info("Live timing connection closed")

    def _on_snapshot(self, msg):
        for topic, data in (getattr(msg, "result", None) or {}).items():
            self._handle(topic, data, "")

    def _on_feed(self, msg):
        if isinstance(msg, list) and len(msg) >= 2:
            self._handle(msg[0], msg[1], msg[2] if len(msg) > 2 else "?")

    def _handle(self, topic, data, ts):
        if self._record:
            self._record.write(json.dumps({"t": time.time(), "topic": topic, "data": data,
                                           "ts": ts}) + "\n")
            self._record.flush()
        try:
            self.state.apply(topic, data, snapshot=(ts == ""))
        except Exception:
            log.exception("Couldn't apply %s update", topic)


class Schedule:
    """Session start times from FastF1's event schedule, refreshed every few hours."""

    def __init__(self):
        self._sessions = []         # (event, session, start, minutes), in start order
        self._next_load = 0.0

    def current(self, now):
        """The session (name, start) whose window contains `now`, if any."""
        self._refresh(now)
        for event, session, start, minutes in self._sessions:
            if start - CONNECT_EARLY <= now <= start + timedelta(minutes=minutes):
                return f"{event} - {session}", start
        return None

    def upcoming(self, now):
        """The next session to start after `now`, for the Echo's countdown."""
        self._refresh(now)
        for event, session, start, _ in self._sessions:
            if start > now:
                return {"event": event, "session": session, "start": int(start.timestamp())}
        return None

    def _refresh(self, now):
        if time.time() >= self._next_load:
            self._load(now.year)

    def _load(self, year):
        sessions = []
        # Next year's calendar too, so the countdown carries on over the winter break.
        for y in (year, year + 1):
            try:
                events = fastf1.get_event_schedule(y, include_testing=False)
            except Exception as exc:
                if y == year:
                    log.warning("Couldn't load the %s schedule: %s", y, exc)
                    self._next_load = time.time() + 1800
                    return
                continue
            for _, ev in events.iterrows():
                for i in range(1, 6):
                    name, start = ev.get(f"Session{i}"), ev.get(f"Session{i}DateUtc")
                    if not name or start is None or str(start) == "NaT":
                        continue
                    kind = "Practice" if name.startswith("Practice") else name
                    sessions.append((ev["EventName"], name,
                                     start.to_pydatetime().replace(tzinfo=timezone.utc),
                                     SESSION_MINUTES.get(kind, 120)))
        sessions.sort(key=lambda s: s[2])
        self._sessions = sessions
        self._next_load = time.time() + 6 * 3600
        nxt = self.upcoming(datetime.now(timezone.utc))
        if nxt:
            log.info("Next session: %s - %s at %s UTC", nxt["event"], nxt["session"],
                     datetime.fromtimestamp(nxt["start"], timezone.utc).strftime("%a %d %b %H:%M"))


def run_live(state, record_dir):
    schedule = Schedule()
    feed = None
    while True:
        try:
            now = datetime.now(timezone.utc)
            session = schedule.current(now)
            state.next_session = schedule.upcoming(now)
            if feed is None and session:
                log.info("Session window open: %s", session[0])
                feed = LiveFeed(state, record_dir)
                try:
                    feed.start()
                except Exception as exc:
                    log.warning("Couldn't connect to live timing: %s", exc)
                    feed.stop()
                    feed = None
            elif feed is not None:
                idle = state.clock() - state.last_message_at
                over = session is None and not state.is_live(state.clock())
                if not feed.open or idle > IDLE_RECONNECT_S or state.session_changed or over:
                    log.info("Disconnecting (%s)", "session over" if over else "reconnecting")
                    feed.stop()
                    feed = None
                    state.reset()
                    continue
        except Exception:
            log.exception("Scheduler error")
        time.sleep(10)


# ---------- Replay (testing without a live session) ----------

class ReplayClock:
    def __init__(self, speed):
        self.speed = speed
        self.set(0.0)

    def set(self, virtual):
        self._virtual, self._real = virtual, time.time()

    def __call__(self):
        return self._virtual + (time.time() - self._real) * self.speed


def run_replay(state, path, speed, skip):
    with open(path) as f:
        rows = [json.loads(line) for line in f if line.strip()]
    clock = ReplayClock(speed)
    state.clock = clock
    while True:
        state.reset()
        state.connected = True
        start = rows[0]["t"] + skip
        log.info("Replaying %s from +%ds at %gx", os.path.basename(path), skip, speed)
        for row in rows:
            if row["t"] <= start:
                clock.set(row["t"])     # fast-forward through the skipped part
            else:
                if clock() < start:
                    clock.set(start)
                wait = (row["t"] - clock()) / speed
                if wait > 0:
                    time.sleep(wait)
            state.apply(row["topic"], row["data"], snapshot=(row["ts"] == ""))
        log.info("Replay finished, starting again")
        time.sleep(5)


# ---------- HTTP ----------

def track_loop(state):
    """Fetches the circuit outline whenever the session moves to a new circuit."""
    failed = {}
    while True:
        circuit = state.circuit()
        if circuit:
            track_id = f"{circuit[0]}-{circuit[1]}"
            if (state.track is None or state.track.id != track_id) and time.time() > failed.get(track_id, 0):
                track = load_track(*circuit)
                if track:
                    state.track = track
                else:
                    log.warning("No track outline for circuit %s", circuit[0])
                    failed[track_id] = time.time() + 300
        time.sleep(3)


def serve(state, port, token):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if token and not hmac.compare_digest(self.headers.get("Authorization", ""),
                                                 f"Bearer {token}"):
                return self._send(401, {"error": "unauthorised"})
            path = urlparse(self.path).path.rstrip("/")
            if path == "/f1":
                return self._send(200, state.summary())
            if path == "/f1/track":
                track = state.track
                return self._send(200, track.as_json()) if track else self._send(404, {"error": "no track"})
            if path in ("", "/health"):
                return self._send(200, {"ok": True})
            self._send(404, {"error": "not found"})

        def _send(self, code, obj):
            body = json.dumps(obj, separators=(",", ":")).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    log.info("Serving on port %d", port)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


def main():
    ap = argparse.ArgumentParser(description="Live F1 data for the Echo Cover dashboard")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8765)))
    ap.add_argument("--record", metavar="DIR", help="save the raw live feed to DIR")
    ap.add_argument("--replay", metavar="FILE", help="replay a recorded feed instead of going live")
    ap.add_argument("--speed", type=float, default=1.0, help="replay speed multiplier")
    ap.add_argument("--skip", type=float, default=0.0, help="seconds to skip at the start of a replay")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    fastf1.set_log_level("WARNING")
    cache = os.path.join(HERE, "cache")
    os.makedirs(cache, exist_ok=True)
    fastf1.Cache.configure(cache_dir=cache)

    # Replays learn from scratch, so they behave the same every time.
    state = LiveState(memory=None if args.replay else LoopMemory(LOOPS_FILE))
    threading.Thread(target=track_loop, args=(state,), daemon=True).start()
    if args.replay:
        threading.Thread(target=run_replay, args=(state, args.replay, args.speed, args.skip),
                         daemon=True).start()
    else:
        threading.Thread(target=run_live, args=(state, args.record), daemon=True).start()
    serve(state, args.port, os.environ.get("F1SERVER_TOKEN", ""))


if __name__ == "__main__":
    main()
