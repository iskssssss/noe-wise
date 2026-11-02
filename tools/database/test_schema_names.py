"""表名重写的边界验证：SQL、动态表名与业务值必须分别处理。"""

from pathlib import Path
import unittest

from schema_names import bounded_identifier, load_mapping, object_target, rewrite, sql, validate_declarations
import schema_names
from tempfile import TemporaryDirectory
from unittest.mock import patch


class SchemaNamesTest(unittest.TestCase):
    def setUp(self):
        self.names = {"agents": "skillforge_agents", "sessions": "skillforge_chat_sessions",
                      "runtime_budget_roots": "agent_runtime_budget_roots", "agents_pkey": "skillforge_agents_pkey"}
        self.objects = {"agents_pkey"}

    def test_rewrites_sql_positions_and_preserves_fields_and_values(self):
        source = "INSERT INTO agents(id,agents) VALUES(1,'agents'); SELECT agents.id, 'sessions', data->>'agents' FROM agents JOIN sessions s ON s.id=agents.id;"
        actual = sql(source, self.names, self.objects)
        self.assertIn("INSERT INTO skillforge_agents(id,agents) VALUES(1,'agents')", actual)
        self.assertIn("SELECT skillforge_agents.id, 'sessions', data->>'agents' FROM skillforge_agents JOIN skillforge_chat_sessions", actual)

    def test_rewrites_qualified_comments_foreign_keys_and_object_names(self):
        source = 'CREATE TABLE public.agents(id bigint CONSTRAINT agents_pkey PRIMARY KEY, owner_id bigint REFERENCES public.sessions(id)); COMMENT ON COLUMN public.agents.id IS \'agents\';'
        actual = sql(source, self.names, self.objects)
        self.assertIn("CONSTRAINT skillforge_agents_pkey", actual)
        self.assertIn("REFERENCES public.skillforge_chat_sessions", actual)
        self.assertIn("COLUMN public.skillforge_agents.id IS 'agents'", actual)

    def test_rewrites_dollar_quoted_dynamic_sql_and_table_metadata(self):
        source = "DO $$ BEGIN IF TG_TABLE_NAME='agents' THEN EXECUTE 'SELECT id FROM public.agents'; END IF; END $$;"
        self.assertEqual("DO $$ BEGIN IF TG_TABLE_NAME='skillforge_agents' THEN EXECUTE 'SELECT id FROM public.skillforge_agents'; END IF; END $$;", sql(source, self.names))

    def test_rewrites_catalog_arrays_and_regclass(self):
        source = "SELECT 1 FROM information_schema.tables WHERE table_name IN ('agents','sessions'); SELECT to_regclass('public.agents'); DO $$ BEGIN FOREACH table_name IN ARRAY ARRAY['agents','sessions'] LOOP END LOOP; END $$;"
        actual = sql(source, self.names)
        self.assertIn("IN ('skillforge_agents','skillforge_chat_sessions')", actual)
        self.assertIn("to_regclass('public.skillforge_agents')", actual)
        self.assertIn("ARRAY['skillforge_agents','skillforge_chat_sessions']", actual)

    def test_java_table_name_and_sql_leave_json_routes_and_variables(self):
        source = '@TableName("agents") class A { String route="agents"; String json="{\\"agents\\":[]}"; String query="SELECT id FROM agents"; Object sessions; }'
        actual = rewrite(Path("A.java"), source, self.names)
        self.assertIn('@TableName("skillforge_agents")', actual)
        self.assertIn('String query="SELECT id FROM skillforge_agents"', actual)
        self.assertIn('String route="agents"; String json="{\\"agents\\":[]}"', actual)
        self.assertIn("Object sessions", actual)

    def test_xml_namespace_attributes_and_cdata(self):
        source = '<mapper namespace="sessions"><select id="agents" resultType="agents"><![CDATA[SELECT id FROM agents WHERE name=\'agents\']]></select></mapper>'
        actual = rewrite(Path("Mapper.xml"), source, self.names)
        self.assertIn('namespace="sessions"', actual)
        self.assertIn('id="agents" resultType="agents"', actual)
        self.assertIn("FROM skillforge_agents WHERE name='agents'", actual)

    def test_idempotence(self):
        first = sql("SELECT id FROM agents", self.names)
        self.assertEqual(first, sql(first, self.names))

    def test_preserves_explicit_table_aliases_and_cte_names(self):
        source = "SELECT sessions.id AS agents FROM sessions sessions WHERE sessions.id=1"
        self.assertEqual("SELECT sessions.id AS agents FROM skillforge_chat_sessions sessions WHERE sessions.id=1", sql(source, self.names))
        cte = "WITH agents AS (SELECT id FROM public.agents) SELECT agents.id FROM agents"
        self.assertEqual("WITH agents AS (SELECT id FROM public.skillforge_agents) SELECT agents.id FROM agents", sql(cte, self.names))
        self.assertEqual("WITH agents AS (SELECT 1) SELECT id FROM agents; SELECT id FROM skillforge_agents;", sql("WITH agents AS (SELECT 1) SELECT id FROM agents; SELECT id FROM agents;", self.names))
        self.assertEqual("SELECT id FROM skillforge_chat_sessions AS agents; SELECT skillforge_agents.id FROM skillforge_agents;", sql("SELECT id FROM sessions AS agents; SELECT agents.id FROM agents;", self.names))

    def test_relation_name_array_is_a_dynamic_table_list(self):
        source = "DO $$ BEGIN FOREACH relation_name IN ARRAY ARRAY['agents','sessions'] LOOP EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',relation_name); END LOOP; END $$;"
        self.assertIn("ARRAY['skillforge_agents','skillforge_chat_sessions']", sql(source, self.names))

    def test_table_helper_changes_only_the_table_parameter(self):
        source = 'class A { void find(String table,String value){} void test(){find("agents","agents");} }'
        self.assertEqual('class A { void find(String table,String value){} void test(){find("skillforge_agents","agents");} }', rewrite(Path("A.java"), source, self.names))
        self.assertEqual('table.equals("skillforge_agents"); feature.equals("agents");', rewrite(Path("A.java"), 'table.equals("agents"); feature.equals("agents");', self.names))
        self.assertEqual('for(String table:java.util.List.of("skillforge_agents")){}',rewrite(Path("A.java"),'for(String table:java.util.List.of("agents")){}',self.names))

    def test_create_table_like_source_and_business_like_values(self):
        source = "CREATE TABLE revisions (LIKE agents INCLUDING DEFAULTS); SELECT id FROM agents WHERE name LIKE 'agents';"
        self.assertEqual("CREATE TABLE revisions (LIKE skillforge_agents INCLUDING DEFAULTS); SELECT id FROM skillforge_agents WHERE name LIKE 'agents';", sql(source, self.names))

    def test_dynamic_table_fragment_composite_types_and_relation_lists(self):
        xml = '<mapper><sql id="accountTable"><choose><when test="platform">agents</when><otherwise>sessions</otherwise></choose></sql></mapper>'
        self.assertIn('>skillforge_agents</when><otherwise>skillforge_chat_sessions<', rewrite(Path("Mapper.xml"), xml, self.names))
        block = "DO $$ DECLARE first agents; second sessions; BEGIN DELETE FROM agents a USING sessions s,agents b WHERE a.id=s.id; END $$;"
        actual = sql(block, self.names)
        self.assertIn("DECLARE first skillforge_agents; second skillforge_chat_sessions;", actual)
        self.assertIn("USING skillforge_chat_sessions s,skillforge_agents b", actual)

    def test_grant_comma_tables_and_concatenated_java_literals(self):
        source = 'execute("GRANT SELECT ON agents," + "sessions,runtime_budget_roots TO test_role");'
        self.assertEqual('execute("GRANT SELECT ON skillforge_agents," + "skillforge_chat_sessions,agent_runtime_budget_roots TO test_role");', rewrite(Path("A.java"), source, self.names))

    def test_comment_on_constraint_and_implicit_index(self):
        source = "COMMENT ON CONSTRAINT check_owner ON agents IS 'agents'; COMMENT ON INDEX agents_pkey IS 'primary';"
        self.assertEqual("COMMENT ON CONSTRAINT check_owner ON skillforge_agents IS 'agents'; COMMENT ON INDEX skillforge_agents_pkey IS 'primary';", sql(source, self.names, self.objects))
        self.assertEqual("ALTER INDEX public.skillforge_agents_pkey RENAME TO skillforge_agents_pkey", sql("ALTER INDEX public.agents_pkey RENAME TO agents_pkey", self.names, self.objects))

    def test_truncate_tables_after_dynamic_join(self):
        source = 'execute("TRUNCATE " + String.join(",",tables) + ",agents,sessions CASCADE");'
        self.assertEqual('execute("TRUNCATE " + String.join(",",tables) + ",skillforge_agents,skillforge_chat_sessions CASCADE");', rewrite(Path("A.java"), source, self.names))

    def test_long_identifiers_are_stable_and_do_not_collide(self):
        first = "skillforge_" + "long_relation_name_" * 5 + "first"
        second = "skillforge_" + "long_relation_name_" * 5 + "second"
        self.assertEqual(63, len(bounded_identifier(first)))
        self.assertEqual(bounded_identifier(first), bounded_identifier(first))
        self.assertNotEqual(bounded_identifier(first), bounded_identifier(second))

    def test_object_owner_prefix_does_not_rewrite_sdk_semantics_or_field_suffix(self):
        names = {**self.names, "plans": "skillforge_billing_plans", "agent_runtime_execution_sessions": "agent_runtime_execution_sessions"}
        self.assertEqual("execution_sessions_pk", object_target("execution_sessions_pk", names))
        self.assertEqual("skillforge_billing_plans_quota_max_agents_check", object_target("plans_quota_max_agents_check", names))
        self.assertEqual("agent_runtime_execution_sessions_pk", object_target("agent_runtime_execution_sessions_pk", names))

    def test_composite_types_preserve_explicit_schema(self):
        self.assertEqual("DO $$ DECLARE row public.skillforge_agents; BEGIN END $$;", sql("DO $$ DECLARE row public.agents; BEGIN END $$;", self.names))

    def test_previous_platform_table_target_changes_without_renaming_explicit_objects(self):
        names = {"agents":"nw_agents", "skillforge_agents":"nw_agents", "agents_pkey":"skillforge_agents_pkey", "skillforge_runtime_%":"nw_runtime_%"}
        source = "CREATE TABLE skillforge_agents(id bigint CONSTRAINT skillforge_agents_pkey PRIMARY KEY); COMMENT ON CONSTRAINT skillforge_agents_pkey ON skillforge_agents IS 'skillforge_agents';"
        self.assertEqual("CREATE TABLE nw_agents(id bigint CONSTRAINT skillforge_agents_pkey PRIMARY KEY); COMMENT ON CONSTRAINT skillforge_agents_pkey ON nw_agents IS 'skillforge_agents';", sql(source,names,{"agents_pkey"}))
        java = '@TableName("skillforge_agents") class Agent {String module="skillforge-core-agent"; String environment="SKILLFORGE_DB_URL"; String feature="skillforge_agents";}'
        self.assertEqual('@TableName("nw_agents") class Agent {String module="skillforge-core-agent"; String environment="SKILLFORGE_DB_URL"; String feature="skillforge_agents";}',rewrite(Path("Agent.java"),java,names,{"agents_pkey"}))
        self.assertIn("relname LIKE 'nw_runtime_%'",sql("SELECT relname FROM pg_class WHERE relname LIKE 'skillforge_runtime_%'",names))

    def test_controlled_manifest_retains_source_and_previous_table_targets(self):
        data,names,_ = load_mapping()
        for row in data["tables"]:
            if row["owner"] == "platform":
                self.assertTrue(row["target"].startswith("nw_"))
                # 首次以当前命名空间创建的表没有历史别名，不能为满足清单虚构旧名。
                if row["source"] == row["target"]:
                    self.assertEqual([], row["previous_targets"])
                    self.assertNotIn(row["source"], names)
                else:
                    self.assertTrue(row["previous_targets"])
                for previous in row["previous_targets"]:
                    self.assertEqual(row["target"],names[previous])
        self.assertEqual("skillforge_agents_pkey",names["agents_pkey"])

    def test_initial_implicit_reference_does_not_rename_later_explicit_constraint(self):
        rule={"path":"migration.sql","operation":"drop_constraint","source":"skillforge_agents_pkey","target":"nw_agents_pkey"}
        source="ALTER TABLE nw_agents DROP CONSTRAINT skillforge_agents_pkey, ADD CONSTRAINT skillforge_agents_pkey PRIMARY KEY(id);"
        with patch.object(schema_names,"SCOPED_OBJECT_REFERENCES",[rule]):
            actual=rewrite(Path("migration.sql"),source,{"skillforge_agents":"nw_agents"})
            self.assertEqual("ALTER TABLE nw_agents DROP CONSTRAINT nw_agents_pkey, ADD CONSTRAINT skillforge_agents_pkey PRIMARY KEY(id);",actual)
            self.assertEqual(source,rewrite(Path("later.sql"),source,{"skillforge_agents":"nw_agents"}))

    def test_minimal_mcp_fixtures_only_drop_the_initial_implicit_protocol_constraint(self):
        load_mapping()
        source='sql("ALTER TABLE nw_runtime_mcp_bindings DROP CONSTRAINT skillforge_runtime_mcp_bindings_protocol_version_check"); sql("ALTER TABLE nw_runtime_mcp_bindings ADD CONSTRAINT skillforge_runtime_mcp_bindings_protocol_version_check CHECK(protocol_version=\'2026-07-28\')");'
        expected=source.replace('DROP CONSTRAINT skillforge_runtime_mcp_bindings_protocol_version_check','DROP CONSTRAINT nw_runtime_mcp_bindings_protocol_version_check')
        for file in ['backend-java/skillforge-core-capability/src/test/java/com/skillforge/runtime/adapter/mcp/McpToolPortalTest.java','backend-java/skillforge-core-capability/src/test/java/com/skillforge/core/capability/integration/runtime/tool/JdbcMcpBindingsTest.java']:
            self.assertEqual(expected,rewrite(Path(file),source,{}))

    def test_postgresql_unquoted_names_fold_to_lowercase_but_quoted_names_are_exact(self):
        names={"skillforge_agents":"nw_agents"}
        self.assertEqual("SELECT id FROM nw_agents; SELECT id FROM public.nw_agents;",sql("SELECT id FROM SKILLFORGE_AGENTS; SELECT id FROM public.SkillForge_Agents;",names))
        self.assertEqual('SELECT id FROM "SKILLFORGE_AGENTS"; SELECT id FROM "nw_agents";',sql('SELECT id FROM "SKILLFORGE_AGENTS"; SELECT id FROM "skillforge_agents";',names))

    def test_alias_and_cte_identity_follow_postgresql_casefold_and_quotes(self):
        names={"skillforge_agents":"nw_agents"}
        for source in ["SELECT skillforge_agents.id FROM nw_agents AS SKILLFORGE_AGENTS",'SELECT "SKILLFORGE_AGENTS".id FROM nw_agents AS "SKILLFORGE_AGENTS"','SELECT skillforge_agents.id FROM nw_agents AS "skillforge_agents"',"WITH SKILLFORGE_AGENTS AS (SELECT 1 AS id) SELECT skillforge_agents.id FROM skillforge_agents",'WITH "SKILLFORGE_AGENTS" AS (SELECT 1 AS id) SELECT "SKILLFORGE_AGENTS".id FROM "SKILLFORGE_AGENTS"']:
            self.assertEqual(source,sql(source,names))

    def test_declaration_gate_rejects_unknown_table_without_rewriter(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("platform", "sdk", "sqlite"):
                (root / name).mkdir()
            (root / "platform/new.sql").write_text("CREATE TABLE missed_business_table(id bigint);", encoding="utf-8")
            with patch.object(schema_names, "ROOT", root), patch.object(schema_names, "MIGRATIONS", "platform"), patch.object(schema_names, "SDK_SCHEMA", "sdk"), patch.object(schema_names, "SQLITE_SCHEMA", "sqlite"):
                failures = validate_declarations({"tables": [], "metadata_exceptions": []})
            self.assertEqual(1, len(failures))
            self.assertIn("missed_business_table", failures[0])


if __name__ == "__main__":
    unittest.main()
