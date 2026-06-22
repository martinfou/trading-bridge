#!/usr/bin/env python3
import os
import sys
import re
import time
from pathlib import Path
import argparse

def parse_sprint_status(file_path):
    if not os.path.exists(file_path):
        sys.stderr.write(f"Error: file not found at {file_path}\n")
        sys.exit(1)
        
    metadata = {}
    development_status = {}
    in_dev_status = False
    
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            
            if stripped.startswith("development_status:"):
                in_dev_status = True
                continue
            
            if ":" not in stripped:
                continue
                
            key, val = stripped.split(":", 1)
            key = key.strip()
            val = val.strip().strip('"').strip("'")
            
            if not in_dev_status:
                metadata[key] = val
            else:
                development_status[key] = val
                
    return metadata, development_status

def get_epic_for_story(story_key):
    if story_key.startswith("dg-"):
        return "epic-desktop-gui"
    if story_key.startswith("dci-"):
        return "epic-desktop-crossplatform-ci"
    if story_key.startswith("dbj-"):
        return "epic-desktop-bundle-java"
    
    match = re.match(r"^(\d+)-", story_key)
    if match:
        return f"epic-{match.group(1)}"
    return None

def make_progress_bar(percentage):
    filled = int(percentage / 10)
    empty = 10 - filled
    return "[" + "█" * filled + "░" * empty + "]"

def scan_story_file(artifacts_dir, story_key, status):
    filepath = os.path.join(artifacts_dir, f"{story_key}.md")
    blockers = []
    stalled = False
    last_modified = None
    
    if os.path.exists(filepath):
        mtime = os.path.getmtime(filepath)
        last_modified = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(mtime))
        
        # Check if stalled (active and no edits in last 48 hours)
        if status in ("in-progress", "review"):
            if time.time() - mtime > 48 * 3600:
                stalled = True
                
        # Scan for blockers
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                content = f.read()
                # Scan line by line for blockers
                for line in content.splitlines():
                    line_lower = line.lower()
                    if any(w in line_lower for w in ("block", "depend", "stuck")):
                        # Skip if completed task
                        if "[x]" in line_lower or "unblock" in line_lower:
                            continue
                        blockers.append(line.strip())
        except Exception:
            pass
            
    return stalled, blockers, last_modified

