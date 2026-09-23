#!/usr/bin/env python3
"""Boots a Fabric server for each Minecraft version with the jar from build/libs and checks
in-game that happy ghasts are actually protected.

    python smoketest/smoketest.py                     # every release since 1.21.6
    python smoketest/smoketest.py 1.21.8 26.3         # only these
    python smoketest/smoketest.py 26.4 --try-anyway   # try the closest jar on an unsupported version
    python smoketest/smoketest.py --loader min        # each jar's oldest allowed Fabric Loader

Needs Java 21 and 25. Accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for its test
servers, which are cached in your temp folder (--work-dir to change that).
"""

import argparse
import concurrent.futures
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORK_DIR = Path(tempfile.gettempdir()) / "fireproofghasts-smoketest"
FIRST_VERSION = "1.21.6"  # first version with happy ghasts
MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_META = "https://meta.fabricmc.net/v2"
FIRE_DAMAGE_TYPES = ["minecraft:lava", "minecraft:in_fire", "minecraft:on_fire", "minecraft:campfire",
                     "minecraft:hot_floor", "minecraft:fireball", "minecraft:unattributed_fireball"]
print_lock = threading.Lock()


def log(message):
    with print_lock:
        print(message, flush=True)


def parse_version(text):
    # snapshots and pre-releases sort just before their release
    base, _, pre = text.partition("-")
    numbers = tuple(int(part) for part in base.split("."))
    return numbers + (0,) * (3 - len(numbers)), (0 if pre else 1)


def in_range(version, version_range):
    v = parse_version(version)
    for predicate in version_range.split():
        op, target = re.fullmatch(r"(>=|<=|>|<|=)?(.+)", predicate).groups()
        t = parse_version(target)
        if not {">=": v >= t, "<=": v <= t, ">": v > t, "<": v < t}.get(op, v == t):
            return False
    return True


def read_properties(path):
    props = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def load_jar_groups():
    root = read_properties(ROOT / "gradle.properties")
    groups = []
    for props_file in sorted((ROOT / "versions").glob("*/gradle.properties")):
        props = read_properties(props_file)
        jar_name = f"{root['archives_name']}-{root['mod_version']}+mc{props['minecraft_label']}.jar"
        groups.append({
            "name": props_file.parent.name,
            "compiled_for": props["minecraft_version"],
            "range": props["minecraft_range"],
            "loader_min": props["loader_min"],
            "jar": ROOT / "build" / "libs" / jar_name,
        })
    return root, groups


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(url, timeout=300) as response, open(temp, "wb") as out:
        shutil.copyfileobj(response, out)
    temp.replace(path)


def patched_jar(jar, destination):
    with zipfile.ZipFile(jar) as source, zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as target:
        for item in source.infolist():
            data = source.read(item.filename)
            if item.filename == "fabric.mod.json":
                mod = json.loads(data)
                mod["depends"]["minecraft"] = "*"
                data = json.dumps(mod, indent=2).encode()
            target.writestr(item, data)
    return destination


def java_major(home):
    release = Path(home) / "release"
    if release.exists():
        match = re.search(r'JAVA_VERSION="(\d+)', release.read_text(errors="replace"))
        if match:
            return int(match.group(1))
    return None


def find_java(major):
    exe ="java.exe" if os.name == "nt" else "java"
    homes = [os.environ.get(f"JAVA{major}_HOME"), os.environ.get("JAVA_HOME")]
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    patterns = [gradle_home / "jdks" / "*", Path.home() / ".jdks" / "*"]
    if os.name == "nt":
        patterns += [Path(p) / "*" for p in (r"C:\Program Files\Java", r"C:\Program Files\Eclipse Adoptium",
                                              r"C:\Program Files\Microsoft", r"C:\Program Files\Zulu")]
    else:
        patterns += [Path("/usr/lib/jvm/*"), Path("/Library/Java/JavaVirtualMachines/*/Contents/Home")]
    for pattern in patterns:
        homes += [str(p) for p in sorted(Path(pattern.anchor).glob(str(pattern.relative_to(pattern.anchor))))]
    for home in filter(None, homes):
        if java_major(home) == major and (Path(home) / "bin" / exe).exists():
            return str(Path(home) / "bin" / exe)
    path_java = shutil.which("java")
    if path_java:
        result = subprocess.run([path_java, "-version"], capture_output=True, text=True)
        match = re.search(r'version "(\d+)', result.stderr)
        if match and int(match.group(1)) == major:
            return path_java
    return None


