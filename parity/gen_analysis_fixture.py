"""Generate the Android parser fixture from the real engine.

A script rather than a hand-written JSON so the fixture is provably the engine's
own wire format for `analyze_logs`. Re-run when that op changes shape:

    Code/.venv/bin/python <this script>
"""
import hashlib, json, sys, tempfile
from pathlib import Path

sys.path.insert(0, "/Users/sam/SimosTools/Code")
from tests.faultinject import PullSpec, build_folder
from simoscal.bridge import BRIDGE_VERSION, dispatch

folder = Path(tempfile.mkdtemp())
# Short pulls: just over the 15-sample / 0.8 s / 800-rpm detection floor, so the
# fixture exercises every plot without carrying a real drive's worth of samples.
build_folder(
    folder,
    [PullSpec(n=26, put_overshoot=25.0, knock={3: -3.5}), PullSpec(n=26)],
    wastegate=True,
    ign_table=True,
)
csvs = sorted(folder.glob("simostools-*.csv"))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()

env = json.loads(dispatch(json.dumps({
    "bridge_version": BRIDGE_VERSION,
    "op": "analyze_logs",
    "request_id": "fixture",
    "params": {"logs": [
        {"log_path": str(p), "log_sha256": sha(p), "display_name": p.name} for p in csvs
    ]},
})))
assert env["ok"], env
result = env["result"]

out = Path("/Users/sam/simoscal-android/engine/src/test/resources/analysis_result.json")
out.write_text(json.dumps(result, indent=1, sort_keys=True))
print("wrote", out.stat().st_size, "bytes")
print("plots:", [(p["id"], p["drawn"]) for p in result["plots"]])
print("pulls:", len(result["pulls"]), "findings:", len(result["findings"]),
      "skipped:", [s["check_id"] for s in result["skipped"]])
print("severities:", sorted({f["severity"] for f in result["findings"]}))
