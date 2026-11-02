"""控件盘点提取器回归：嵌套插槽之后的控件不可被遗漏。"""
import unittest
from unittest.mock import patch
from generate_platform_ui_control_inventory import template_region, TemplateObjects, label_for, route_records, refresh_pages
from sync_ui_acceptance_index import synchronize_rules


class TemplateRegionTests(unittest.TestCase):
    def test_nested_slots_keep_late_fields_and_drawer_footer(self):
        template = '<header><template #actions><button>新建</button></template></header><UiInput/><UiDrawer><template #footer><UiButton>保存</UiButton></template></UiDrawer>'
        region, line = template_region('<script>const a = 1;</script>\n<template>' + template + '</template>\n<style/>')
        self.assertEqual(region, template)
        self.assertEqual(line, 2)

    def test_condition_with_comparison_does_not_break_nesting(self):
        template = '<template v-if="items.length > 0"><p>记录</p></template><UiButton>查询</UiButton>'
        self.assertEqual(template_region('<template>' + template + '</template>')[0], template)

    def test_ancestor_conditions_and_self_closing_siblings(self):
        nodes = TemplateObjects('<section v-if="enabled"><UiInput/><span>文字</span></section><UiIcon :icon="icon"/>').nodes
        self.assertEqual(next(node for node in nodes if node['tag'] == 'UiInput')['conditions'], ['v-if=enabled'])
        self.assertEqual(next(node for node in nodes if node['tag'] == '#text')['conditions'], ['v-if=enabled'])
        self.assertEqual(nodes[-1]['conditions'], [])

    def test_next_control_label_is_not_assigned_to_previous_input(self):
        self.assertEqual(label_for('UiInput', {}, '<UiButton>{{ t("save") }}</UiButton>'), ('', 'unbound'))


class AcceptanceRuleSourceTests(unittest.TestCase):
    def test_unmapped_business_route_is_not_reported_as_shared_shell(self):
        router = """[
          { path: '/platform', component: () => import('../views/platform/Layout.vue') },
          { path: 'new-business', component: () => import('../views/platform/NewBusinessPage.vue') }
        ]"""
        with patch('generate_platform_ui_control_inventory.read', return_value=router):
            records = route_records()
        self.assertEqual(records[0]['page_id'], 'shared-shell')
        self.assertEqual(records[1]['page_id'], 'unmapped-page')
        self.assertEqual(records[1]['component'], 'frontend-platform/src/views/platform/NewBusinessPage.vue')

    def test_current_rule_text_changes_without_promoting_historical_evidence(self):
        evidence = [{"kind": "automated_partial", "observed": "旧版本局部场景"}]
        record = {
            "id": "A12", "origin": "original_numbered_rule", "requirement": "旧滚动规则",
            "implementation_status": "not_assessed", "verification_status": "not_executed",
            "evidence": evidence,
        }
        synchronize_rules({"records": [record]}, "- A12 页面滚动：内容区滚动。", "")
        self.assertEqual(record["requirement"], "页面滚动：内容区滚动。")
        self.assertEqual(record["source"], "../03-规范与术语/05-后台系统交互规范.md")
        self.assertEqual(record["implementation_status"], "not_assessed")
        self.assertEqual(record["verification_status"], "not_executed")
        self.assertIs(record["evidence"], evidence)

    def test_refinement_uses_its_actual_source_and_keeps_unmatched_history(self):
        current = {"id": "D37", "origin": "new_refinement", "object": "旧标签",
                   "requirement": "旧合同", "acceptance": "旧断言"}
        historical = {"id": "D01", "origin": "original_numbered_rule", "requirement": "历史规则"}
        synchronize_rules({"records": [current, historical]}, "",
                          "| D37 | 历史标签（不适用） | 原规则仅供追溯。 | 不纳入当前验收。 |")
        self.assertEqual(current["object"], "历史标签（不适用）")
        self.assertEqual(current["requirement"], "原规则仅供追溯。")
        self.assertEqual(current["acceptance"], "不纳入当前验收。")
        self.assertEqual(current["source"], "../03-规范与术语/06-后台逐控件规格.md")
        self.assertEqual(historical["requirement"], "历史规则")


class PageRefreshTests(unittest.TestCase):
    def test_refresh_preserves_other_pages_and_replaces_private_components(self):
        def control(page, source, line):
            return {"object_id": source, "page_id": page, "source_component": source,
                    "source_line": line, "control_type": "display_text", "gaps": [],
                    "binding_definition_ref": "template-display-node", "consumed_by": []}

        def consumption(consumer, source, line):
            return {"consumer": consumer, "source": source, "component": source, "source_line": line}

        def inventory(line, private):
            data = {"controls": [control("P01", "other.vue", line), control("P05", "billing.vue", line),
                                 control("shared-component", private, line),
                                 control("shared-component", "shared.vue", line)],
                    "routes": [{"page_id": "P01", "path": "/other", "component": "other.vue", "source_line": line},
                               {"page_id": "P05", "path": "/billing", "component": "billing.vue", "source_line": line}],
                    "component_consumptions": [consumption("other.vue", "shared.vue", line),
                                               consumption("billing.vue", "shared.vue", line),
                                               consumption("billing.vue", private, line)],
                    "source_files": sorted(["other.vue", "billing.vue", "shared.vue", private]),
                    "shared_component_bindings": {}, "summary": {"page_source_file_count": 2}}
            for item in data["controls"]:
                item["consumed_by"] = sorted(row["consumer"] for row in data["component_consumptions"]
                                             if row["source"] == item["source_component"])
            return data

        current, fresh = inventory(10, "invoice.vue"), inventory(90, "costs.vue")
        result = refresh_pages(current, fresh, {"P05"})
        self.assertEqual(result["controls"][0], current["controls"][0])
        self.assertIn(current["controls"][3], result["controls"])
        self.assertEqual(result["routes"][0], current["routes"][0])
        self.assertIn(current["component_consumptions"][0], result["component_consumptions"])
        self.assertNotIn("invoice.vue", result["source_files"])
        self.assertIn("costs.vue", result["source_files"])
        self.assertEqual(result["summary"]["control_count"], 4)
        self.assertEqual(refresh_pages(result, fresh, {"P05"}), result)


if __name__ == '__main__':
    unittest.main()
