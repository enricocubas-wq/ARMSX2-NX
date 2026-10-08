#!/usr/bin/env python3
"""Turns the "[PROF]" lines of an emulog.txt into a readable profile.

The Switch build logs native code as offsets into the module text (the NRO has no symbols).
This resolves them against the ELF of the same build (attached to each factory release as
armsx2nx.elf.xz) with llvm-symbolizer, groups the samples by function, and disassembles the
guest (PS2) code dumps with llvm-mc.

Usage:
  ci/tools/prof_report.py --log emulog.txt --elf armsx2nx.elf [--from SECONDS] [--to SECONDS]
                          [--windows] [--top N]

Without --windows only the sum over the selected time range is printed.
"""

import argparse
import collections
import json
import re
import subprocess
import sys

LINE_RE = re.compile(r"^\[\s*([0-9.]+)\]\s+(.*)$")
HEADER_RE = re.compile(r"^\[PROF\] (EE|GS|VU): (\d+) samples in ([0-9.]+)s \(missed (\d+)\) \|(.*)$")
NATIVE_RE = re.compile(r"^\[PROF\] (EE|GS|VU) native:(.*)$")
STACK_RE = re.compile(r"^\[PROF\] (EE|GS|VU) stack (\d+): (.*)$")
GUEST_RE = re.compile(r"^\[PROF\] EE guest:(.*)$")
CODE_RE = re.compile(r"^\[PROF\] EE code ([0-9a-f]{8}):(.*)$")
PERF_RE = re.compile(r"^\[PERF\] speed (\d+)% .*?fps ([0-9.]+) limiter (\S+)")
ANCHOR_RE = re.compile(r"anchor at \+(0x[0-9a-f]+)")

SVC_NAMES = {
    0x01: "SetHeapSize", 0x02: "SetMemoryPermission", 0x03: "SetMemoryAttribute", 0x04: "MapMemory",
    0x05: "UnmapMemory", 0x06: "QueryMemory", 0x07: "ExitProcess", 0x08: "CreateThread",
    0x09: "StartThread", 0x0A: "ExitThread", 0x0B: "SleepThread", 0x0C: "GetThreadPriority",
    0x0D: "SetThreadPriority", 0x0E: "GetThreadCoreMask", 0x0F: "SetThreadCoreMask",
    0x10: "GetCurrentProcessorNumber", 0x11: "SignalEvent", 0x12: "ClearEvent", 0x13: "MapSharedMemory",
    0x14: "UnmapSharedMemory", 0x15: "CreateTransferMemory", 0x16: "CloseHandle", 0x17: "ResetSignal",
    0x18: "WaitSynchronization", 0x19: "CancelSynchronization", 0x1A: "ArbitrateLock",
    0x1B: "ArbitrateUnlock", 0x1C: "WaitProcessWideKeyAtomic", 0x1D: "SignalProcessWideKey",
    0x1E: "GetSystemTick", 0x1F: "ConnectToNamedPort", 0x21: "SendSyncRequest",
    0x22: "SendSyncRequestWithUserBuffer", 0x24: "GetProcessId", 0x25: "GetThreadId", 0x26: "Break",
    0x27: "OutputDebugString", 0x28: "ReturnFromException", 0x29: "GetInfo", 0x2A: "FlushEntireDataCache",
    0x2B: "FlushDataCache", 0x2C: "MapPhysicalMemory", 0x2D: "UnmapPhysicalMemory", 0x32: "SetThreadActivity",
    0x33: "GetThreadContext3", 0x34: "WaitForAddress", 0x35: "SignalToAddress", 0x36: "SynchronizePreemptionState",
    0x4B: "CreateCodeMemory", 0x4C: "ControlCodeMemory", 0x73: "SetProcessMemoryPermission",
    0x74: "MapProcessMemory", 0x75: "UnmapProcessMemory", 0x77: "MapProcessCodeMemory",
    0x78: "UnmapProcessCodeMemory",
}


class Symbolizer:
    def __init__(self, elf):
        self.elf = elf
        self.cache = {}

    def resolve(self, offsets):
        todo = sorted({o for o in offsets if o not in self.cache})
        if not todo:
            return
        cmd = ["llvm-symbolizer", f"--obj={self.elf}", "--inlining", "--functions=linkage", "--demangle",
               "--output-style=JSON"]
        out = subprocess.run(cmd, input="\n".join(hex(o) for o in todo), capture_output=True, text=True).stdout
        for line in out.splitlines():
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            addr = int(entry["Address"], 16)
            frames = entry.get("Symbol", [])
            self.cache[addr] = frames

    def frames(self, offset):
        return self.cache.get(offset, [])

    def function(self, offset):
        """Outermost frame = the physical function the code lives in."""
        frames = self.frames(offset)
        return short(frames[-1]["FunctionName"]) if frames else f"?{offset:x}"

    def where(self, offset):
        """Innermost frame with file:line, for detail."""
        frames = self.frames(offset)
        if not frames:
            return ""
        f = frames[0]
        name = short(f["FunctionName"])
        file = f.get("FileName", "").split("/app/src/main/cpp/")[-1]
        return f"{name} [{file}:{f.get('Line', 0)}]" if file else name


