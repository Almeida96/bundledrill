"""Cross-check the packaged CLI using plain Git and the warehouse fixture."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args, expect=0):
    proc = subprocess.run(args, capture_output=True, text=True, timeout=90)
    if proc.returncode != expect:
        raise RuntimeError(f"{args[0]} returned {proc.returncode}: {proc.stderr}")
    return proc.stdout.strip()


def main(install, demo):
    exe = "java.exe" if os.name == "nt" else "java"
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / exe) if "JAVA_HOME" in os.environ else exe
    cli = (java, "-cp", str(install / "lib" / "*"), "io.github.almeida96.bundledrill.MainKt")
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        oracle = root / "oracle.git"
        run("git", "clone", "--bare", str(demo / "base.bundle"), str(oracle))
        # Ask ordinary Git to fetch both independent histories, without BundleDrill's importer.
        for bundle, branch in [("inventory-day2.bundle", "main"), ("handbook.bundle", "handbook")]:
            ref = "refs/heads/" + branch
            run("git", "-C", str(oracle), "fetch", str(demo / bundle), f"+{ref}:{ref}")
        run(*cli, "drill", "--plan", str(demo / "complete.json"), "--out", str(root / "kept"), "--keep-repo")
        actual = root / "kept" / "recovered.git"
        for branch, filename in [("main", "inventory.csv"), ("handbook", "handbook.md")]:
            ref = "refs/heads/" + branch
            for args in [("rev-parse", ref), ("rev-parse", ref + "^{tree}"), ("show", ref + ":" + filename)]:
                assert run("git", "-C", str(actual), *args) == run("git", "-C", str(oracle), *args)
        broken = root / "lost"
        run(*cli, "drill", "--plan", str(demo / "broken.json"), "--out", str(broken), expect=1)
        report = json.loads((broken / "report.json").read_bytes())
        assert {p["ref"]: p["state"] for p in report["points"]} == {"refs/heads/main":"MISSING","refs/heads/handbook":"RECOVERED"}
        print("Independent Git clone/fetch matched both commits, trees and file contents. Missing base produced exit 1.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", required=True, type=Path)
    parser.add_argument("--demo", required=True, type=Path)
    args = parser.parse_args()
    main(args.install_dir.resolve(), args.demo.resolve())
