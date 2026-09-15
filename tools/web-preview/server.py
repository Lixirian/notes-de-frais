#!/usr/bin/env python3
"""
Aperçu web de l'app : miroir d'un émulateur / appareil Android dans le navigateur.

- Capture l'écran via `adb exec-out screencap -p` (quelques images/seconde, suffisant pour
  juger le rendu) et renvoie les clics, glissements et la saisie clavier via `adb shell input`.
- Permet de lancer les AVD du projet (téléphone, tablette, pliable), de plier/déplier et
  de faire pivoter l'écran.

Aucune dépendance hors bibliothèque standard Python 3.

Usage : python tools/web-preview/server.py [--port 8765] [--sdk <chemin du SDK>]
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT_ROOT = HERE.parent.parent
PNG_MAGIC = bytes([0x89]) + b"PNG"


def find_sdk(explicit: str | None) -> Path:
    candidates = [explicit, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local_props = PROJECT_ROOT / "local.properties"
    if local_props.exists():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                raw = line.split("=", 1)[1].strip()
                candidates.append(raw.replace("\\:", ":").replace("\\\\", "\\"))
    if os.name == "nt":
        candidates.append(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"))
    else:
        candidates.append(os.path.expanduser("~/Android/Sdk"))
        candidates.append(os.path.expanduser("~/Library/Android/sdk"))
    for c in candidates:
        if c and Path(c).exists():
            return Path(c)
    sys.exit("SDK Android introuvable : passez --sdk ou définissez ANDROID_HOME")


def _no_window() -> int:
    return subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


class Adb:
    def __init__(self, sdk: Path):
        exe = "adb.exe" if os.name == "nt" else "adb"
        self.adb = str(sdk / "platform-tools" / exe)
        emu = "emulator.exe" if os.name == "nt" else "emulator"
        self.emulator = str(sdk / "emulator" / emu)
        self.lock = threading.Lock()
        self.display_cache: dict[str, str | None] = {}

    def run(self, *args: str, serial: str | None = None, timeout: float = 15) -> bytes:
        cmd = [self.adb]
        if serial:
            cmd += ["-s", serial]
        cmd += list(args)
        out = subprocess.run(cmd, capture_output=True, timeout=timeout, creationflags=_no_window())
        return out.stdout

    def devices(self) -> list[dict]:
        text = self.run("devices", "-l").decode("utf-8", "replace")
        result = []
        for line in text.splitlines()[1:]:
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            model = next((p.split(":", 1)[1] for p in parts if p.startswith("model:")), "")
            name = model
            if serial.startswith("emulator-") and state == "device":
                avd = self.run("emu", "avd", "name", serial=serial, timeout=5).decode("utf-8", "replace").splitlines()
                if avd and avd[0].strip() and avd[0].strip() != "OK":
                    name = avd[0].strip()
            result.append({"serial": serial, "state": state, "name": name or serial})
        return result

    def screenshot(self, serial: str) -> bytes:
        with self.lock:
            display = self.display_cache.get(serial)
            args = ["exec-out", "screencap", "-p"] + (["-d", display] if display else [])
            png = self.run(*args, serial=serial, timeout=20)
            if not png.startswith(PNG_MAGIC):
                # Pliables / doubles écrans : screencap exige l'identifiant d'affichage.
                display = self.primary_display(serial)
                self.display_cache[serial] = display
                if display:
                    png = self.run("exec-out", "screencap", "-p", "-d", display, serial=serial, timeout=20)
                idx = png.find(PNG_MAGIC)
                if idx > 0:
                    png = png[idx:]
            return png

    def primary_display(self, serial: str) -> str | None:
        text = self.run("shell", "dumpsys", "SurfaceFlinger", "--display-id", serial=serial).decode("utf-8", "replace")
        for line in text.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0] == "Display" and parts[1].isdigit():
                return parts[1]
        return None

    def size(self, serial: str) -> tuple[int, int]:
        text = self.run("shell", "wm", "size", serial=serial).decode("utf-8", "replace")
        lines = [l for l in text.splitlines() if "size:" in l]
        chosen = lines[-1] if lines else "0x0"
        dims = chosen.split(":")[-1].strip()
        w, h = dims.split("x")
        return int(w), int(h)

    def list_avds(self) -> list[str]:
        out = subprocess.run([self.emulator, "-list-avds"], capture_output=True, timeout=20, creationflags=_no_window())
        return [l.strip() for l in out.stdout.decode("utf-8", "replace").splitlines() if l.strip() and not l.startswith("INFO")]

    def start_avd(self, name: str) -> None:
        subprocess.Popen(
            [self.emulator, "-avd", name, "-no-boot-anim", "-gpu", "auto", "-no-audio"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=_no_window(),
        )


class Handler(BaseHTTPRequestHandler):
    adb: Adb  # injecté au démarrage

    def log_message(self, fmt, *args):  # silence
        pass

    def _json(self, payload, status=200):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(url.query)
        serial = qs.get("serial", [None])[0]
        try:
            if url.path in ("/", "/index.html"):
                html = (HERE / "index.html").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(html)))
                self.end_headers()
                self.wfile.write(html)
            elif url.path == "/api/devices":
                self._json({"devices": self.adb.devices(), "avds": self.adb.list_avds()})
            elif url.path == "/api/size":
                w, h = self.adb.size(serial)
                self._json({"width": w, "height": h})
            elif url.path == "/api/frame":
                png = self.adb.screenshot(serial)
                if not png.startswith(PNG_MAGIC):
                    self._json({"error": "capture impossible"}, 503)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(png)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(png)
            else:
                self.send_error(404)
        except Exception as e:  # noqa: BLE001
            self._json({"error": str(e)}, 500)

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        serial = body.get("serial")
        try:
            if url.path == "/api/tap":
                self.adb.run("shell", "input", "tap", str(int(body["x"])), str(int(body["y"])), serial=serial)
            elif url.path == "/api/swipe":
                self.adb.run(
                    "shell", "input", "swipe",
                    str(int(body["x1"])), str(int(body["y1"])), str(int(body["x2"])), str(int(body["y2"])),
                    str(int(body.get("duration", 200))), serial=serial,
                )
            elif url.path == "/api/key":
                self.adb.run("shell", "input", "keyevent", str(int(body["key"])), serial=serial)
            elif url.path == "/api/text":
                text = str(body.get("text", ""))
                if text:
                    # `input text` ne gère pas les espaces : %s est la convention d'adb.
                    self.adb.run("shell", "input", "text", text.replace(" ", "%s"), serial=serial)
            elif url.path == "/api/rotate":
                rotation = int(body.get("rotation", 0))
                self.adb.run("shell", "settings", "put", "system", "accelerometer_rotation", "0", serial=serial)
                self.adb.run("shell", "settings", "put", "system", "user_rotation", str(rotation), serial=serial)
            elif url.path == "/api/fold":
                action = "fold" if body.get("folded") else "unfold"
                self.adb.run("emu", action, serial=serial)
                self.adb.display_cache.pop(serial, None)
            elif url.path == "/api/start":
                self.adb.start_avd(str(body["avd"]))
            elif url.path == "/api/install":
                apk = PROJECT_ROOT / "dist" / "notes-de-frais.apk"
                package = "com.lixirian.notesdefrais"
                if not apk.exists():
                    apk = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
                    package = "com.lixirian.notesdefrais.debug"
                if not apk.exists():
                    self._json({"error": "Aucun APK : lancez ./build-apk.sh ou ./gradlew assembleDebug"}, 404)
                    return
                out = self.adb.run("install", "-r", str(apk), serial=serial, timeout=180).decode("utf-8", "replace")
                self.adb.run("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1", serial=serial)
                self._json({"ok": True, "output": out})
                return
            else:
                self.send_error(404)
                return
            self._json({"ok": True})
        except Exception as e:  # noqa: BLE001
            self._json({"error": str(e)}, 500)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--sdk", default=None)
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()

    Handler.adb = Adb(find_sdk(args.sdk))
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{args.port}/"
    print(f"Aperçu web : {url}  (Ctrl+C pour arrêter)")
    if not args.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