def short(name, limit=110):
    # Drop argument lists of templates/functions to keep lines readable.
    name = name.replace("(anonymous namespace)::", "")
    depth = 0
    out = []
    for ch in name:
        if ch == "(":
            if depth == 0:
                out.append("()")
            depth += 1
            continue
        if ch == ")":
            depth -= 1
            continue
        if depth == 0:
            out.append(ch)
    text = "".join(out)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def parse_loc(token):
    token = token.strip()
    if token == "?":
        return ("none", None)
    if token.startswith("J:"):
        return ("jit", token[2:])
    if token.startswith("svc:"):
        return ("svc", int(token[4:], 16))
    return ("native", int(token, 16))


def loc_text(sym, loc):
    kind, value = loc
    if kind == "none":
        return "?"
    if kind == "jit":
        return f"[recompiled {value}]"
    if kind == "svc":
        return f"svc {SVC_NAMES.get(value, hex(value))}"
    return sym.function(value)


def parse_log(path):
    windows = []
    current = None
    last_perf = None
    anchor = None
    with open(path, errors="replace") as f:
        for raw in f:
            m = LINE_RE.match(raw.rstrip("\n"))
            if not m:
                continue
            t, msg = float(m.group(1)), m.group(2)
            pm = PERF_RE.match(msg)
            if pm:
                last_perf = (t, int(pm.group(1)), float(pm.group(2)), pm.group(3))
                if current is not None:
                    current["perf"].append(last_perf)
                continue
            if not msg.startswith("[PROF]"):
                continue
            am = ANCHOR_RE.search(msg)
            if am:
                anchor = int(am.group(1), 16)
            hm = HEADER_RE.match(msg)
            if hm:
                thread = hm.group(1)
                if current is None or thread in current["threads"]:
                    current = {"time": t, "threads": {}, "perf": [last_perf] if last_perf else [], "code": {}}
                    windows.append(current)
                areas = {}
                for part in hm.group(5).split():
                    pass
                tokens = hm.group(5).split()
                for i in range(0, len(tokens) - 1, 2):
                    areas[tokens[i]] = float(tokens[i + 1].rstrip("%"))
                current["threads"][thread] = {
                    "samples": int(hm.group(2)), "seconds": float(hm.group(3)), "missed": int(hm.group(4)),
                    "areas": areas, "native": collections.Counter(), "stacks": [], "guest": collections.Counter(),
                }
                continue
            if current is None:
                continue
            nm = NATIVE_RE.match(msg)
            if nm and nm.group(1) in current["threads"]:
                for item in nm.group(2).split():
                    off, count = item.split("=")
                    current["threads"][nm.group(1)]["native"][int(off, 16)] += int(count)
                continue
            sm = STACK_RE.match(msg)
            if sm and sm.group(1) in current["threads"]:
                text = sm.group(3)
                lr = None
                lm = re.search(r"\(lr ([^)]+)\)", text)
                if lm:
                    lr = parse_loc(lm.group(1))
                    text = text.replace(lm.group(0), "")
                parts = [parse_loc(p) for p in text.split("<")]
                current["threads"][sm.group(1)]["stacks"].append((int(sm.group(2)), parts[0], lr, parts[1:]))
                continue
            gm = GUEST_RE.match(msg)
            if gm and "EE" in current["threads"]:
                for item in gm.group(1).split():
                    pc, count = item.split("=")
                    current["threads"]["EE"]["guest"][int(pc, 16)] += int(count)
                continue
            cm = CODE_RE.match(msg)
            if cm:
                current["code"][int(cm.group(1), 16)] = [int(w, 16) for w in cm.group(2).split()]
    return windows, anchor


def disassemble(pc, words):
    data = " ".join(f"0x{b:02x}" for w in words for b in w.to_bytes(4, "little"))
    out = subprocess.run(["llvm-mc", "--disassemble", "-triple=mips64el", "-mcpu=mips3"], input=data,
                         capture_output=True, text=True)
    lines = [l.strip() for l in out.stdout.splitlines() if l.strip() and not l.strip().startswith(".text")]
    result = []
    for i, w in enumerate(words):
        text = lines[i] if i < len(lines) else ""
        result.append(f"    {pc + 4 * i:08x}: {w:08x}  {text}")
    return result


