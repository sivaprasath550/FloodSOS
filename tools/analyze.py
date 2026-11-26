#!/usr/bin/env python3
"""
FloodSOS field-test analysis.

Merges two sources of truth:
  * Firestore  -> what ARRIVED (and when the server got it)
  * phone exports (Stats screen -> Export) -> what was SENT, including
    messages that never arrived, plus every Bluetooth exchange

and reports delivery rate, latency per channel, hop distribution and relay
exchange stats as Markdown (+ PNG charts if matplotlib is installed).

Usage:
  python tools/analyze.py                                  # Firestore only
  python tools/analyze.py --exports field-data/            # + delivery rate
  python tools/analyze.py --exports field-data/ --since 2026-11-26T10:00 --until 2026-11-26T12:00

Standard library only (matplotlib optional for charts).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANNELS = ["internet", "sms", "relay"]  # fixed order = fixed colour per channel
EXPORT_FORMAT = 1


# --------------------------------------------------------------------------- data in

def parse_value(v: dict):
    """Firestore REST wraps every value in its type, e.g. {"integerValue": "42"}."""
    if "stringValue" in v:
        return v["stringValue"]
    if "integerValue" in v:
        return int(v["integerValue"])  # 64-bit ints arrive as strings
    if "doubleValue" in v:
        return float(v["doubleValue"])
    if "booleanValue" in v:
        return v["booleanValue"]
    if "timestampValue" in v:
        return iso_to_ms(v["timestampValue"])
    if "nullValue" in v:
        return None
    raise ValueError(f"Unsupported Firestore value: {v}")


def iso_to_ms(text: str) -> int:
    # Firestore: "2026-11-26T10:15:30.123456Z" (fraction length varies)
    dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
    return int(dt.timestamp() * 1000)


def parse_doc(doc: dict) -> dict:
    fields = {k: parse_value(v) for k, v in doc.get("fields", {}).items()}
    fields.setdefault("id", doc["name"].rsplit("/", 1)[-1])
    return fields


def fetch_messages(project: str, token: str | None = None) -> list[dict]:
    """Reads every document in `messages` through the public REST API (paged)."""
    base = (f"https://firestore.googleapis.com/v1/projects/{project}"
            f"/databases/(default)/documents/messages")
    docs, page_token = [], None
    while True:
        params = {"pageSize": "300"}
        if page_token:
            params["pageToken"] = page_token
        req = urllib.request.Request(f"{base}?{urllib.parse.urlencode(params)}")
        if token:  # needed only if the rules stop allowing public reads
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.load(resp)
        docs += [parse_doc(d) for d in body.get("documents", [])]
        page_token = body.get("nextPageToken")
        if not page_token:
            return docs


def load_exports(directory: Path) -> list[dict]:
    exports = []
    for path in sorted(directory.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("format") != EXPORT_FORMAT or "deviceId" not in data:
            print(f"skipping {path.name}: not a FloodSOS export (format {EXPORT_FORMAT})", file=sys.stderr)
            continue
        exports.append(data)
    return exports


def default_project() -> str | None:
    rc = ROOT / ".firebaserc"
    if rc.exists():
        return json.loads(rc.read_text()).get("projects", {}).get("default")
    return None


# --------------------------------------------------------------------------- maths

def percentile(sorted_values: list[float], p: float) -> float:
    """Nearest-rank percentile, the same method as the app's Percentiles.kt."""
    if not sorted_values:
        raise ValueError("no values")
    if not 0 < p <= 100:
        raise ValueError("p must be in (0, 100]")
    return sorted_values[math.ceil(p / 100 * len(sorted_values)) - 1]


def summarize(values: list[float]) -> dict:
    if not values:
        return {"count": 0, "median": None, "p90": None, "max": None}
    s = sorted(values)
    return {"count": len(s), "median": percentile(s, 50), "p90": percentile(s, 90), "max": s[-1]}


def in_window(ms: int, since: int | None, until: int | None) -> bool:
    return (since is None or ms >= since) and (until is None or ms <= until)


