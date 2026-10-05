"""Close only the four authorized issues with exact-comment readback."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = "e-zz/logseq"
TARGETS = [4, 8, 9, 11]


def gh(*args, stdin=None):
    proc = subprocess.run(
        ["gh", *args], input=stdin, capture_output=True,
        text=True, encoding="utf-8", timeout=60,
    )
    if proc.returncode:
        raise RuntimeError(f"gh {args}: {proc.stderr}")
    return proc.stdout


def api(endpoint, *args, stdin=None):
    return json.loads(gh("api", endpoint, *args, stdin=stdin))


def save(name, obj):
    (ROOT / name).write_text(
        json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def states():
    data = json.loads(gh(
        "issue", "list", "--repo", REPO, "--state", "all", "--limit", "100",
        "--json", "number,title,state,url,closedAt",
    ))
    return {str(item["number"]): item for item in data}


def main():
    payloads = json.loads((ROOT / "payloads.json").read_text(encoding="utf-8"))
    if sorted(map(int, payloads)) != TARGETS:
        raise RuntimeError("Payload targets differ from user-authorized scope")
    before = states()
    save("states-before.json", before)
    for n in TARGETS:
        if before[str(n)]["state"] != "OPEN":
            raise RuntimeError(f"#{n} is not OPEN; review before changing it")
    progress = {"repository": REPO, "targets": TARGETS, "verified": []}
    save("verification.json", progress)
    for n in TARGETS:
        body = payloads[str(n)]
        if (ROOT / f"issue-{n}-closure.md").read_text(encoding="utf-8") != body:
            raise RuntimeError(f"#{n} markdown differs from reviewed payload")
        groups = json.loads(gh(
            "api", f"repos/{REPO}/issues/{n}/comments?per_page=100",
            "--paginate", "--slurp",
        ))
        comments = [item for group in groups for item in group]
        existing = [item for item in comments if item.get("body") == body]
        if len(existing) > 1:
            raise RuntimeError(f"#{n} has multiple identical comments")
        if existing:
            posted = existing[0]
        else:
            posted = api(
                f"repos/{REPO}/issues/{n}/comments", "--method", "POST",
                "--input", "-", stdin=json.dumps({"body": body}),
            )
        save(f"issue-{n}-posted.json", posted)
        comment = api(f"repos/{REPO}/issues/comments/{posted['id']}")
        save(f"issue-{n}-comment-readback.json", comment)
        if comment["body"] != body:
            raise RuntimeError(f"#{n} published body does not match reviewed payload")
        close_output = gh(
            "issue", "close", str(n), "--repo", REPO, "--reason", "completed",
        )
        issue = api(f"repos/{REPO}/issues/{n}")
        state = {k: issue[k] for k in (
            "number", "title", "state", "state_reason", "closed_at", "html_url",
        )}
        save(f"issue-{n}-state-readback.json", state)
        if issue["state"] != "closed" or issue["state_reason"] != "completed":
            raise RuntimeError(f"#{n} did not close as completed")
        item = {
            "number": n, "comment_url": comment["html_url"],
            "comment_id": comment["id"], "body_match": True,
            "state": issue["state"], "state_reason": issue["state_reason"],
            "closed_at": issue["closed_at"], "issue_url": issue["html_url"],
            "close_command_output": close_output,
        }
        progress["verified"].append(item)
        save("verification.json", progress)
        print(json.dumps(item, ensure_ascii=False), flush=True)
    after = states()
    save("states-after.json", after)
    non_target_changes = {
        key: {"before": item["state"], "after": after.get(key, {}).get("state")}
        for key, item in before.items()
        if int(key) not in TARGETS and item["state"] != after.get(key, {}).get("state")
    }
    progress["non_target_state_changes"] = non_target_changes
    progress["all_verified"] = (
        sorted(item["number"] for item in progress["verified"]) == TARGETS
        and not non_target_changes
    )
    save("verification.json", progress)
    if not progress["all_verified"]:
        raise RuntimeError("Final scope/count verification failed")
    print("ALL_VERIFIED", json.dumps(TARGETS), flush=True)


if __name__ == "__main__":
    main()