def report(windows, sym, top, label):
    threads = collections.OrderedDict()
    perf = []
    code = {}
    for w in windows:
        perf += [p for p in w["perf"] if p]
        code.update(w["code"])
        for name, t in w["threads"].items():
            agg = threads.setdefault(name, {"samples": 0, "seconds": 0.0, "missed": 0, "areas": collections.Counter(),
                                            "native": collections.Counter(), "stacks": collections.Counter(),
                                            "guest": collections.Counter()})
            agg["samples"] += t["samples"]
            agg["seconds"] += t["seconds"]
            agg["missed"] += t["missed"]
            for a, pct in t["areas"].items():
                agg["areas"][a] += pct * t["samples"] / 100.0
            agg["native"].update(t["native"])
            agg["guest"].update(t["guest"])
            for count, head, lr, chain in t["stacks"]:
                agg["stacks"][(head, lr, tuple(chain))] += count

    offsets = set()
    for agg in threads.values():
        offsets.update(agg["native"].keys())
        for (head, lr, chain) in agg["stacks"]:
            for loc in (head, lr) + chain:
                if loc and loc[0] == "native":
                    offsets.add(loc[1])
    sym.resolve(offsets)

    print(f"===== {label} =====")
    if perf:
        speeds = [p[1] for p in perf]
        print(f"speed in this range: avg {sum(speeds)/len(speeds):.0f}% (min {min(speeds)}%, max {max(speeds)}%) "
              f"over {len(perf)} [PERF] lines; limiters: {collections.Counter(p[3] for p in perf).most_common()}")
    for name, agg in threads.items():
        n = agg["samples"]
        if n == 0:
            continue
        print(f"\n--- {name} thread: {n} samples over {agg['seconds']:.0f}s (missed {agg['missed']})")
        areas = ", ".join(f"{a} {100.0 * c / n:.1f}%" for a, c in agg["areas"].most_common())
        print(f"    where: {areas}")

        by_func = collections.Counter()
        detail = collections.defaultdict(collections.Counter)
        for off, count in agg["native"].items():
            func = sym.function(off)
            by_func[func] += count
            detail[func][sym.where(off)] += count
        if by_func:
            print(f"    native code by function (% of all {name} samples; only the hottest buckets are logged):")
            for func, count in by_func.most_common(top):
                print(f"      {100.0 * count / n:5.1f}%  {func}")
                for where, c in detail[func].most_common(2):
                    if where and short(where, 400) != func:
                        print(f"               {100.0 * c / n:4.1f}% at {where}")

        if agg["stacks"]:
            print(f"    call chains (% of all {name} samples):")
            for (head, lr, chain), count in agg["stacks"].most_common(top):
                text = loc_text(sym, head)
                if lr:
                    text += f" (lr {loc_text(sym, lr)})"
                for loc in chain:
                    text += " <- " + loc_text(sym, loc)
                print(f"      {100.0 * count / n:5.1f}%  {text}")

        if agg["guest"]:
            jit_ee = agg["areas"].get("ee", 0.0)
            print(f"    hottest guest blocks (% of all EE samples; recompiled EE code is {100.0 * jit_ee / n:.1f}%):")
            for pc, count in agg["guest"].most_common(top):
                print(f"      {100.0 * count / n:5.1f}%  {pc:08x}")

    if code:
        print("\n--- guest code of the hottest blocks")
        for pc, words in code.items():
            print(f"  block {pc:08x}:")
            for line in disassemble(pc, words):
                print(line)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--elf", required=True)
    parser.add_argument("--from", dest="start", type=float, default=0.0)
    parser.add_argument("--to", dest="end", type=float, default=1e12)
    parser.add_argument("--windows", action="store_true", help="also print every 10 s window")
    parser.add_argument("--top", type=int, default=25)
    opts = parser.parse_args()

    windows, anchor = parse_log(opts.log)
    sym = Symbolizer(opts.elf)
    if anchor is not None:
        nm = subprocess.run(["llvm-nm", opts.elf], capture_output=True, text=True).stdout
        m = re.search(r"^([0-9a-f]+) T HorizonProfilerAnchor$", nm, re.M)
        if m and int(m.group(1), 16) != anchor:
            print(f"WARNING: the ELF does not match the log (anchor {int(m.group(1), 16):#x} vs {anchor:#x})")
        elif m:
            print(f"ELF matches the log (anchor at +{anchor:#x})")

    selected = [w for w in windows if opts.start <= w["time"] <= opts.end]
    if not selected:
        print("no [PROF] windows in that range")
        return 1
    if opts.windows:
        for w in selected:
            report([w], sym, opts.top, f"window ending at {w['time']:.1f}s")
            print()
    report(selected, sym, opts.top,
           f"{len(selected)} windows from {selected[0]['time']:.1f}s to {selected[-1]['time']:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