def generate_dashboard(metadata, dev_status, artifacts_dir):
    artifacts_dir = os.path.abspath(artifacts_dir)
    epics = {}
    stories = {}
    retrospectives = {}
    
    for key, val in dev_status.items():
        if key.startswith("epic-") and not key.endswith("-retrospective"):
            epics[key] = {
                "status": val,
                "stories_total": 0,
                "stories_done": 0,
                "name": key.replace("epic-", "Epic "),
                "stories_list": []
            }
        elif key.endswith("-retrospective"):
            retrospectives[key] = val
        else:
            stories[key] = {
                "status": val,
                "epic": get_epic_for_story(key)
            }
            
    # Associate stories with epics and calculate counts
    for s_key, s_val in stories.items():
        epic_key = s_val["epic"]
        if epic_key in epics:
            epics[epic_key]["stories_total"] += 1
            if s_val["status"] == "done":
                epics[epic_key]["stories_done"] += 1
            epics[epic_key]["stories_list"].append({
                "key": s_key,
                "status": s_val["status"]
            })
                
    # Calculate overall stats
    total_stories = len(stories)
    done_stories = sum(1 for s in stories.values() if s["status"] == "done")
    review_stories = sum(1 for s in stories.values() if s["status"] == "review")
    in_progress_stories = sum(1 for s in stories.values() if s["status"] == "in-progress")
    ready_stories = sum(1 for s in stories.values() if s["status"] == "ready-for-dev")
    backlog_stories = sum(1 for s in stories.values() if s["status"] == "backlog")
    
    overall_percentage = (done_stories / total_stories * 100) if total_stories > 0 else 0.0
    
    # Check active status & identify stalled / blocked
    stalled_stories = []
    blocked_stories = []
    
    for s_key, s_val in stories.items():
        if s_val["status"] in ("in-progress", "review", "ready-for-dev"):
            stalled, blockers, mtime = scan_story_file(artifacts_dir, s_key, s_val["status"])
            s_val["stalled"] = stalled
            s_val["blockers"] = blockers
            s_val["mtime"] = mtime
            
            if stalled:
                stalled_stories.append(s_key)
            if blockers:
                blocked_stories.append((s_key, blockers))
                
    # Determine Health
    health = "HEALTHY"
    health_color = "green"
    if blocked_stories:
        health = "BLOCKED"
        health_color = "red"
    elif stalled_stories:
        health = "WARNING"
        health_color = "orange"
        
    md = []
    md.append(f"# Sprint Status UI Dashboard\n")
    md.append(f"**Project:** {metadata.get('project', 'Unknown')} | **Last Updated:** {metadata.get('last_updated', 'Unknown')}\n")
    
    # Hero Metrics
    prog_bar = make_progress_bar(overall_percentage)
    health_badge = f"<span style='color:{health_color}; font-weight:bold;'>[{health}]</span>"
    md.append(f"## Hero Metrics\n")
    md.append(f"- **Overall Completion:** {overall_percentage:.1f}% `{prog_bar}`")
    md.append(f"- **Sprint Health:** {health_badge}")
    md.append(f"- **Story Breakdown:** Done: {done_stories} | Review: {review_stories} | In-Progress: {in_progress_stories} | Ready: {ready_stories} | Backlog: {backlog_stories}\n")
    
    # Active Pipeline Diagram (Mermaid)
    ip_epics = [k for k, v in epics.items() if v["status"] == "in-progress"]
    if ip_epics:
        md.append(f"### Active Epics Pipeline\n")
        md.append("```mermaid")
        md.append("flowchart LR")
        md.append("  classDef done fill:#1b5e20,stroke:#81c784,color:#fff")
        md.append("  classDef ip fill:#e65100,stroke:#ffb74d,color:#fff")
        
        nodes = []
        for i, epic_key in enumerate(sorted(ip_epics)):
            e_data = epics[epic_key]
            done_cnt = e_data["stories_done"]
            tot_cnt = e_data["stories_total"]
            pct = (done_cnt / tot_cnt * 100) if tot_cnt > 0 else 0.0
            node_id = epic_key.replace("-", "")
            node_label = f'"{e_data["name"]}<br/>({pct:.0f}%)"'
            nodes.append(f"  {node_id}[{node_label}]:::ip")
            
        md.extend(nodes)
        
        # Link active epics in flow
        if len(ip_epics) > 1:
            links = []
            for i in range(len(ip_epics) - 1):
                n1 = sorted(ip_epics)[i].replace("-", "")
                n2 = sorted(ip_epics)[i+1].replace("-", "")
                links.append(f"  {n1} --> {n2}")
            md.extend(links)
            
        md.append("```\n")
        
    # Split Layout Widescreen Table
    md.append("## Project Widescreen Board\n")
    md.append("| Roadmap & Epics | Active Stories (Sprint Kanban) |")
    md.append("| :--- | :--- |")
    
    # Build Left Column (Roadmap)
    left_lines = []
    # Sort Epics by number
    def epic_sort_key(k):
        if "desktop" in k:
            return 999
        match = re.search(r"epic-(\d+)", k)
        return int(match.group(1)) if match else 0
        
    sorted_epic_keys = sorted(epics.keys(), key=epic_sort_key)
    
    # Determine the "active pointer"
    active_epic_key = None
    for k in sorted_epic_keys:
        if epics[k]["status"] == "in-progress":
            active_epic_key = k
            break
            
    for k in sorted_epic_keys:
        e = epics[k]
        pct = (e["stories_done"] / e["stories_total"] * 100) if e["stories_total"] > 0 else (100.0 if e["status"] == "done" else 0.0)
        pointer = "▶ " if k == active_epic_key else "&nbsp;&nbsp;"
        
        stories_html = []
        if e["stories_list"]:
            stories_html.append("<br/><details>")
            stories_html.append(f"<summary>Show stories ({len(e['stories_list'])})</summary>")
            stories_html.append("<ul>")
            for story in sorted(e["stories_list"], key=lambda s: s["key"]):
                status_icon = "🟢" if story["status"] == "done" else ("🟡" if story["status"] == "review" else ("🔴" if story["status"] == "in-progress" else ("🔵" if story["status"] == "ready-for-dev" else "⚪")))
                file_link = f"[ {story['key']} ](file://{artifacts_dir}/{story['key']}.md)" if os.path.exists(os.path.join(artifacts_dir, f"{story['key']}.md")) else story['key']
                stories_html.append(f"<li>{status_icon} {file_link} ({story['status']})</li>")
            stories_html.append("</ul>")
            stories_html.append("</details>")
            
        left_lines.append(f"{pointer}**{e['name']}** ({e['status']})<br/>`{make_progress_bar(pct)}` {pct:.0f}%" + "".join(stories_html))
        
    # Build Right Column (Kanban)
    right_lines = []
    
    # Group Active Stories
    right_lines.append("**🟡 Review**")
    rev_list = [k for k, v in stories.items() if v["status"] == "review"]
    if rev_list:
        for s in sorted(rev_list):
            stalled_tag = " <span style='color:orange;'>[STALLED]</span>" if stories[s].get("stalled") else ""
            blocked_tag = " <span style='color:red;'>[⚠️ BLOCKED]</span>" if stories[s].get("blockers") else ""
            file_link = f"[ {s} ](file://{artifacts_dir}/{s}.md)" if os.path.exists(os.path.join(artifacts_dir, f"{s}.md")) else s
            right_lines.append(f"- {file_link}{stalled_tag}{blocked_tag}")
    else:
        right_lines.append("*(None)*")
        
    right_lines.append("<br/>**🔴 In Progress**")
    ip_list = [k for k, v in stories.items() if v["status"] == "in-progress"]
    if ip_list:
        for s in sorted(ip_list):
            stalled_tag = " <span style='color:orange;'>[STALLED]</span>" if stories[s].get("stalled") else ""
            blocked_tag = " <span style='color:red;'>[⚠️ BLOCKED]</span>" if stories[s].get("blockers") else ""
            file_link = f"[ {s} ](file://{artifacts_dir}/{s}.md)" if os.path.exists(os.path.join(artifacts_dir, f"{s}.md")) else s
            right_lines.append(f"- {file_link}{stalled_tag}{blocked_tag}")
    else:
        right_lines.append("*(None)*")
        
    right_lines.append("<br/>**🟢 Ready for Dev**")
    rdy_list = [k for k, v in stories.items() if v["status"] == "ready-for-dev"]
    if rdy_list:
        for s in sorted(rdy_list):
            blocked_tag = " <span style='color:red;'>[⚠️ BLOCKED]</span>" if stories[s].get("blockers") else ""
            file_link = f"[ {s} ](file://{artifacts_dir}/{s}.md)" if os.path.exists(os.path.join(artifacts_dir, f"{s}.md")) else s
            right_lines.append(f"- {file_link}{blocked_tag}")
    else:
        right_lines.append("*(None)*")
        
    right_lines.append("<br/><details>")
    back_list = [k for k, v in stories.items() if v["status"] == "backlog"]
    right_lines.append(f"<summary><b>⚫ Backlog ({len(back_list)})</b></summary>")
    if back_list:
        right_lines.append("<ul>")
        for s in sorted(back_list):
            file_link = f"[ {s} ](file://{artifacts_dir}/{s}.md)" if os.path.exists(os.path.join(artifacts_dir, f"{s}.md")) else s
            right_lines.append(f"<li>{file_link}</li>")
        right_lines.append("</ul>")
    else:
        right_lines.append("*(None)*")
    right_lines.append("</details>")
        
    # Zip left and right columns
    max_len = max(len(left_lines), len(right_lines))
    for idx in range(max_len):
        left_cell = left_lines[idx] if idx < len(left_lines) else ""
        right_cell = right_lines[idx] if idx < len(right_lines) else ""
        md.append(f"| {left_cell} | {right_cell} |")
        
    # Stalled / Blocked Highlights
    if blocked_stories or stalled_stories:
        md.append(f"\n## ⚠️ Alert & Bottlenecks\n")
        
        if blocked_stories:
            md.append(f"### Blocked Items")
            for s_key, blks in blocked_stories:
                md.append(f"- **{s_key}**:")
                for b in blks:
                    md.append(f"  - `{b}`")
            md.append("")
                    
        if stalled_stories:
            md.append(f"### Stalled Items (>48h without updates)")
            for s_key in stalled_stories:
                mtime_str = stories[s_key].get("mtime", "unknown")
                md.append(f"- **{s_key}** (Last modified: {mtime_str})")
            md.append("")
            
    return "\n".join(md)

def main():
    parser = argparse.ArgumentParser(description="Render Sprint Status Visual UI")
    parser.add_argument("--yaml", default="_bmad-output/implementation-artifacts/sprint-status.yaml", help="Path to sprint-status.yaml")
    parser.add_argument("--output", default="_bmad-output/sprint-status-dashboard.md", help="Path to output Markdown dashboard")
    parser.add_argument("--artifacts-dir", default="_bmad-output/implementation-artifacts", help="Directory where story markdown files live")
    
    args = parser.parse_args()
    
    metadata, dev_status = parse_sprint_status(args.yaml)
    dashboard_md = generate_dashboard(metadata, dev_status, args.artifacts_dir)
    
    # Ensure parent output directory exists
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(dashboard_md)
        
    print(f"Sprint status dashboard written successfully to {args.output}")

if __name__ == "__main__":
    main()
