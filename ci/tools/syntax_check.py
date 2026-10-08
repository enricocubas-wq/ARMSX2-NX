#!/usr/bin/env python3
"""Local syntax check of Switch sources without the devkitA64 compiler.

Takes the exact compile command of each file from the factory's compile_commands.json
(branch ci-logs), swaps GCC for the host clang targeting aarch64-none-elf, points the
include paths at the headers published by the "Devkit headers" workflow, and runs
clang++ -fsyntax-only. It catches most compile errors in seconds instead of a 13-minute
factory build. It is not a full substitute: clang is not GCC, so a clean pass here can
still fail there (and vice versa, rarely).

Usage:
  ci/tools/syntax_check.py --devkit DIR --commands compile_commands.json FILE [FILE...]

DIR is the extracted devkit-headers.tar.xz (it contains devkitA64/, libnx/, portlibs/).
"""

import argparse
import json
import os
import shlex
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CI_REPO = "/__w/ARMSX2-NX/ARMSX2-NX"
CI_DEVKITPRO = "/opt/devkitpro"
GCC_VERSION = "15.2.0"

DROP_FLAGS = {"-mtp=soft", "-pipe", "-pthread", "-c"}


def system_includes(devkit):
    # GCC's own intrinsic headers (arm_neon.h, arm_acle.h...) use GCC-only builtins, so clang's
    # resource headers take their place; libstdc++ and newlib come from the devkit.
    base = os.path.join(devkit, "devkitA64")
    cxx = os.path.join(base, "aarch64-none-elf", "include", "c++", GCC_VERSION)
    resource = subprocess.run(["clang++", "-print-resource-dir"], capture_output=True, text=True).stdout.strip()
    return [
        cxx,
        os.path.join(cxx, "aarch64-none-elf"),
        os.path.join(cxx, "backward"),
        os.path.join(resource, "include"),
        os.path.join(base, "aarch64-none-elf", "include"),
    ]


def translate(command, devkit):
    args = shlex.split(command)[1:]  # drop the compiler
    out = []
    skip_next = False
    for arg in args:
        if skip_next:
            skip_next = False
            continue
        if arg == "-o":
            skip_next = True
            continue
        if arg in DROP_FLAGS:
            continue
        if arg == "-flax-vector-conversions":
            arg = "-flax-vector-conversions=all"
        arg = arg.replace(CI_REPO, REPO).replace(CI_DEVKITPRO, devkit)
        out.append(arg)

    cmd = ["clang++", "--target=aarch64-none-elf", "-fsyntax-only", "-nostdinc", "-nostdinc++",
           "-D__DEVKITA64__=1", "-D__DEVKITPRO__=1", "-Wno-unknown-warning-option",
           "-Wno-unused-command-line-argument", "-Wno-gnu-line-marker", "-ferror-limit=50",
           # newlib's malloc.h declares calloc & co. without the throw() its stdlib.h uses; GCC
           # lets that pass in system headers, clang only if stdlib.h comes first.
           "-include", "stdlib.h"]
    for inc in system_includes(devkit):
        if os.path.isdir(inc):
            cmd += ["-isystem", inc]
    return cmd + out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--devkit", required=True)
    parser.add_argument("--commands", required=True)
    parser.add_argument("files", nargs="+")
    opts = parser.parse_args()

    with open(opts.commands) as f:
        entries = json.load(f)

    failed = 0
    for path in opts.files:
        rel = os.path.relpath(os.path.abspath(path), REPO)
        entry = next((e for e in entries if e["file"].replace(CI_REPO + "/", "") == rel), None)
        if entry is None:
            # New file: borrow the command of a neighbour in the same directory.
            folder = os.path.dirname(rel)
            entry = next((e for e in entries if os.path.dirname(e["file"].replace(CI_REPO + "/", "")) == folder), None)
            if entry is None:
                print(f"== {rel}: no compile command found", flush=True)
                failed += 1
                continue
            entry = dict(entry)
            entry["command"] = entry["command"].replace(entry["file"], os.path.join(CI_REPO, rel))
        cmd = translate(entry["command"], os.path.abspath(opts.devkit))
        cwd = entry["directory"].replace(CI_REPO, REPO)
        if not os.path.isdir(cwd):
            cwd = REPO
        result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
        status = "ok" if result.returncode == 0 else "FAILED"
        print(f"== {rel}: {status}", flush=True)
        if result.stderr.strip():
            print(result.stderr.rstrip(), flush=True)
        failed += result.returncode != 0
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
