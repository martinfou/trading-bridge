---
name: bmad-sprint-status-ui
description: 'Generate a rich visual Markdown dashboard or launch the interactive, web-based BMad local project board.'
---

# Sprint Status UI Workflow

**Goal:** Generate and display a beautiful visual representation of the current sprint status (either as a static Markdown report or an interactive web-based Kanban board).

## On Activation

Based on the user's request, perform one of the following actions:

### Option A: Generate Static Markdown Dashboard (Default)
1. Run the rendering script to parse `sprint-status.yaml` and generate the dashboard Markdown file:
   `python3 {skill-root}/scripts/render_sprint_status.py`
2. Open the newly generated dashboard file `_bmad-output/sprint-status-dashboard.md` as a user-facing Markdown artifact.
3. Highlight only the hero metrics, blocked tasks, and the next recommended actions (do not re-summarize the entire dashboard).

### Option B: Launch Interactive Project Board (HTTP Server)
1. Run the zero-dependency Python project board server in the background, pointing to the current project directory:
   `python3 {skill-root}/scripts/project_board_server.py --project-root . --port 8010 &`
2. Open the user's default browser to `http://localhost:8010` to display the interactive board.
3. Confirm to the user that the server has launched successfully.
