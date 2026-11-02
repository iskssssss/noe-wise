"""执行 prompt 必须触发检查；说明 Markdown 不触发付费评测。"""
import fnmatch
from pathlib import Path
import unittest

from behavior_changes import analyse


class ChangeRoutingTests(unittest.TestCase):
    def test_prompt_and_docs_routes(self):
        prefix = "backend-java/skillforge-prompts/"
        result = analyse([prefix + "src/main/resources/com/skillforge/prompts/chat/core.md"])
        self.assertIn("behavior", result["offline_checks"])
        self.assertEqual(18, len(result["selection"]["case_ids"]))
        self.assertFalse(result["live_execution_automatic"])
        self.assertEqual([], analyse([prefix + "README.md", "docs/design.md"])["offline_checks"])

    def test_memory_skill_and_delegation_routing(self):
        path = "backend-java/skillforge-prompts/src/main/resources/com/skillforge/prompts/"
        memory = analyse([path + "system/memory-extraction.md"])
        self.assertIn("memory", memory["offline_checks"])
        self.assertEqual("memory", memory["recommendations"][0]["suite"])
        self.assertEqual(["skill"], analyse(["tools/skills/evaluation/main.py"])["offline_checks"])
        self.assertEqual(["D11"], analyse([path + "delegation/task.md"])["recommendations"][0]["case_ids"])

    def test_workflow_order_reincludes_executable_markdown_for_push_and_pr(self):
        repo = Path(__file__).resolve().parents[2]
        prompt = "backend-java/skillforge-prompts/src/main/resources/com/skillforge/prompts/chat/core.md"
        for workflow in ("java-ci.yml", "runtime-e2e.yml", "agent-behavior-eval.yml"):
            text = (repo / ".github/workflows" / workflow).read_text(encoding="utf-8")
            # Match the ordered literal path filters used by these three workflows.
            import re
            blocks = re.findall(r"    paths:\n((?:      - .+\n)+)", text)
            self.assertTrue(blocks, workflow)
            for block in blocks:
                patterns = [line.strip()[3:-1] for line in block.splitlines()]
                def included(path):
                    state = False
                    for pattern in patterns:
                        negative = pattern.startswith("!")
                        if fnmatch.fnmatchcase(path, pattern.lstrip("!")):
                            state = not negative
                    return state
                self.assertTrue(included(prompt), workflow)
                self.assertFalse(included("backend-java/skillforge-prompts/README.md"), workflow)
