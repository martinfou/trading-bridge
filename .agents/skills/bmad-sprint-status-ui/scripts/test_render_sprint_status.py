#!/usr/bin/env python3
import os
import tempfile
import unittest
from pathlib import Path
from render_sprint_status import (
    parse_sprint_status,
    get_epic_for_story,
    make_progress_bar,
    generate_dashboard
)

class TestRenderSprintStatus(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.TemporaryDirectory()
        self.artifacts_dir = Path(self.test_dir.name) / "artifacts"
        self.artifacts_dir.mkdir()
        
    def tearDown(self):
        self.test_dir.cleanup()
        
    def test_parse_sprint_status(self):
        yaml_content = """
# comment line
project: test-project
last_updated: 2026-06-21

development_status:
  epic-1: in-progress
  1-1-story-one: done
  1-2-story-two: review
"""
        yaml_file = Path(self.test_dir.name) / "status.yaml"
        with open(yaml_file, "w", encoding="utf-8") as f:
            f.write(yaml_content)
            
        metadata, dev_status = parse_sprint_status(str(yaml_file))
        
        self.assertEqual(metadata.get("project"), "test-project")
        self.assertEqual(metadata.get("last_updated"), "2026-06-21")
        self.assertEqual(dev_status.get("epic-1"), "in-progress")
        self.assertEqual(dev_status.get("1-1-story-one"), "done")
        self.assertEqual(dev_status.get("1-2-story-two"), "review")

    def test_get_epic_for_story(self):
        self.assertEqual(get_epic_for_story("1-1-maven-structure"), "epic-1")
        self.assertEqual(get_epic_for_story("12-5-event-stream"), "epic-12")
        self.assertEqual(get_epic_for_story("dg-4-dashboard"), "epic-desktop-gui")
        self.assertEqual(get_epic_for_story("dci-1-matrix"), "epic-desktop-crossplatform-ci")
        self.assertEqual(get_epic_for_story("dbj-3-jvm"), "epic-desktop-bundle-java")
        self.assertIsNone(get_epic_for_story("invalid-story-key"))

    def test_make_progress_bar(self):
        self.assertEqual(make_progress_bar(0), "[░░░░░░░░░░]")
        self.assertEqual(make_progress_bar(50), "[█████░░░░░]")
        self.assertEqual(make_progress_bar(100), "[██████████]")

    def test_generate_dashboard_basic(self):
        metadata = {"project": "test-project", "last_updated": "2026-06-21"}
        dev_status = {
            "epic-1": "in-progress",
            "1-1-story-one": "done",
            "1-2-story-two": "review"
        }
        
        # Create story file for 1-2 to check blockers / modified time
        story_file = self.artifacts_dir / "1-2-story-two.md"
        with open(story_file, "w", encoding="utf-8") as f:
            f.write("Status: review\nThis is blocked by some issue.\n")
            
        dashboard_md = generate_dashboard(metadata, dev_status, str(self.artifacts_dir))
        
        self.assertIn("Sprint Status UI Dashboard", dashboard_md)
        self.assertIn("test-project", dashboard_md)
        self.assertIn("50.0%", dashboard_md)  # 1 done out of 2 stories = 50%
        self.assertIn("BLOCKED", dashboard_md) # contains "blocked" keyword
        self.assertIn("1-2-story-two", dashboard_md)
        self.assertIn("This is blocked by some issue", dashboard_md)

if __name__ == "__main__":
    unittest.main()