class ServerDied(Exception):
    pass


class Server:
    def __init__(self, directory, java, launcher):
        self.directory = directory
        self.lines = []
        self.done = False
        self.marker = 0
        self.changed = threading.Condition()
        self.log_file = open(directory / "smoketest.log", "w", encoding="utf-8")
        self.process = subprocess.Popen(
            [java, "-Xmx1536M", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", launcher.name, "nogui"],
            cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        threading.Thread(target=self._read_output, daemon=True).start()

    def _read_output(self):
        for raw in self.process.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            with self.changed:
                self.lines.append(line)
                self.log_file.write(line + "\n")
                self.changed.notify_all()
        with self.changed:
            self.done = True
            self.changed.notify_all()

    def wait_for(self, pattern, timeout, start=0):
        regex =re.compile(pattern)
        deadline = time.time() + timeout
        index = start
        with self.changed:
            while True:
                while index < len(self.lines):
                    if regex.search(self.lines[index]):
                        return index
                    index += 1
                if self.done:
                    raise ServerDied("the server stopped unexpectedly")
                if time.time() >= deadline:
                    raise TimeoutError(f"no output matching {pattern!r} after {timeout}s")
                self.changed.wait(deadline - time.time())

    def send(self, command):
        self.process.stdin.write((command + "\n").encode("utf-8"))
        self.process.stdin.flush()

    def run(self, command, timeout=30):
        self.marker += 1
        marker = f"smoketest-marker-{self.marker}"
        with self.changed:
            start = len(self.lines)
        self.send(command)
        self.send("say " + marker)  # commands run in order, so the marker lands right after the output
        end = self.wait_for(re.escape(marker), timeout, start)
        return "\n".join(self.lines[start:end])

    def stop(self):
        try:
            if self.process.poll() is None:
                self.send("stop")
                self.process.wait(timeout=90)
        except Exception:
            self.process.kill()
        finally:
            self.log_file.close()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def prepare_server(directory, mc_version, loader, jar):
    for leftover in ("world", "logs", "config", "mods"):
        shutil.rmtree(directory / leftover, ignore_errors=True)
    (directory / "mods").mkdir(parents=True)
    shutil.copy2(jar, directory / "mods" / jar.name)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text("\n".join([
        "online-mode=false", f"server-port={free_port()}", r"level-type=minecraft\:flat", "difficulty=peaceful",
        "spawn-protection=0", "view-distance=4", "simulation-distance=4", "generate-structures=false",
        "sync-chunk-writes=false", "motd=Fireproof Happy Ghasts smoke test", ""]))
    launcher = directory / f"fabric-server-{loader}.jar"
    if not launcher.exists():
        installer = next(i["version"] for i in fetch_json(f"{FABRIC_META}/versions/installer") if i["stable"])
        download(f"{FABRIC_META}/versions/loader/{mc_version}/{loader}/{installer}/server/jar", launcher)
    return launcher


class Checks:
    def __init__(self, server):
        self.server = server
        self.results = []
        self.ghasts = 0

    def check(self, name, passed, detail=""):
        self.results.append((name, bool(passed), " ".join(detail.split())[-300:]))

    def run(self, command):
        return self.server.run(command)

    def setting(self, key, value):
        output = self.run(f"fireproofghasts set {key} {value}")
        self.check(f"/fireproofghasts set {key} {value}", f"{key} is now" in output, output)

    def number(self, selector, path):
        output =self.run(f"data get entity {selector} {path}")
        match = re.search(r"has the following entity data: (-?[\d.]+)", output)
        return float(match.group(1)) if match else None

    def ghast(self, nbt="", rider=False):
        self.ghasts += 1
        tag = f"g{self.ghasts}"
        x = -40 + (self.ghasts % 20) * 5
        passengers = f',Passengers:[{{id:"minecraft:pig",NoAI:1b,Tags:["{tag}r"]}}]' if rider else ""
        self.run(f'summon minecraft:happy_ghast {x} -60 40 {{NoAI:1b,Tags:["{tag}"]{nbt}{passengers}}}')
        return f"@e[tag={tag},limit=1]", f"@e[tag={tag}r,limit=1]"

    # a fresh ghast every time, since mobs ignore repeat hits for half a second
    def damage(self, name, damage_type, expected, nbt="", target="ghast"):
        ghast, rider =self.ghast(nbt, rider=target != "ghast")
        victim = rider if target == "rider" else ghast
        output = self.run(f"damage {victim} 1 {damage_type}")
        result = "applied" if re.search(r"Applied [\d.]+ damage", output) else "blocked" if "invulnerable" in output else "error"
        self.check(f"{name}: {damage_type} on {target} is {expected}", result == expected, output)
        self.run(f"kill {ghast}")
        if target != "ghast":
            self.run(f"kill {rider}")

    def lava_pool(self, x, tag, nbt=""):
        self.run(f"fill {x} -50 0 {x + 7} -43 7 minecraft:barrier hollow")
        self.run(f"fill {x + 1} -49 1 {x + 6} -44 6 minecraft:lava")
        self.run(f'summon minecraft:happy_ghast {x + 4} -48 4 {{Tags:["{tag}"]{nbt}}}')
        return f"@e[tag={tag},limit=1]"


def run_checks(server, config_file):
    c = Checks(server)
    output = c.run("fireproofghasts")
    c.check("/fireproofghasts lists the settings", "lava: true" in output and "extra_damage_types: none" in output, output)
    c.run("forceload add -48 -16 64 48")

    # with the mod off the ghast has to get hurt, otherwise the checks below prove nothing
    c.setting("enabled", "false")
    in_lava = c.lava_pool(0, "lava_off")
    burning, _ = c.ghast()
    c.run(f"data merge entity {burning} {{Fire:200s}}")
    c.damage("mod off", "minecraft:in_fire", "applied")
    time.sleep(5)
    health = c.number(in_lava, "Health")
    c.check("mod off: happy ghast in lava gets hurt", health is None or health < 20, f"health={health}")
    fire = c.number(burning, "Fire")
    c.check("mod off: happy ghast set on fire keeps burning", fire is not None and fire > 0, f"Fire={fire}")

    c.setting("enabled", "true")
    adult = c.lava_pool(20, "lava_adult")
    baby = c.lava_pool(40, "lava_baby", ",Age:-24000")
    burning, _ = c.ghast()
    c.run(f"data merge entity {burning} {{Fire:200s}}")
    for damage_type in FIRE_DAMAGE_TYPES:
        c.damage("default", damage_type, "blocked")
    c.damage("default", "minecraft:generic", "applied")
    c.damage("default", "minecraft:cactus", "applied")
    c.damage("default ghastling", "minecraft:lava", "blocked", ",Age:-24000")
    time.sleep(5)
    for name, ghast in (("adult", adult), ("ghastling", baby)):
        health = c.number(ghast, "Health")
        c.check(f"default: {name} in lava keeps full health", health == 20, f"health={health}")
        fire = c.number(ghast, "Fire")
        c.check(f"default: {name} in lava doesn't catch fire", fire is not None and fire <= 0, f"Fire={fire}")
    fire = c.number(burning, "Fire")
    c.check("default: a burning happy ghast goes out at once", fire is not None and fire <= 0, f"Fire={fire}")

    c.setting("lava", "false")
    c.damage("lava=false", "minecraft:lava", "applied")
    c.damage("lava=false", "minecraft:in_fire", "blocked")
    c.setting("lava", "true")
    c.setting("fire", "false")
    c.damage("fire=false", "minecraft:in_fire", "applied")
    c.damage("fire=false", "minecraft:hot_floor", "applied")
    c.damage("fire=false", "minecraft:lava", "blocked")
    c.setting("fire", "true")
    c.setting("burning", "false")
    c.damage("burning=false", "minecraft:on_fire", "applied")
    c.damage("burning=false", "minecraft:lava", "blocked")
    c.setting("burning", "true")
    c.setting("protect_ghastlings", "false")
    c.damage("protect_ghastlings=false", "minecraft:lava", "applied", ",Age:-24000")
    c.damage("protect_ghastlings=false (adult)", "minecraft:lava", "blocked")
    c.setting("protect_ghastlings", "true")
    c.setting("protect_adults", "false")
    c.damage("protect_adults=false", "minecraft:lava", "applied")
    c.damage("protect_adults=false (ghastling)", "minecraft:lava", "blocked", ",Age:-24000")
    c.setting("protect_adults", "true")
    harness = ',equipment:{body:{id:"minecraft:white_harness",count:1}}'
    c.setting("require_harness", "true")
    c.damage("require_harness, no harness", "minecraft:lava", "applied")
    c.damage("require_harness, harness", "minecraft:lava", "blocked", harness)
    c.setting("require_harness", "false")
    c.setting("require_rider", "true")
    c.damage("require_rider, no rider", "minecraft:lava", "applied")
    c.damage("require_rider, ridden", "minecraft:lava", "blocked", target="ghast with rider")
    c.setting("require_rider", "false")
    c.damage("protect_riders=false", "minecraft:lava", "applied", target="rider")
    c.setting("protect_riders", "true")
    c.damage("protect_riders=true", "minecraft:lava", "blocked", target="rider")
    c.damage("protect_riders=true", "minecraft:generic", "applied", target="rider")
    c.setting("protect_riders", "false")

    c.setting("extra_damage_types", "#is_explosion, cactus")
    c.damage("extra types", "minecraft:explosion", "blocked")
    c.damage("extra types", "minecraft:cactus", "blocked")
    c.damage("extra types", "minecraft:generic", "applied")
    output = c.run("fireproofghasts set extra_damage_types minecraft:not_a_real_type")
    c.check("extra types: a typo is pointed out", "Unknown damage type 'minecraft:not_a_real_type'" in output, output)
    c.setting("extra_damage_types", "none")

    text =config_file.read_text(encoding="utf-8") if config_file.exists() else ""
    c.check("config file was written", "lava=true" in text and "extra_damage_types=" in text, text[:200])
    config_file.write_text(text.replace("lava=true", "lava=false").replace("fire=true", "fire=maybe") + "bogus=1\n",
                           encoding="utf-8")
    output = c.run("fireproofghasts reload")
    c.check("reload reports a bad value", "fire=maybe should be true or false" in output, output)
    c.check("reload reports an unknown setting", "Unknown setting 'bogus'" in output, output)
    c.damage("after reload (lava=false)", "minecraft:lava", "applied")
    c.damage("after reload (fire=maybe -> default)", "minecraft:in_fire", "blocked")
    config_file.unlink()
    output = c.run("fireproofghasts reload")
    c.check("reload recreates a deleted config file", config_file.exists() and "reloaded" in output, output)
    c.damage("after recreating the config", "minecraft:lava", "blocked")
    return c.results


def test_version(mc_version, groups, args, default_loader):
    group =next((g for g in groups if in_range(mc_version, g["range"])), None)
    forced = False
    if group is None:
        if not args.try_anyway:
            return "NO JAR", "no jar claims this version (use --try-anyway to test the closest one)"
        older = [g for g in groups if parse_version(g["compiled_for"]) <= parse_version(mc_version)]
        if not older:
            return "NO JAR", "older than every jar"
        group, forced = max(older, key=lambda g: parse_version(g["compiled_for"])), True
    if not group["jar"].exists():
        return "FAIL", f"{group['jar'].name} not found; run ./gradlew build first"

    directory = args.work_dir / mc_version
    directory.mkdir(parents=True, exist_ok=True)
    jar = patched_jar(group["jar"], directory / group["jar"].name) if forced else group["jar"]
    loader = group["loader_min"] if args.loader == "min" else (args.loader or default_loader)
    java_needed = fetch_json(args.manifest[mc_version])["javaVersion"]["majorVersion"]
    java = args.java.get(java_needed) or find_java(java_needed)
    if not java:
        return "FAIL", f"Java {java_needed} not found (pass --java {java_needed}=PATH)"

    label = f"{group['name']} jar{' (forced)' if forced else ''}, loader {loader}, Java {java_needed}"
    log(f"[{mc_version}] starting server ({label})")
    launcher = prepare_server(directory, mc_version, loader, jar)
    if forced:
        (directory / "mods" / group["jar"].name).unlink()
        shutil.copy2(jar, directory / "mods" / jar.name)
    server = Server(directory, java, launcher)
    try:
        server.wait_for(r"Done \(", timeout=900)
        with server.changed:
            output = "\n".join(server.lines)
        if "Loaded settings from config/fireproofghasts.properties" not in output:
            return "FAIL", f"{label}: the mod didn't start (see {directory / 'smoketest.log'})"
        results = run_checks(server, directory / "config" / "fireproofghasts.properties")
    except (TimeoutError, ServerDied) as error:
        with server.changed:
            causes = [line.strip() for line in server.lines if line.lstrip().startswith("Caused by: ")]
        cause = f"\n      {causes[-1]}" if causes else ""
        return "FAIL", f"{label}: {error} (see {directory / 'smoketest.log'}){cause}"
    finally:
        server.stop()
    with server.changed:
        problems = [line for line in server.lines
                    if "fireproofghasts" in line.lower() and re.search(r"(?i)exception|mixin.*(error|fail)", line)]
    failed = [f"{name} -> {detail}" for name, passed, detail in results if not passed]
    failed += [f"log: {line}" for line in problems]
    status = "PASS" if not failed else "FAIL"
    summary = f"{label}: {sum(p for _, p, _ in results)}/{len(results)} checks passed"
    return status, "\n      ".join([summary] + failed)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("versions", nargs="*", help=f"Minecraft versions to test (default: every release since {FIRST_VERSION})")
    parser.add_argument("--loader", help="Fabric Loader version, or 'min' for each jar's oldest allowed one")
    parser.add_argument("--try-anyway", action="store_true", help="test versions no jar supports yet with the closest jar")
    parser.add_argument("--parallel", type=int, default=3, help="servers to run at the same time (default 3)")
    parser.add_argument("--java", action="append", default=[], metavar="MAJOR=PATH", help="java executable to use, e.g. 25=C:/jdk-25/bin/java.exe")
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK_DIR, help=f"where to keep the test servers (default {DEFAULT_WORK_DIR})")
    args = parser.parse_args()
    args.java = {int(k): v for k, v in (item.split("=", 1) for item in args.java)}
    log(f"Test servers are kept in {args.work_dir}")

    root, groups = load_jar_groups()
    manifest = fetch_json(MOJANG_MANIFEST)["versions"]
    args.manifest = {v["id"]: v["url"] for v in manifest}
    versions = args.versions
    if not versions:
        releases = [v["id"] for v in manifest if v["type"] == "release"]
        versions = list(reversed(releases[:releases.index(FIRST_VERSION) + 1]))
    unknown = [v for v in versions if v not in args.manifest]
    if unknown:
        sys.exit(f"Unknown Minecraft version(s): {', '.join(unknown)}")

    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures = {pool.submit(test_version, v, groups, args, root["loader_version"]): v for v in versions}
        for future in concurrent.futures.as_completed(futures):
            version = futures[future]
            try:
                results[version] = future.result()
            except Exception as error:
                results[version] = ("FAIL", f"{type(error).__name__}: {error}")
            log(f"[{version}] {results[version][0]}")

    print("\nResults:")
    for version in versions:
        status, details = results[version]
        print(f"  {version:<16} {status:<7} {details}")
    sys.exit(1 if any(status == "FAIL" for status, _ in results.values()) else 0)


if __name__ == "__main__":
    main()
