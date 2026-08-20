# Strix — where runs live & how to view them

Quick reference for finding past Strix pentest runs and opening them in the web viewer.
(Full plan + cost model: `docs/prod-readiness/STRIX-PENTEST-PLAN.md`.)

---

## Where runs are stored

Every run is written to **`strix_runs/<run-name>/`**, relative to the directory Strix was launched
from. We launch from the repo root, so runs live at:

```
/Users/sidarthapati/Desktop/Projects/one_day_delivery/strix_runs/
```

This folder is **gitignored** (run logs can contain creds), so it stays local to this machine.

**List all runs:**
```bash
ls -1 strix_runs/
# e.g. one-day-delivery_88d1
```

**What's inside each `strix_runs/<run>/`:**
| File | What it is |
|---|---|
| `penetration_test_report.md` | The human-readable report (exec summary, findings, recommendations) |
| `vulnerabilities/vuln-000N.md` | One detailed markdown file per finding (PoC, code locations, fix) |
| `findings.sarif` | SARIF — import into IDEs / code-scanning tools |
| `vulnerabilities.csv` / `.json` | Machine-readable finding lists |
| `strix.log` | Full agent activity log |
| `run.json`, `.state/` | Run metadata + agent state (used by the viewer) |

> You don't need the viewer to read results — just open the `.md` files directly
> (`open strix_runs/<run>/penetration_test_report.md`).

---

## Opening a run in the web viewer

The viewer binary is at `~/.strix/bin/strix`, so first put it on PATH:
```bash
export PATH=~/.strix/bin:$PATH
```

**Open a specific run** (from the repo root so it finds `strix_runs/`):
```bash
strix view one-day-delivery_88d1            # a random free port, auto-opens browser
strix view one-day-delivery_88d1 --port 7331    # fixed port
strix view                                   # most recent run
strix view <run> --port 7331 --no-open       # serve only, don't auto-open (we use this when Claude launches it)
```

### ⚠️ The important gotcha — use the tokenized URL

When it starts, the viewer **prints a URL with a `?token=…`** — open **that** one:
```
Serving one-day-delivery_88d1 (finished) at:
  http://127.0.0.1:7331/?token=XXXXXXXXXXXXXXXX
```

- The **bare** `http://localhost:7331` drops you on a **"Verify your email to browse all runs"** page.
  That is just Strix's lead-capture gate on the *browse-all-history* feature — **ignore it.**
- The **`?token=…`** link authorizes your browser and opens the run **directly, no email needed.**
- If you launched with `--no-open`, the token URL is only in the terminal output — copy it from there.

**Security:** that token link lets whoever opens it browse history and even steer a *live* scan.
It's localhost-only, so just **don't share it**. A new token is minted each time you start the viewer.

**Stop the viewer:** `Ctrl-C` in its terminal (or kill the process if backgrounded).

---

## Live viewing during a scan

`strix view` works on **live or finished** runs. So you can watch a scan as it happens:
open a second terminal while a scan is running and `strix view <run> --port 7331`, then open the
token URL — the agent tree and findings update in real time.

(When Claude drives a scan, it runs the scan headless in the background **and** starts this viewer,
then hands you the `?token=` URL to watch live — "Model A" in the plan doc.)
