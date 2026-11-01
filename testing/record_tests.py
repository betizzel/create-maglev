#!/usr/bin/env python3
"""Run the isolated Maglev recorded-client regression suite.

Requires Java 17, ffmpeg/ffprobe, Xvfb/xauth, and requirements.txt. Captures
only the X11 display (no audio) and keeps all mutable Minecraft profiles and
worlds under testing/build/run/.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
TESTING = ROOT / "testing"
BUILD = TESTING / "build"
CASES = (
    "stock-control",
    "electric-powered",
    "bullet-powered",
    "maglev-powered",
    "maglev-unpowered",
    "maglev-loss",
    "maglev-rider",
)
EVENTS = (
    "MAGLEV_TEST_CLIENT_READY",
    "MAGLEV_TEST_CLIENT_RESULT",
    "MAGLEV_TEST_RECORDING_DONE",
    "MAGLEV_TEST_CLIENT_COMPLETE",
    "MAGLEV_TEST_CLIENT_FATAL",
)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def resolved(value: str | Path, base: Path = ROOT) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = base / path
    return path.resolve()


def safe_reset_directory(path: Path) -> None:
    """Reset one known harness-owned directory, never a caller-provided path."""
    build_root = BUILD.resolve()
    candidate = path.absolute()
    if candidate == build_root or build_root not in candidate.parents:
        raise RuntimeError(f"Refusing to reset a path outside testing/build: {candidate}")
    if candidate.is_symlink():
        candidate.unlink()
    elif candidate.exists():
        shutil.rmtree(candidate)
    candidate.mkdir(parents=True, exist_ok=True)


def safe_remove_file(path: Path) -> None:
    build_root = BUILD.resolve()
    candidate = path.absolute()
    if candidate == build_root or build_root not in candidate.parents:
        raise RuntimeError(f"Refusing to remove a path outside testing/build: {candidate}")
    if candidate.is_symlink() or candidate.is_file():
        candidate.unlink()


def stop(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)


def read_log(process: subprocess.Popen, path: Path, events: queue.Queue | None = None,
             ready: threading.Event | None = None) -> None:
    try:
        with path.open("w", encoding="utf-8", errors="replace") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if ready is not None and "Done (" in line and 'For help, type "help"' in line:
                    ready.set()
                if events is None:
                    continue
                for marker in EVENTS:
                    position = line.find(marker)
                    if position < 0:
                        continue
                    raw = line[position + len(marker):].strip()
                    if raw.startswith(":"):
                        raw = raw[1:].strip()
                    try:
                        events.put((marker, json.loads(raw)))
                    except json.JSONDecodeError as error:
                        events.put(("reader_error", f"{marker}: {error}: {raw}"))
                    break
    except OSError as error:
        if events is not None:
            events.put(("reader_error", f"Could not write {path}: {error}"))
    finally:
        if events is not None:
            events.put(("process_exit", process.wait()))


class Capture:
    def __init__(self, video: Path, case: str, label: str):
        self.video = video
        self.case = case
        self.label = label
        self.ready = threading.Event()
        self.ffmpeg_log = video.with_suffix(".ffmpeg.log")
        command = [
            "ffmpeg", "-hide_banner", "-y", "-f", "x11grab", "-video_size", "1280x800",
            "-framerate", "20", "-i", os.environ["DISPLAY"] + "+0,0", "-an",
            "-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-metadata",
            f"title={case} - {label}", str(video),
        ]
        self.process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace", bufsize=1, start_new_session=True,
        )
        self.reader = threading.Thread(target=self._log, daemon=True)
        self.reader.start()
        if not self.ready.wait(20):
            stop(self.process)
            self.reader.join(timeout=5)
            raise RuntimeError(f"Video capture did not start; inspect {self.ffmpeg_log}")

    def _log(self) -> None:
        with self.ffmpeg_log.open("w", encoding="utf-8", errors="replace") as log:
            for line in self.process.stderr:
                log.write(line)
                log.flush()
                if "Output #0" in line:
                    self.ready.set()

    def finish(self) -> dict:
        if self.process.poll() is None and self.process.stdin is not None:
            self.process.stdin.write("q\n")
            self.process.stdin.flush()
            self.process.stdin.close()
        try:
            self.process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            stop(self.process)
            raise RuntimeError(f"Video encoder did not stop cleanly for {self.video}")
        self.reader.join(timeout=5)
        if self.process.returncode != 0:
            raise RuntimeError(f"Video encoder failed for {self.video}; inspect {self.ffmpeg_log}")
        if not self.video.is_file():
            raise RuntimeError(f"Video encoder produced no file for {self.case}")

        probe_log = self.video.with_suffix(".ffprobe.log")
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "format=duration:stream=codec_type,codec_name,width,height,nb_frames",
             "-of", "json", str(self.video)],
            text=True, encoding="utf-8", errors="replace", capture_output=True,
        )
        probe_log.write_text(probe.stdout + probe.stderr, encoding="utf-8")
        if probe.returncode != 0:
            raise RuntimeError(f"ffprobe failed for {self.video}; inspect {probe_log}")
        details = json.loads(probe.stdout)
        streams = details.get("streams", [])
        if not any(item.get("codec_type") == "video" and item.get("width") == 1280
                   and item.get("height") == 800 for item in streams):
            raise AssertionError(f"Recording has no 1280x800 client video surface: {details}")
        if any(item.get("codec_type") == "audio" for item in streams):
            raise AssertionError(f"Recording must be silent: {self.video}")
        if float(details.get("format", {}).get("duration", 0)) < 1:
            raise AssertionError(f"Recording is too short to review: {self.video}")
        return {
            "case": self.case,
            "label": self.label,
            "file": self.video.name,
            "sha256": digest(self.video),
            "bytes": self.video.stat().st_size,
            "probe": details,
            "ffmpegLog": self.ffmpeg_log.name,
            "ffprobeLog": probe_log.name,
        }


def add_input(inputs: list[dict], path: Path, role: str, expected: dict | None = None,
              item_id: str | None = None) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"Missing {role} input: {path}")
    size = path.stat().st_size
    checksum = digest(path)
    if expected is not None:
        if "bytes" in expected and int(expected["bytes"]) != size:
            raise AssertionError(f"Input byte count differs from test-runtime manifest: {path}")
        if "sha256" in expected and str(expected["sha256"]).lower() != checksum:
            raise AssertionError(f"Input SHA-256 differs from test-runtime manifest: {path}")
    record = {"role": role, "file": str(path.resolve()), "bytes": size, "sha256": checksum}
    if item_id:
        record["id"] = item_id
    inputs.append(record)
    return record


def audit_runtime(runtime_file: Path, inputs: list[dict]) -> tuple[dict, list[Path]]:
    runtime = json.loads(runtime_file.read_text(encoding="utf-8"))
    if runtime.get("minecraft") != "1.20.1" or runtime.get("loader") != "0.19.5":
        raise AssertionError("test-runtime.json must describe Minecraft 1.20.1 / Fabric Loader 0.19.5")

    production_manifest_path = resolved(runtime["productionManifest"])
    if production_manifest_path != (ROOT / "build" / "production-pack" / "manifest.json").resolve():
        raise AssertionError("test-runtime.json must use build/production-pack/manifest.json")
    production = json.loads(production_manifest_path.read_text(encoding="utf-8"))
    pack_runtime = production.get("runtime", {})
    if (pack_runtime.get("minecraft") != "1.20.1" or pack_runtime.get("loader") != "fabric"
            or pack_runtime.get("loaderVersion") != runtime["loader"]):
        raise AssertionError("Production manifest runtime does not match the returned test-runtime manifest")
    add_input(inputs, production_manifest_path, "production-manifest")
    add_input(inputs, runtime_file, "test-runtime-manifest")

    entries = production.get("mods")
    runtime_mods = runtime.get("mods")
    if not isinstance(entries, list) or not isinstance(runtime_mods, list) or len(entries) != len(runtime_mods):
        raise AssertionError("The returned test-runtime manifest must list every production manifest JAR")
    pack_root = production_manifest_path.parent
    production_paths: list[Path] = []
    for entry, runtime_mod in zip(entries, runtime_mods, strict=True):
        if not isinstance(entry, dict) or not all(key in entry for key in ("file", "bytes", "sha256")):
            raise AssertionError("Production manifest mod entries require file, bytes and sha256")
        manifest_path = resolved(entry["file"], pack_root)
        runtime_path = resolved(runtime_mod)
        if manifest_path != runtime_path:
            raise AssertionError(f"test-runtime production JAR does not match production manifest: {runtime_mod}")
        add_input(inputs, manifest_path, "production-mod", entry, entry.get("id"))
        production_paths.append(manifest_path)

    helper = resolved(runtime["testMod"])
    api = resolved(runtime["gameTestApi"])
    add_input(inputs, helper, "test-helper", item_id="create_maglev_tests")
    add_input(inputs, api, "game-test-api", item_id="fabric-gametest-api-v1")

    fixtures = runtime.get("fixtureOverrides", [])
    if not isinstance(fixtures, list):
        raise AssertionError("test-runtime fixtureOverrides must be a list")
    if not fixtures:
        raise AssertionError("test-runtime.json must include the fixture override inputs")
    fixtures_root = BUILD / "generated" / "create-fixture-overrides"
    for fixture in fixtures:
        if not isinstance(fixture, dict) or not all(key in fixture for key in ("file", "bytes", "sha256")):
            raise AssertionError("Fixture overrides require file, bytes and sha256 metadata")
        fixture_path = resolved(fixture["file"], fixtures_root)
        add_input(inputs, fixture_path, "fixture-override", fixture)

    # The Gradle task emits these for every runtime JAR as a second, explicit
    # inventory. Cross-check it so newly-added runtime inputs cannot go unhashed.
    input_hashes = runtime.get("inputHashes", [])
    if not isinstance(input_hashes, list):
        raise AssertionError("test-runtime inputHashes must be a list")
    seen = {Path(item["file"]).resolve(): item for item in inputs}
    for metadata in input_hashes:
        if not isinstance(metadata, dict) or not all(key in metadata for key in ("file", "bytes", "sha256")):
            raise AssertionError("test-runtime inputHashes entries require file, bytes and sha256")
        input_path = resolved(metadata["file"])
        if input_path in seen:
            existing = seen[input_path]
            if existing["bytes"] != int(metadata["bytes"]) or existing["sha256"] != str(metadata["sha256"]).lower():
                raise AssertionError(f"Conflicting test-runtime hashes for {input_path}")
        else:
            new_record = add_input(inputs, input_path, "runtime-input", metadata, metadata.get("id"))
            seen[input_path] = new_record

    return runtime, production_paths + [helper, api]


def server_properties(server_dir: Path, port: int) -> None:
    eula_file = server_dir / "eula.txt"
    properties_file = server_dir / "server.properties"
    if not eula_file.is_file() or "eula=true" not in eula_file.read_text(encoding="utf-8"):
        raise AssertionError("Dedicated server did not record the explicit test EULA agreement")
    properties = {}
    for line in properties_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    if (properties.get("server-ip") != "127.0.0.1" or properties.get("online-mode") != "false"
            or properties.get("server-port") != str(port)):
        raise AssertionError("Recorded server must be offline and bound to its ephemeral loopback port")


def scene_details(event: dict) -> dict:
    scene = event.get("scene", event)
    if not isinstance(scene, dict):
        raise AssertionError(f"Client READY event has no scene object: {event}")
    event_case = event.get("case") or event.get("clip")
    scene_case = scene.get("case") or scene.get("clip")
    if event_case and scene_case and event_case != scene_case:
        raise AssertionError(f"Client READY event has inconsistent case ids: {event}")
    case = event_case or scene_case
    label = scene.get("label") or event.get("label")
    description = scene.get("description") or event.get("description")
    if not isinstance(case, str) or not isinstance(label, str) or not label.strip():
        raise AssertionError(f"Client READY event must provide a case id and label: {event}")
    if not isinstance(description, str) or not description.strip():
        raise AssertionError(f"Client READY event must provide a scene description: {event}")
    return {"case": case, "label": label.strip(), "description": description.strip(), "scene": scene}


def event_case(event: dict) -> str | None:
    case = event.get("case") or event.get("clip")
    if case is None and isinstance(event.get("result"), dict):
        case = event["result"].get("case")
    return case if isinstance(case, str) else None


def run(args: argparse.Namespace) -> int:
    output = BUILD / "reports" / "recorded"
    profile = BUILD / "run" / "recorded-client"
    control = BUILD / "recording-control"
    server_dir = BUILD / "run" / "recorded-server"
    runtime_file = BUILD / "test-runtime.json"
    server_report_file = BUILD / "reports" / "recorded-results.json"
    safe_reset_directory(output)
    safe_reset_directory(profile)
    safe_reset_directory(control)
    safe_remove_file(server_report_file)

    report = {
        "minecraft": "1.20.1",
        "loader": "fabric-loader-0.19.5",
        "complete": False,
        "passed": False,
        "audio": "intentionally silent; X11 video only, no desktop audio or microphone",
        "visualReview": "Encoded-video checks do not replace human review of gameplay behavior.",
        "inputs": [],
        "results": [],
        "videos": [],
        "clientEvents": [],
        "seedHook": None,
    }
    client = server = None
    capture = None
    client_thread = None
    server_thread = None
    event_log = output / "client-events.jsonl"
    event_stream = event_log.open("w", encoding="utf-8")
    error_text = None
    client_complete = None
    successful = False
    video_by_case: dict[str, dict] = {}
    measured: list[dict] = []
    server_ready = threading.Event()
    messages: queue.Queue = queue.Queue()

    try:
        java17_home = Path(os.environ.get("JAVA17_HOME", "")).expanduser().resolve()
        java = java17_home / "bin" / "java"
        if not java.is_file():
            raise RuntimeError(f"Set JAVA17_HOME to a complete Java 17 installation; missing {java}")
        java_info = subprocess.run([str(java), "-version"], text=True, capture_output=True, check=False)
        java_version = re.search(r'version\s+"?(\d+)', java_info.stderr + java_info.stdout)
        if java_info.returncode != 0 or java_version is None or java_version.group(1) != "17":
            raise RuntimeError(f"The recorded client requires Java 17; {java} did not report a Java 17 runtime")
        environment = dict(os.environ)
        environment["JAVA17_HOME"] = str(java17_home)
        environment["LIBGL_ALWAYS_SOFTWARE"] = "true"

        prepare_log = output / "prepare-runtime.log"
        prepare_command = [
            str(ROOT / "build.sh"), ":packageRuntime", ":testing:prepareTestRuntime", "--console=plain",
        ]
        with prepare_log.open("w", encoding="utf-8") as log:
            prepared = subprocess.run(prepare_command, cwd=ROOT, env=environment,
                                      stdout=log, stderr=subprocess.STDOUT, check=False)
        report["prepareRuntimeLog"] = prepare_log.name
        if prepared.returncode != 0:
            raise RuntimeError(f"Runtime preparation failed ({prepared.returncode}); inspect {prepare_log}")
        if not runtime_file.is_file():
            raise FileNotFoundError(f"Runtime preparation did not produce {runtime_file}")
        runtime, client_mods = audit_runtime(runtime_file, report["inputs"])
        report["seedHook"] = runtime.get("seedHook")

        mods_directory = profile / "mods"
        mods_directory.mkdir(parents=True)
        seen_names = set()
        for source in client_mods:
            if source.name in seen_names:
                raise AssertionError(f"Runtime JAR filename collision: {source.name}")
            seen_names.add(source.name)
            destination = mods_directory / source.name
            shutil.copy2(source, destination)
            if destination.stat().st_size != source.stat().st_size or digest(destination) != digest(source):
                raise AssertionError(f"Client runtime copy differs from audited input: {source}")

        options_file = profile / "options.txt"
        options_file.write_text("""fullscreen:false