def analyze(delivered: list[dict], exports: list[dict],
            since: int | None = None, until: int | None = None) -> dict:
    """Pure function: all numbers in the report come from here (unit-tested)."""
    delivered = [d for d in delivered if in_window(d["createdAt"], since, until)]
    by_id = {d["id"]: d for d in delivered}

    # Latency = server receive time - phone create time. Different clocks, so a
    # phone running fast can produce a negative value: count it, don't hide it.
    latencies = {c: [] for c in CHANNELS}
    skewed = 0
    for d in delivered:
        lat = d["receivedAt"] - d["createdAt"]
        if lat < 0:
            skewed += 1
            continue
        latencies.setdefault(d.get("channel", "unknown"), []).append(lat)

    hops: dict[int, int] = {}
    for d in delivered:
        hops[d["hops"]] = hops.get(d["hops"], 0) + 1

    result = {
        "delivered_count": len(delivered),
        "latency": {c: summarize(v) for c, v in latencies.items()},
        "latency_all": summarize([x for v in latencies.values() for x in v]),
        "latency_values": latencies,  # raw, for charts
        "skewed": skewed,
        "hops": dict(sorted(hops.items())),
        "devices": len(exports),
        "delivery": None,
        "exchanges": None,
    }

    if exports:
        # Ground truth of what was SENT: each phone's own messages.
        created: dict[str, dict] = {}
        held_by: dict[str, set] = {}
        for e in exports:
            short = e["deviceId"][:8]
            for m in e["messages"]:
                held_by.setdefault(m["id"], set()).add(short)
                if m["senderId"] == e["deviceId"] and in_window(m["createdAt"], since, until):
                    created[m["id"]] = m

        by_type = {}
        for t in ("SOS", "SAFE"):
            ids = [i for i, m in created.items() if m["type"] == t]
            by_type[t] = {"sent": len(ids), "delivered": sum(1 for i in ids if i in by_id)}
        delivered_ids = [i for i in created if i in by_id]
        undelivered = sorted(
            ({"id": i, "type": m["type"], "createdAt": m["createdAt"], "smsSent": m.get("smsSent", False),
              "senderId": m["senderId"][:8], "heldBy": sorted(held_by.get(i, set()))}
             for i, m in created.items() if i not in by_id),
            key=lambda u: u["createdAt"],
        )
        result["delivery"] = {
            "sent": len(created),
            "delivered": len(delivered_ids),
            "rate": len(delivered_ids) / len(created) if created else None,
            "by_type": by_type,
            "undelivered": undelivered,
        }

        records = [(e["deviceId"][:8], x) for e in exports for x in e.get("exchanges", [])
                   if in_window(x["startedAt"], since, until)]
        ok = [x for _, x in records if x["success"]]
        result["exchanges"] = {
            "records": len(records),  # each meeting is logged by BOTH phones
            "success": len(ok),
            "success_rate": len(ok) / len(records) if records else None,
            "duration": summarize([x["durationMs"] for x in ok]),
            "pairs": len({frozenset((me, x["peer"])) for me, x in records}),
        }
    return result


# --------------------------------------------------------------------------- output