guiScale:2
renderDistance:5
simulationDistance:5
maxFps:60
pauseOnLostFocus:false
autoJump:false
bobView:false
tutorialStep:none
lang:en_us
soundCategory_master:0.0
""", encoding="utf-8")

        minecraft_home = args.minecraft_home.expanduser().resolve()
        minecraft_home.mkdir(parents=True, exist_ok=True)
        version = f"fabric-loader-{runtime['loader']}-1.20.1"
        if not (minecraft_home / "versions" / version / f"{version}.json").is_file():
            import minecraft_launcher_lib
            if not (minecraft_home / "versions" / "1.20.1" / "1.20.1.json").is_file():
                minecraft_launcher_lib.install.install_minecraft_version("1.20.1", str(minecraft_home))
            minecraft_launcher_lib.fabric.install_fabric(
                "1.20.1", str(minecraft_home), loader_version=runtime["loader"]
            )
        else:
            import minecraft_launcher_lib

        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]

        server_command = [
            str(ROOT / "build.sh"), ":testing:runRecordedServer", "-PtestEula=true",
            f"-PtestPort={port}", "--console=plain",
        ]
        server = subprocess.Popen(
            server_command, cwd=ROOT, env=environment, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
            errors="replace", bufsize=1, start_new_session=True,
        )
        server_thread = threading.Thread(
            target=read_log, args=(server, output / "server.log", None, server_ready), daemon=True
        )
        server_thread.start()
        ready_deadline = time.monotonic() + 300
        while not server_ready.wait(1):
            if server.poll() is not None:
                raise RuntimeError(f"Dedicated server exited before readiness ({server.returncode}); inspect server.log")
            if time.monotonic() >= ready_deadline:
                raise TimeoutError("Dedicated test server did not become ready within five minutes; inspect server.log")
        server_properties(server_dir, port)

        profile_name = "MaglevTests"
        options = {
            "username": profile_name,
            "uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, f"OfflinePlayer:{profile_name}")),
            "token": "0",
            "executablePath": str(java),
            "gameDirectory": str(profile),
            "customResolution": True,
            "resolutionWidth": "1280",
            "resolutionHeight": "800",
            "jvmArguments": [
                "-Xms512M", "-Xmx3G", "-Djava.awt.headless=false",
                "-Dcreatemaglev.tests=true", "-Dcreatemaglev.tests.recorded=true",
                "-Dcreatemaglev.tests.controlDir=" + str(control.resolve()),
            ],
            "quickPlayMultiplayer": f"127.0.0.1:{port}",
        }
        command = minecraft_launcher_lib.command.get_minecraft_command(version, str(minecraft_home), options)
        client = subprocess.Popen(
            command, cwd=profile, env=environment, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
            errors="replace", bufsize=1, start_new_session=True,
        )
        client_thread = threading.Thread(
            target=read_log, args=(client, output / "client.log", messages, None), daemon=True
        )
        client_thread.start()

        started = time.monotonic()
        next_case = 0
        active_scene = None
        active_result = None
        while client_complete is None:
            remaining = args.timeout - (time.monotonic() - started)
            if remaining <= 0:
                raise TimeoutError(f"Recorded regression suite exceeded {args.timeout:g} seconds")
            try:
                kind, event = messages.get(timeout=min(remaining, 5))
            except queue.Empty:
                if client.poll() is not None:
                    raise RuntimeError(f"Recorded client exited before completion ({client.returncode})")
                continue
            if kind == "process_exit":
                raise RuntimeError(f"Recorded client exited before completion ({event})")
            if kind == "reader_error":
                raise RuntimeError(f"Could not parse client event: {event}")
            report["clientEvents"].append({"marker": kind, "event": event})
            event_stream.write(json.dumps({"marker": kind, "event": event}, sort_keys=True) + "\n")
            event_stream.flush()

            if kind == "MAGLEV_TEST_CLIENT_READY":
                if active_scene is not None or capture is not None:
                    raise AssertionError("Client requested overlapping recordings")
                scene = scene_details(event)
                if next_case >= len(CASES) or scene["case"] != CASES[next_case]:
                    expected = CASES[next_case] if next_case < len(CASES) else "no further scene"
                    raise AssertionError(f"Unexpected scene order: got {scene['case']}, expected {expected}")
                print(f"RECORDING {scene['case']}: {scene['label']}: {scene['description']}", flush=True)
                capture = Capture(output / f"{scene['case']}.mp4", scene["case"], scene["label"])
                active_scene = scene
                active_result = None
                next_case += 1
                (control / f"{scene['case']}.start").touch()
            elif kind == "MAGLEV_TEST_CLIENT_RESULT":
                result = event.get("result", event)
                if not isinstance(result, dict) or active_scene is None:
                    raise AssertionError(f"Result arrived without an active scene: {event}")
                if event_case(result) != active_scene["case"]:
                    raise AssertionError(f"Result case does not match active scene {active_scene['case']}: {event}")
                if active_result is not None:
                    raise AssertionError(f"Duplicate client result for {active_scene['case']}")
                if not isinstance(result.get("passed"), bool) or "evidence" not in result:
                    raise AssertionError(f"Result must include passed and evidence fields: {result}")
                measured.append(result)
                active_result = result
                print(("PASS " if result["passed"] else "FAIL ") + result["case"], flush=True)
            elif kind == "MAGLEV_TEST_RECORDING_DONE":
                if active_scene is None or capture is None or active_result is None:
                    raise AssertionError(f"Recording completed without a checked active scene: {event}")
                case = event_case(event)
                if case != active_scene["case"]:
                    raise AssertionError(f"Recorder completion label differs from active scene: {event}")
                if case in video_by_case:
                    raise AssertionError(f"Duplicate recording for {case}")
                video = capture.finish()
                video_by_case[case] = video
                report["videos"].append(video)
                capture = None
                (control / f"{case}.advance").touch()
                active_scene = None
                active_result = None
            elif kind == "MAGLEV_TEST_CLIENT_COMPLETE":
                if active_scene is not None or capture is not None:
                    raise AssertionError("Client completed while a scene recording was still active")
                if next_case != len(CASES) or len(measured) != len(CASES) or len(video_by_case) != len(CASES):
                    raise AssertionError("Client completed before all required cases were recorded")
                if not isinstance(event.get("passed"), bool) or event.get("count") != len(CASES):
                    raise AssertionError(f"Client completion must report {len(CASES)} completed cases and a pass status: {event}")
                client_complete = event
            elif kind == "MAGLEV_TEST_CLIENT_FATAL":
                raise RuntimeError(f"Recorded client reported a fatal error: {event}")

        client.wait(timeout=60)
        server.wait(timeout=60)
        report["clientReturnCode"] = client.returncode
        report["serverReturnCode"] = server.returncode
        if client.returncode != 0 or server.returncode != 0:
            raise AssertionError(f"Runtime process failure: client={client.returncode}, server={server.returncode}")

        if not server_report_file.is_file():
            raise FileNotFoundError(f"Recorded server did not write {server_report_file}")
        server_report = json.loads(server_report_file.read_text(encoding="utf-8"))
        report["serverReport"] = server_report
        if not server_report.get("complete") or server_report.get("results") != measured:
            raise AssertionError("Persisted native server results differ from client-observed result events")
        observed_cases = [result.get("case") for result in measured]
        if observed_cases != list(CASES):
            raise AssertionError(f"Observed results do not match the required cases in order: {observed_cases}")
        if set(video_by_case) != set(CASES) or len(report["videos"]) != len(CASES):
            raise AssertionError("Exactly one labeled MP4 is required for every declared case")
        successful = client_complete["passed"] and all(result["passed"] for result in measured)
        report["complete"] = True
        report["clientComplete"] = client_complete

    except Exception as error:
        error_text = f"{type(error).__name__}: {error}"
        print(f"ERROR {error_text}", file=sys.stderr, flush=True)
    finally:
        if capture is not None:
            stop(capture.process)
        stop(client)
        stop(server)
        event_stream.close()
        for thread in (client_thread, server_thread):
            if thread is not None:
                thread.join(timeout=5)
        report["results"] = measured
        if client_complete is not None:
            report["clientComplete"] = client_complete
        report["passed"] = successful
        if error_text:
            report["error"] = error_text
        report["clientLog"] = "client.log"
        report["serverLog"] = "server.log"
        report["clientEventsLog"] = event_log.name
        report["serverReportFile"] = str(server_report_file.resolve())
        report["ffmpegLogs"] = sorted(path.name for path in output.glob("*.ffmpeg.log"))
        report["ffprobeLogs"] = sorted(path.name for path in output.glob("*.ffprobe.log"))
        if client is not None:
            report.setdefault("clientReturnCode", client.returncode)
        if server is not None:
            report.setdefault("serverReturnCode", server.returncode)
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Recorded run report and logs: {output}", flush=True)

    return 0 if successful else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true",
                        help="Explicitly agree to the Minecraft EULA for the disposable private test world")
    parser.add_argument("--minecraft-home", type=Path,
                        default=ROOT / "work" / "client-cache",
                        help="Project-owned official/Fabric installation cache; gameplay data uses testing/build/run")
    parser.add_argument("--timeout", type=float, default=1200,
                        help="Maximum recorded-suite duration in seconds (default: 1200)")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Pass --accept-eula only after explicitly agreeing to https://aka.ms/MinecraftEULA")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    for executable in ("ffmpeg", "ffprobe", "xvfb-run", "xauth"):
        if shutil.which(executable) is None:
            parser.error(f"Missing recording prerequisite: {executable}")
    if os.environ.get("MAGLEV_RECORDED_XVFB") != "1":
        environment = dict(os.environ, MAGLEV_RECORDED_XVFB="1")
        return subprocess.run([
            "xvfb-run", "--auto-servernum", "--server-args=-screen 0 1280x800x24 -nolisten tcp",
            sys.executable, str(Path(__file__).resolve()), *sys.argv[1:],
        ], cwd=ROOT, env=environment, check=False).returncode
    if not os.environ.get("DISPLAY"):
        parser.error("The runner must execute under its private Xvfb display")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