def fmt_duration(ms) -> str:
    if ms is None:
        return "–"
    s = int(ms // 1000)
    if s < 60:
        return f"{s}s"
    if s < 3600:
        return f"{s // 60}m {s % 60}s"
    return f"{s // 3600}h {(s % 3600) // 60}m"


def fmt_pct(x) -> str:
    return "–" if x is None else f"{x * 100:.1f}%"


def fmt_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


def render_markdown(r: dict, project: str, window: str, charts: list[str]) -> str:
    out = [
        "# FloodSOS field test report",
        "",
        f"- Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}",
        f"- Firestore project: `{project}`",
        f"- Window: {window}",
        f"- Messages on the server: **{r['delivered_count']}**",
        f"- Phone exports used: **{r['devices']}**",
        "",
        "## Delivery rate",
        "",
    ]
    d = r["delivery"]
    if d is None:
        out += ["Needs phone exports (Stats screen → Export, then `--exports DIR`): "
                "Firestore alone can't show messages that never arrived.", ""]
    else:
        out += ["| Type | Sent | Delivered | Rate |", "|---|---:|---:|---:|"]
        for t, v in d["by_type"].items():
            out.append(f"| {t} | {v['sent']} | {v['delivered']} | "
                       f"{fmt_pct(v['delivered'] / v['sent'] if v['sent'] else None)} |")
        out += [f"| **All** | **{d['sent']}** | **{d['delivered']}** | **{fmt_pct(d['rate'])}** |", ""]

    out += ["## Latency (created on phone → received by server)", "",
            "| Channel | Messages | Median | p90 | Max |", "|---|---:|---:|---:|---:|"]
    for c, s in r["latency"].items():
        out.append(f"| {c} | {s['count']} | {fmt_duration(s['median'])} | "
                   f"{fmt_duration(s['p90'])} | {fmt_duration(s['max'])} |")
    a = r["latency_all"]
    out.append(f"| **All** | **{a['count']}** | **{fmt_duration(a['median'])}** | "
               f"**{fmt_duration(a['p90'])}** | **{fmt_duration(a['max'])}** |")
    if r["skewed"]:
        out.append(f"\n{r['skewed']} message(s) excluded: negative latency (the phone's clock was ahead of the server's).")
    out.append("")

    out += ["## Hop distribution (delivered messages)", "", "| Hops | Messages | Share |", "|---:|---:|---:|"]
    total = sum(r["hops"].values()) or 1
    for h, n in r["hops"].items():
        out.append(f"| {h} | {n} | {fmt_pct(n / total)} |")
    out.append("")

    x = r["exchanges"]
    if x is not None:
        out += ["## Bluetooth exchanges", "",
                f"- Exchange records: {x['records']} (each meeting is logged by both phones)",
                f"- Completed: {x['success']} ({fmt_pct(x['success_rate'])})",
                f"- Distinct phone pairs: {x['pairs']}",
                f"- Duration (completed): median {fmt_duration(x['duration']['median'])}, "
                f"p90 {fmt_duration(x['duration']['p90'])}", ""]

    if d is not None and d["undelivered"]:
        out += ["## Undelivered messages", "", "| Created | Type | Sender | SMS sent | Still held by |",
                "|---|---|---|---|---|"]
        for u in d["undelivered"]:
            out.append(f"| {fmt_time(u['createdAt'])} | {u['type']} | {u['senderId']} | "
                       f"{'yes' if u['smsSent'] else 'no'} | {', '.join(u['heldBy'])} |")
        out.append("")

    if charts:
        out += ["## Charts", ""] + [f"![{c}]({c})" for c in charts] + [""]
    return "\n".join(out)


# Reference palette (validated light mode, slots 1-3 pass all-pairs CVD checks).
COLORS = {"internet": "#2a78d6", "sms": "#eb6834", "relay": "#1baf7a"}
INK, INK_2, MUTED, GRID, AXIS, SURFACE = "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7", "#fcfcfb"


def make_charts(r: dict, out_dir: Path) -> list[str]:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib not installed: skipping charts", file=sys.stderr)
        return []

    def style(ax):
        ax.set_facecolor(SURFACE)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(AXIS)
        ax.tick_params(colors=MUTED, labelsize=9)
        ax.grid(axis="y", color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)

    files = []

    # 1) "What share of messages arrived within X?" per channel (ECDF).
    #    Log time axis: latencies span seconds (internet) to hours (relay), and one
    #    slow message would otherwise squash everything else against zero.
    series = {c: sorted(v) for c, v in r["latency_values"].items() if c in COLORS and v}
    if series:
        from matplotlib.ticker import FixedLocator, FuncFormatter
        fig, ax = plt.subplots(figsize=(7, 4), facecolor=SURFACE)
        style(ax)
        for c in CHANNELS:  # fixed order -> fixed colour per channel
            if c not in series:
                continue
            secs = [max(v / 1000, 1) for v in series[c]]  # floor at 1 s for the log axis
            ys = [(i + 1) / len(secs) * 100 for i in range(len(secs))]
            ax.step([secs[0]] + secs, [0] + ys, where="post", color=COLORS[c], linewidth=2,
                    label=f"{c} (n={len(secs)})")
            ax.annotate(c, (secs[-1], ys[-1]), xytext=(6, 0), textcoords="offset points",
                        va="center", fontsize=9, color=INK_2)  # direct label, ink not series colour
        ax.set_xscale("log")
        ticks = [1, 10, 60, 600, 3600, 6 * 3600, 86400, 7 * 86400]
        ax.xaxis.set_major_locator(FixedLocator(ticks))
        ax.xaxis.set_major_formatter(FuncFormatter(lambda s, _: fmt_duration(s * 1000).replace(" 0s", "").replace(" 0m", "")))
        ax.xaxis.set_minor_locator(FixedLocator([]))
        ax.set_xlabel("Time from SOS to server (log scale)", color=INK_2)
        ax.set_ylabel("% of messages delivered", color=INK_2)
        ax.set_ylim(0, 105)
        ax.set_title("Delivery latency by channel", loc="left", color=INK, fontsize=12)
        ax.legend(frameon=False, fontsize=9, labelcolor=INK_2, loc="lower right")
        fig.tight_layout()
        fig.savefig(out_dir / "latency_by_channel.png", dpi=150)
        plt.close(fig)
        files.append("latency_by_channel.png")

    # 2) Hop distribution: one series, so one hue and no legend.
    if r["hops"]:
        fig, ax = plt.subplots(figsize=(6, 3.5), facecolor=SURFACE)
        style(ax)
        # Always show the full 0..5 range (the app's hop limit), so an empty hop
        # level reads as "none" instead of disappearing.
        hs = list(range(0, max(5, max(r["hops"])) + 1))
        ns = [r["hops"].get(h, 0) for h in hs]
        bars = ax.bar([str(h) for h in hs], ns, width=0.6, color=COLORS["internet"])
        ax.bar_label(bars, padding=3, fontsize=9, color=INK_2)
        ax.set_xlabel("Hops before reaching the server", color=INK_2)
        ax.set_ylabel("Messages", color=INK_2)
        ax.set_title("Hop distribution of delivered messages", loc="left", color=INK, fontsize=12)
        ax.margins(y=0.15)
        fig.tight_layout()
        fig.savefig(out_dir / "hop_distribution.png", dpi=150)
        plt.close(fig)
        files.append("hop_distribution.png")
    return files


def local_ms(text: str) -> int:
    """'2026-11-26T10:00' in local time (or with an explicit offset) -> epoch ms."""
    dt = datetime.fromisoformat(text)
    return int(dt.timestamp() * 1000)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="FloodSOS field-test analysis")
    p.add_argument("--project", default=default_project(), help="Firebase project id (default: .firebaserc)")
    p.add_argument("--exports", type=Path, help="folder with phone export JSON files")
    p.add_argument("--since", help="only messages created at/after this local time, e.g. 2026-11-26T10:00")
    p.add_argument("--until", help="only messages created at/before this local time")
    p.add_argument("--out", type=Path, default=ROOT / "results", help="output folder (default: results/)")
    p.add_argument("--no-charts", action="store_true")
    args = p.parse_args(argv)

    if not args.project:
        p.error("no --project and no .firebaserc found")
    since = local_ms(args.since) if args.since else None
    until = local_ms(args.until) if args.until else None

    delivered = fetch_messages(args.project, os.environ.get("FIRESTORE_TOKEN"))
    exports = load_exports(args.exports) if args.exports else []
    result = analyze(delivered, exports, since, until)

    args.out.mkdir(parents=True, exist_ok=True)
    charts = [] if args.no_charts else make_charts(result, args.out)
    window = f"{args.since or 'start'} → {args.until or 'now'}"
    report = render_markdown(result, args.project, window, charts)
    (args.out / "report.md").write_text(report, encoding="utf-8")

    sys.stdout.reconfigure(encoding="utf-8")
    print(report)
    print(f"\nSaved {args.out / 'report.md'}" + (f" and {len(charts)} chart(s)" if charts else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
