import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools/open_platform_contract.py"
CONTRACT_PATH = ROOT / "backend-java/skillforge-institution-service/src/main/resources/openapi/open-platform-v1.yaml"

spec = importlib.util.spec_from_file_location("open_platform_contract", MODULE_PATH)
contract = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(contract)


class OpenPlatformContractTest(unittest.TestCase):
    def setUp(self):
        self.document = contract.load_document(CONTRACT_PATH)

    def test_checked_in_contract_is_valid(self):
        self.assertEqual([], contract.validate_contract(self.document))
        self.assertEqual(80, len(contract.extract_operations(self.document)))

    def test_binary_upload_allows_only_signed_fixed_metadata_queries(self):
        path = "/open-platform/sessions/attachments"
        operation = self.document["paths"][path]["post"]
        self.assertEqual({"application/octet-stream"}, set(operation["requestBody"]["content"]))
        operation["parameters"].append({"in": "query", "name": "operator_ref", "required": True,
                                        "schema": {"type": "string"}})
        self.assertTrue(any("request body" in error for error in contract.validate_contract(self.document)))

    def test_json_writes_do_not_inherit_binary_upload_query_exception(self):
        operation = self.document["paths"]["/open-platform/sessions/attachments"]["post"]
        operation["requestBody"]["content"] = {"application/json": {"schema": {"type": "object"}}}
        self.assertTrue(any("request body" in error for error in contract.validate_contract(self.document)))

    def test_group_identity_has_seven_operations_and_four_required_command_keys(self):
        """Group metadata never requires a fake EndUser or exposes group execution routes."""
        expected = {
            "/open-platform/groups/ensure": ("post", "groups.write"),
            "/open-platform/groups/close": ("post", "groups.write"),
            "/open-platform/groups/detail": ("get", "groups.read"),
            "/open-platform/groups/members": ("get", "groups.read"),
            "/open-platform/groups/agents": ("get", "groups.read"),
            "/open-platform/groups/members/update": ("post", "groups.members.write"),
            "/open-platform/groups/agents/update": ("post", "groups.agents.write"),
        }
        self.assertTrue(set(expected).issubset(self.document["paths"]))
        for path, (method, scope) in expected.items():
            operation = self.document["paths"][path][method]
            self.assertEqual(scope, operation["x-required-scope"])
            if method == "post":
                invalid = copy.deepcopy(self.document)
                invalid["paths"][path][method]["parameters"] = []
                self.assertTrue(any(path in error and "must require Idempotency-Key" in error
                                    for error in contract.validate_contract(invalid)))
        for route, response in (("members", "ApiResponseGroupMembersPage"), ("agents", "ApiResponseGroupAgentsPage")):
            path = "/open-platform/groups/" + route
            operation = self.document["paths"][path]["get"]
            self.assertNotIn("requestBody", operation)
            self.assertEqual("#/components/schemas/" + response,
                             operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"])
            parameters = {item["name"]: item for item in operation["parameters"]}
            self.assertEqual({"group_binding_id", "after_id", "limit"}, set(parameters))
            self.assertTrue(all(item["in"] == "query" for item in parameters.values()))
            self.assertTrue(parameters["group_binding_id"]["required"])
            self.assertFalse(parameters["after_id"]["required"])
            self.assertFalse(parameters["limit"]["required"])
            self.assertEqual({"type": "integer", "minimum": 1, "maximum": 200, "default": 100}, parameters["limit"]["schema"])
            runtime = copy.deepcopy(self.document)
            runtime["paths"][path]["get"]["x-required-scope"] = "groups.write"
            self.assertTrue(any("scope mismatch" in error and path in error
                                for error in contract.diff_documents(self.document, runtime)))
        schemas = self.document["components"]["schemas"]
        self.assertEqual({"namespace", "external_group_ref", "lifecycle_ref", "business_scope_id"}, set(schemas["GroupEnsure"]["required"]))
        self.assertEqual({"kind", "group_binding_id"}, set(schemas["GroupSessionSubject"]["required"]))
        self.assertEqual({"kind", "end_user_id"}, set(schemas["UserSessionSubject"]["required"]))
        self.assertEqual("#/components/schemas/UserSessionSubject", schemas["Session"]["properties"]["subject"]["$ref"])
        self.assertEqual("#/components/schemas/GroupSessionSubject", schemas["GroupView"]["properties"]["subject"]["$ref"])
        self.assertFalse({"end_user_id", "agent_id", "members", "agents"} & set(schemas["GroupView"]["properties"]))

    def test_group_updates_preserve_exact_identity_object_cas_and_source_revision(self):
        """Client contracts retain source ordering and distinguish first projection from close CAS."""
        schemas = self.document["components"]["schemas"]
        self.assertEqual(1, schemas["GroupClose"]["properties"]["expected_revision"]["minimum"])
        for name, identifier, states in (("GroupUpdateMember", "end_user_id", ["active", "left"]),
                                          ("GroupUpdateAgent", "agent_id", ["active", "removed"])):
            properties = schemas[name]["properties"]
            self.assertEqual("string", properties[identifier]["type"])
            self.assertEqual(0, properties["expected_revision"]["minimum"])
            self.assertEqual(1, properties["source_revision"]["minimum"])
            self.assertEqual(states, properties["status"]["enum"])
            self.assertIn("source_event_id", schemas[name]["required"])
        self.assertEqual(["member", "manager"], schemas["GroupUpdateMember"]["properties"]["role"]["enum"])
        for field, replacement in (("end_user_id", {"type": "integer"}),
                                   ("end_user_id", {"type": ["integer", "string"]}),
                                   ("source_revision", {"type": "string"})):
            runtime = copy.deepcopy(self.document)
            runtime["components"]["schemas"]["GroupUpdateMember"]["properties"][field] = replacement
            self.assertTrue(any("request schema mismatch" in error and "/groups/members/update" in error
                                for error in contract.diff_documents(self.document, runtime)))
        for name, field, path in (("GroupMemberView", "end_user_id", "/groups/members/update"),
                                   ("GroupAgentView", "agent_id", "/groups/agents/update")):
            runtime = copy.deepcopy(self.document)
            runtime["components"]["schemas"][name]["properties"][field]["type"] = ["integer", "string"]
            self.assertTrue(any("response schema mismatch" in error and path in error
                                for error in contract.diff_documents(self.document, runtime)))

    def test_group_record_trigger_and_wait_stay_distinct_from_public_control_surfaces(self):
        """Source and Run operations retain their own authorization beside GI-04 reads and controls."""
        group_routes = {path for path in self.document["paths"] if path.startswith("/open-platform/groups/")}
        identity = {"ensure", "close", "detail", "members", "agents", "members/update", "agents/update"}
        incoming = {"messages", "triggers", "triggers/detail", "runs/detail", "runs/respond", "runs/commands"}
        controls = {"history", "events", "stream", "controls", "controls/authorize", "controls/detail",
                    "controls/current", "controls/terminalize", "controls/release", "sender-endpoints/register",
                    "sender-endpoints/retire", "sender-endpoints/ack", "deliveries/qualify"}
        memories = {"memories", "memories/create", "memories/review", "memories/correct", "memories/forget", "memories/restore", "memories/extraction-policy"}
        self.assertEqual({"/open-platform/groups/" + route for route in identity | incoming | controls | memories}, group_routes)
        for route, method, scope in (("messages", "post", "groups.messages.write"),
                                      ("triggers", "post", "groups.triggers.write"),
                                      ("triggers/detail", "get", "groups.triggers.read"),
                                      ("runs/detail", "get", "groups.runs.read"),
                                      ("runs/respond", "post", "groups.runs.respond"),
                                      ("runs/commands", "post", "groups.runs.control")):
            path = "/open-platform/groups/" + route
            operation = self.document["paths"][path][method]
            self.assertEqual(scope, operation["x-required-scope"])
            if method == "post":
                invalid = copy.deepcopy(self.document)
                invalid["paths"][path][method]["parameters"] = []
                self.assertTrue(any(path in error and "must require Idempotency-Key" in error
                                    for error in contract.validate_contract(invalid)))
        self.assertIn("202", self.document["paths"]["/open-platform/groups/triggers"]["post"]["responses"])
        for route, fields in (("triggers/detail", {"group_binding_id", "trigger_id"}),
                               ("runs/detail", {"group_binding_id", "run_id"})):
            parameters = self.document["paths"]["/open-platform/groups/" + route]["get"]["parameters"]
            self.assertEqual(fields, {parameter["name"] for parameter in parameters})
            self.assertTrue(all(parameter["required"] and parameter["in"] == "query" for parameter in parameters))

    def test_group_extraction_policy_is_explicit_and_uses_revision_cas(self):
        path = "/open-platform/groups/memories/extraction-policy"
        read = self.document["paths"][path]["get"]
        write = self.document["paths"][path]["put"]
        self.assertEqual("groups.memory.read", read["x-required-scope"])
        self.assertEqual("groups.memory.write", write["x-required-scope"])
        self.assertEqual({"group_binding_id", "operator_end_user_id"}, {item["name"] for item in read["parameters"]})
        self.assertTrue(all(item["required"] and item["schema"]["type"] == "string" for item in read["parameters"]))
        schemas = self.document["components"]["schemas"]
        command = schemas["GroupMemoryExtractionPolicyCommand"]
        self.assertEqual(set(command["properties"]), set(command["required"]))
        self.assertEqual(0, command["properties"]["expected_revision"]["minimum"])
        self.assertEqual("boolean", command["properties"]["enabled"]["type"])
        self.assertEqual("string", command["properties"]["extractor_agent_id"]["type"])
        view = schemas["GroupMemoryExtractionPolicyView"]
        self.assertEqual(set(view["properties"]), set(view["required"]))
        self.assertEqual(["string", "null"], view["properties"]["extractor_agent_id"]["type"])
        self.assertIn("409", write["responses"])
        self.assertIn("422", write["responses"])

    def test_group_memory_governance_retains_sources_subjects_and_original_command_identity(self):
        base = "/open-platform/groups/memories"
        operation = self.document["paths"][base]["get"]
        self.assertEqual("groups.memory.read", operation["x-required-scope"])
        parameters = {item["name"]: item for item in operation["parameters"]}
        self.assertEqual({"group_binding_id", "reader_end_user_id", "agent_id"},
                         {name for name, value in parameters.items() if value["required"]})
        self.assertEqual(200, parameters["limit"]["schema"]["maximum"])
        for action in ("create", "review", "correct", "forget", "restore"):
            path = base + "/" + action
            self.assertEqual("groups.memory.write", self.document["paths"][path]["post"]["x-required-scope"])
            invalid = copy.deepcopy(self.document)
            invalid["paths"][path]["post"]["parameters"] = []
            self.assertTrue(any(path in error and "must require Idempotency-Key" in error
                                for error in contract.validate_contract(invalid)))
        schemas = self.document["components"]["schemas"]
        create = schemas["GroupMemoryCreate"]
        self.assertIn("source_message_ids", create["required"])
        self.assertNotIn("source_event_ids", create["properties"])
        self.assertEqual(["GROUP", "GROUP_MEMBER"], create["properties"]["scope"]["enum"])
        self.assertEqual(["string", "null"], create["properties"]["subject_end_user_id"]["type"])
        for name in ("GroupMemoryReview", "GroupMemoryCorrect", "GroupMemoryRevision"):
            self.assertIn("expected_revision", schemas[name]["required"])
            self.assertEqual(1, schemas[name]["properties"]["expected_revision"]["minimum"])
        self.assertIn("approved", schemas["GroupMemoryReview"]["required"])
        fact = schemas["GroupMemoryFact"]
        self.assertEqual(set(fact["properties"]), set(fact["required"]))
        self.assertEqual(["string", "null"], fact["properties"]["subject_end_user_id"]["type"])
        self.assertTrue({"source_message_ids", "source_event_ids", "derived_from", "superseded_by", "status"}
                        <= set(fact["properties"]))
        self.assertFalse({"owner", "end_user_id", "agent_id"} & set(fact["properties"]))

    def test_group_public_read_cursors_bind_the_explicit_reader_and_keep_sse_raw(self):
        for route in ("history", "events", "stream"):
            operation = self.document["paths"]["/open-platform/groups/" + route]["get"]
            self.assertEqual("groups.history.read", operation["x-required-scope"])
            parameters = {item["name"]: item for item in operation["parameters"]}
            expected = {"group_binding_id", "reader_end_user_id", "cursor"}
            expected.add("Last-Event-ID" if route == "stream" else "page_size")
            self.assertEqual(expected, set(parameters))
            self.assertTrue(parameters["reader_end_user_id"]["required"])
            self.assertEqual("string", parameters["reader_end_user_id"]["schema"]["type"])
            if route == "stream":
                self.assertEqual("header", parameters["Last-Event-ID"]["in"])
                self.assertEqual({"text/event-stream"}, set(operation["responses"]["200"]["content"]))
            else:
                self.assertEqual(200, parameters["page_size"]["schema"]["maximum"])
                self.assertEqual(20, parameters["page_size"]["schema"]["default"])
        schemas = self.document["components"]["schemas"]
        for page, item in (("GroupHistoryPage", "ChatMessageRecord"), ("GroupEventsPage", "SessionOutboxEvent")):
            schema = schemas[page]
            self.assertEqual(set(schema["properties"]), set(schema["required"]))
            self.assertEqual({"$ref": "#/components/schemas/" + item}, schema["properties"]["items"]["items"])
            self.assertEqual(["string", "null"], schema["properties"]["next_cursor"]["type"])
        self.assertEqual(["string", "null"], schemas["GroupHistoryPage"]["properties"]["event_cursor"]["type"])

    def test_group_controls_do_not_conflate_acceptance_application_or_exact_delivery_release(self):
        routes = {
            "controls": ("post", "groups.controls.write"),
            "controls/authorize": ("post", "groups.controls.read"),
            "controls/detail": ("get", "groups.controls.read"),
            "controls/current": ("get", "groups.controls.read"),
            "controls/terminalize": ("post", "groups.controls.reconcile"),
            "controls/release": ("post", "groups.controls.release"),
            "sender-endpoints/register": ("post", "groups.senders.manage"),
            "sender-endpoints/retire": ("post", "groups.senders.manage"),
            "sender-endpoints/ack": ("post", "groups.controls.ack"),
            "deliveries/qualify": ("post", "groups.controls.ack"),
        }
        for route, (method, scope) in routes.items():
            path = "/open-platform/groups/" + route
            operation = self.document["paths"][path][method]
            self.assertEqual(scope, operation["x-required-scope"])
            parameters = [contract.resolve_ref(self.document, item) for item in operation.get("parameters", [])]
            headers = {item["name"] for item in parameters if item["in"] == "header" and item["required"]}
            if method == "post" and route not in {"controls/authorize", "deliveries/qualify"}:
                self.assertIn("Idempotency-Key", headers)
                invalid = copy.deepcopy(self.document)
                invalid["paths"][path][method]["parameters"] = []
                self.assertTrue(any(path in error and "must require Idempotency-Key" in error
                                    for error in contract.validate_contract(invalid)))
            else:
                self.assertNotIn("Idempotency-Key", headers)
        schemas = self.document["components"]["schemas"]
        self.assertIn("202", self.document["paths"]["/open-platform/groups/controls"]["post"]["responses"])
        self.assertEqual(["paused", "running"], schemas["GroupControlCommand"]["properties"]["desired_state"]["enum"])
        self.assertEqual(1, schemas["GroupControlCommand"]["properties"]["command_id"]["minLength"])
        self.assertEqual(1, schemas["GroupControlCommand"]["properties"]["scopes"]["minItems"])
        self.assertEqual({"message_id", "message_version", "delivery_id"}, set(schemas["GroupReleaseMessage"]["required"]))
        self.assertIn("blocked_boundary_refs", schemas["GroupControlRelease"]["required"])
        receipt = schemas["GroupControlReceipt"]
        self.assertIn("TERMINAL_NOT_ACCEPTED", receipt["properties"]["status"]["enum"])
        self.assertTrue({"required_participants", "durable_acks", "in_flight", "result_kind"}.issubset(receipt["required"]))
        self.assertEqual(["integer", "null"], receipt["properties"]["through_trigger_sequence"]["type"])
        self.assertIn("expected_generation", schemas["GroupSenderRegister"]["required"])
        self.assertIn("in_flight", schemas["GroupSenderAck"]["required"])
        self.assertIn("in_flight", schemas["GroupSenderRetire"]["required"])
        self.assertEqual(0, schemas["GroupSenderAck"]["properties"]["in_flight"]["minItems"])
        self.assertEqual(0, schemas["GroupSenderRetire"]["properties"]["in_flight"]["minItems"])
        self.assertEqual(1, schemas["GroupControlRelease"]["properties"]["blocked_boundary_refs"]["items"]["minLength"])

    def test_sender_connection_is_unknown_independently_of_registration_and_nullable_last_confirmation(self):
        endpoint = self.document["components"]["schemas"]["GroupSenderEndpoint"]
        self.assertTrue({"last_confirmed_at", "connection_state"}.issubset(endpoint["required"]))
        self.assertEqual(["string", "null"], endpoint["properties"]["last_confirmed_at"]["type"])
        self.assertEqual("date-time", endpoint["properties"]["last_confirmed_at"]["format"])
        self.assertEqual(["UNKNOWN"], endpoint["properties"]["connection_state"]["enum"])
        for field, replacement in (("last_confirmed_at", {"type": "string", "format": "date-time"}),
                                   ("connection_state", {"type": "string", "enum": ["ACTIVE", "UNKNOWN"]})):
            runtime = copy.deepcopy(self.document)
            runtime["components"]["schemas"]["GroupSenderEndpoint"]["properties"][field] = replacement
            self.assertTrue(any("response schema mismatch" in error and "/groups/controls/current" in error
                                for error in contract.diff_documents(self.document, runtime)))

    def test_group_control_strict_diff_rejects_lost_cas_scope_boundaries_and_null_receipts(self):
        for schema, field, replacement, route in (
                ("GroupControlCommand", "command_id", {"type": "string", "minLength": 0, "maxLength": 128}, "/groups/controls"),
                ("GroupControlCommand", "expected_revision", {"type": "string"}, "/groups/controls"),
                ("GroupControlRelease", "blocked_boundary_refs", {"type": "string"}, "/groups/controls/release"),
                ("GroupControlReceipt", "through_trigger_sequence", {"type": "integer", "format": "int64"}, "/groups/controls"),
                ("GroupSenderRegister", "expected_generation", {"type": "string"}, "/groups/sender-endpoints/register")):
            runtime = copy.deepcopy(self.document)
            runtime["components"]["schemas"][schema]["properties"][field] = replacement
            self.assertTrue(any("schema mismatch" in error and route in error
                                for error in contract.diff_documents(self.document, runtime)), (schema, field))
        runtime = copy.deepcopy(self.document)
        runtime["paths"]["/open-platform/groups/sender-endpoints/ack"]["post"]["x-required-scope"] = "groups.senders.manage"
        self.assertTrue(any("scope mismatch" in error for error in contract.diff_documents(self.document, runtime)))

    def test_group_message_source_has_author_proof_and_never_fakes_a_run_receipt(self):
        schemas = self.document["components"]["schemas"]
        self.assertEqual({"group_binding_id", "session_id", "source_message_id", "source_sequence", "source_event_id", "source_revision"},
                         set(schemas["GroupMessageReceipt"]["properties"]))
        record = schemas["GroupMessageRecord"]
        self.assertEqual({"group_binding_id", "source_event_id", "source_revision", "author", "content"}, set(record["required"]))
        self.assertEqual(1, record["properties"]["content"]["minItems"])
        self.assertEqual(100, record["properties"]["content"]["maxItems"])
        self.assertEqual({"#/components/schemas/ChatContentImage", "#/components/schemas/ChatContentText"},
                         {branch["$ref"] for branch in record["properties"]["content"]["items"]["oneOf"]})
        for kind, name, fields in (("end_user", "EndUser", {"kind", "end_user_id"}),
                                  ("agent", "Agent", {"kind", "agent_id", "run_id", "source_ref"}),
                                  ("system", "System", {"kind", "source_ref"}),
                                  ("tool", "Tool", {"kind", "agent_id", "run_id", "source_ref"})):
            branch = contract._schema_shape(self.document, schemas["GroupMessage" + name + "Author"])
            self.assertEqual(fields, set(branch["required"]))
            self.assertEqual([kind], branch["properties"]["kind"]["enum"])
        self.assertNotIn("oneOf", schemas["GroupMessageAuthor"])
        self.assertEqual(4, len(record["properties"]["author"]["oneOf"]))

    def test_author_schema_normalization_accepts_acyclic_inheritance_and_rejects_recursive_union(self):
        """A common author base may be flattened, but must not recursively union its children."""
        flattened = copy.deepcopy(self.document)
        for name in ("EndUser", "Agent", "Tool", "System"):
            schema = "GroupMessage" + name + "Author"
            flattened["components"]["schemas"][schema] = contract._schema_shape(
                self.document, self.document["components"]["schemas"][schema])
        self.assertEqual([], contract.diff_documents(self.document, flattened))
        recursive = copy.deepcopy(self.document)
        recursive["components"]["schemas"]["GroupMessageAuthor"]["oneOf"] = copy.deepcopy(
            recursive["components"]["schemas"]["GroupMessageRecord"]["properties"]["author"]["oneOf"])
        self.assertTrue(any("request schema mismatch" in error and "/groups/messages" in error
                            for error in contract.diff_documents(self.document, recursive)))

    def test_author_kind_and_provenance_stay_enforced_without_field_discriminator_metadata(self):
        """Each oneOf branch remains closed by a required literal kind and its real source fields."""
        record = self.document["components"]["schemas"]["GroupMessageRecord"]
        self.assertNotIn("discriminator", record["properties"]["author"])
        self.assertEqual(4, len(record["properties"]["author"]["oneOf"]))
        for change in ("kind", "source_ref"):
            runtime = copy.deepcopy(self.document)
            branch = runtime["components"]["schemas"]["GroupMessageAgentAuthor"]
            if change == "kind":
                branch["allOf"][1]["properties"]["kind"]["enum"] = ["end_user"]
            else:
                branch["required"].remove("source_ref")
            self.assertTrue(any("request schema mismatch" in error and "/groups/messages" in error
                                for error in contract.diff_documents(self.document, runtime)), change)

    def test_chat_content_names_keep_input_text_separate_from_runtime_result_text(self):
        """Chat input and Runtime result records have different wire fields despite their Java short name."""
        schemas = self.document["components"]["schemas"]
        chat = contract._schema_shape(self.document, schemas["ChatContentText"])
        result = contract._schema_shape(self.document, schemas["Text"])
        self.assertEqual({"type", "text"}, set(chat["properties"]))
        self.assertIn("result_id", result["properties"])
        self.assertNotIn("type", result["properties"])
        self.assertEqual({"type", "media_id"}, set(contract._schema_shape(self.document, schemas["ChatContentImage"])["properties"]))
        polluted = copy.deepcopy(self.document)
        polluted["components"]["schemas"]["ChatContentText"]["allOf"].append({"$ref": "#/components/schemas/Result"})
        self.assertTrue(any("request schema mismatch" in error and "/groups/messages" in error
                            for error in contract.diff_documents(self.document, polluted)))

    def test_trigger_projection_and_waiting_reply_keep_original_typed_identity(self):
        schemas = self.document["components"]["schemas"]
        self.assertEqual({"group_binding_id", "source_message_id", "requester_end_user_id", "agent_id"}, set(schemas["GroupTrigger"]["required"]))
        for field in ("requester_end_user_id", "agent_id"):
            self.assertEqual("string", schemas["GroupTrigger"]["properties"][field]["type"])
        for field in ("input_id", "run_id", "rejection_code"):
            self.assertEqual(["string", "null"], schemas["GroupTriggerReceipt"]["properties"][field]["type"])
            self.assertIn(field, schemas["GroupTriggerReceipt"]["required"])
        self.assertEqual("string", schemas["GroupRunDetail"]["properties"]["revision"]["type"])
        self.assertEqual({"group_binding_id", "run_id", "input_id", "responder_end_user_id", "hitl_response"}, set(schemas["GroupRunRespond"]["required"]))
        self.assertEqual("object", schemas["GroupRunRespond"]["properties"]["hitl_response"]["type"])
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["GroupRunDetail"]["properties"]["revision"] = {"type": "integer"}
        self.assertTrue(any("response schema mismatch" in error and "/groups/runs/detail" in error
                            for error in contract.diff_documents(self.document, runtime)))

    def test_group_run_commands_target_original_run_and_reuse_safe_response(self):
        schemas = self.document["components"]["schemas"]
        command = schemas["GroupRunCommand"]
        self.assertEqual({"group_binding_id", "run_id", "input_id", "operator_end_user_id", "action"}, set(command["required"]))
        self.assertEqual(["cancel_execution", "steer"], command["properties"]["action"]["enum"])
        self.assertEqual(["string", "null"], command["properties"]["text"]["type"])
        self.assertNotIn("text", command["required"])
        self.assertNotIn("maxLength", command["properties"]["text"])
        self.assertEqual("string", command["properties"]["operator_end_user_id"]["type"])
        operation = self.document["paths"]["/open-platform/groups/runs/commands"]["post"]
        response = operation["responses"]["200"]["content"]["application/json"]["schema"]
        self.assertEqual("#/components/schemas/ApiResponseGroupRunResponse", response["$ref"])

    def test_authorization_switch_uses_fixed_identity_cas_and_required_command_key(self):
        """The public switch keeps exact IDs, deployment CAS and a durable command identity."""
        path = "/open-platform/agent-deployments/authorization"
        operation = self.document["paths"][path]["post"]
        self.assertEqual("resources.write", operation["x-required-scope"])
        schema = self.document["components"]["schemas"]["SwitchAuthorization"]
        self.assertEqual({"deployment_id", "expected_revision", "grant_id", "tenant_id"}, set(schema["properties"]))
        self.assertNotIn("tenant_id", schema["required"])
        self.assertIn("null", schema["properties"]["tenant_id"]["type"])
        self.assertIn("expected_revision", schema["required"])
        for name in ("deployment_id", "grant_id"):
            self.assertEqual("string", schema["properties"][name]["type"])
            self.assertIn(name, schema["required"])
        self.assertEqual("integer", schema["properties"]["expected_revision"]["type"])
        self.assertEqual(1, schema["properties"]["expected_revision"]["minimum"])
        invalid = copy.deepcopy(self.document)
        invalid["paths"][path]["post"]["parameters"] = []
        self.assertTrue(any("must require Idempotency-Key" in error
                            for error in contract.validate_contract(invalid)))
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["SwitchAuthorization"]["properties"]["grant_id"] = {"type": "integer"}
        self.assertTrue(any("request schema mismatch" in error and path in error
                            for error in contract.diff_documents(self.document, runtime)))
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["SwitchAuthorization"]["properties"]["expected_revision"] = {"type": "string"}
        self.assertTrue(any("request schema mismatch" in error and path in error
                            for error in contract.diff_documents(self.document, runtime)))

    def test_runtime_diff_rejects_missing_operation_scope_headers_and_body(self):
        """Missing security metadata and command preconditions cannot pass as absent facts."""
        path = "/open-platform/agent-deployments/authorization"
        for missing, message in (("x-required-scope", "scope mismatch"),
                                 ("parameters", "required headers mismatch"),
                                 ("requestBody", "request schema mismatch")):
            runtime = copy.deepcopy(self.document)
            del runtime["paths"][path]["post"][missing]
            self.assertTrue(any(message in error and path in error
                                for error in contract.diff_documents(self.document, runtime)), missing)

    def test_grant_chain_preserves_nullable_roots_and_rejects_revision_type_drift(self):
        """Governance ancestry remains typed and cannot collapse to an opaque response map."""
        schemas = self.document["components"]["schemas"]
        self.assertEqual("#/components/schemas/GrantChain", schemas["View"]["properties"]["grant_chain"]["$ref"])
        self.assertEqual("#/components/schemas/GrantHop", schemas["GrantChain"]["properties"]["hops"]["items"]["$ref"])
        for name in ("parent_grant_id", "grantor_tenant_id", "relation_ref"):
            self.assertEqual({"type": ["string", "null"]}, schemas["GrantHop"]["properties"][name])
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["GrantHop"]["properties"]["revision"] = {"type": "string"}
        self.assertTrue(any("response schema mismatch" in error and "agent-deployments" in error
                            for error in contract.diff_documents(self.document, runtime)))

    def test_agent_budget_schema_preserves_inheritance_positive_calls_and_closed_fields(self):
        path = "/open-platform/agent-deployments/definitions"
        schema = self.document["components"]["schemas"]["AgentExecutionConfig"]
        self.assertFalse(schema["additionalProperties"])
        calls = schema["properties"]["max_business_model_calls"]
        self.assertEqual("integer", calls["type"])
        self.assertEqual(1, calls["minimum"])
        self.assertTrue(calls["nullable"])
        self.assertNotIn("max_business_model_calls", schema.get("required", []))
        for dropped in ("additionalProperties", "minimum", "nullable"):
            runtime = copy.deepcopy(self.document)
            target = runtime["components"]["schemas"]["AgentExecutionConfig"]
            if dropped != "additionalProperties":
                target = target["properties"]["max_business_model_calls"]
            del target[dropped]
            self.assertTrue(any("response schema mismatch" in error and path in error
                                for error in contract.diff_documents(self.document, runtime)), dropped)

    def test_rejects_path_templates_write_queries_and_broken_refs(self):
        document = copy.deepcopy(self.document)
        document["paths"]["/open-platform/invalid/{id}"] = {
            "post": {"x-required-scope": "resources.write", "parameters": [
                {"name": "id", "in": "query", "schema": {"type": "string"}}
            ]}
        }
        errors = contract.validate_contract(document)
        self.assertTrue(any("fixed path" in error for error in errors))
        self.assertTrue(any("request body" in error for error in errors))
        document["components"]["schemas"]["Broken"] = {"$ref": "#/components/schemas/Absent"}
        self.assertTrue(any("unresolved reference" in error for error in contract.validate_contract(document)))

    def test_write_commands_include_identifiers_and_nested_payload(self):
        for path, method, identifiers in [
            ("/open-platform/sessions/messages", "post", {"session_id"}),
            ("/open-platform/resources/update", "put", {"resource_type", "external_ref"}),
        ]:
            operation = self.document["paths"][path][method]
            body = contract.resolve_ref(self.document, operation["requestBody"])
            schema = contract.resolve_ref(self.document, body["content"]["application/json"]["schema"])
            self.assertTrue((identifiers | {"payload"}).issubset(schema["properties"]))
            self.assertTrue((identifiers | {"payload"}).issubset(schema["required"]))

    def test_integration_identity_and_structured_input_preserve_public_boundaries(self):
        """Public consumers keep decimal identities and exact typed references instead of opaque maps."""
        schemas = self.document["components"]["schemas"]
        for name in ("ExternalEndUser", "Session", "CreateSessionRequest"):
            self.assertEqual("string", schemas[name]["properties"]["end_user_id"]["type"])
        self.assertEqual(256, self.document["components"]["parameters"]["IdempotencyKey"]["schema"]["maxLength"])
        self.assertEqual(128, schemas["InboundReceipt"]["properties"]["input_id"]["maxLength"])
        message = schemas["MessageRequest"]["properties"]
        content = message["content"]["items"]["oneOf"]
        references = message["references"]["items"]["oneOf"]
        self.assertEqual({"ChatContentImage", "ChatContentText"}, {item["$ref"].rsplit("/", 1)[-1] for item in content})
        self.assertEqual({"ArtifactFiles", "DeliverableRevision", "Message", "Process"},
                         {item["$ref"].rsplit("/", 1)[-1] for item in references})

    def test_runtime_diff_normalizes_server_prefix(self):
        runtime = copy.deepcopy(self.document)
        runtime["servers"] = [{"url": "/api/v1"}]
        runtime["paths"] = {
            "/api/v1" + path: value for path, value in self.document["paths"].items()
        }
        self.assertEqual([], contract.diff_documents(self.document, runtime))

    def test_runtime_diff_ignores_unrelated_host_routes(self):
        runtime = copy.deepcopy(self.document)
        runtime["paths"]["/api/v1/internal-host-settings"] = {"get": {"responses": {"200": {"description": "host only"}}}}
        self.assertEqual([], contract.diff_documents(self.document, runtime))

    def test_schema_nullable_normalizes_without_hiding_nonnullable_drift(self):
        nullable = contract._schema_shape({}, {"type": "string", "nullable": True})
        union = contract._schema_shape({}, {"oneOf": [{"type": "string"}, {"type": "null"}]})
        self.assertEqual(nullable, union)
        self.assertNotEqual(nullable, contract._schema_shape({}, {"type": "string"}))

    def test_response_schema_drift_is_reported(self):
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["ApiResponse"]["properties"]["trace_id"]["minLength"] = 2
        self.assertTrue(any("response schema mismatch" in error
                            for error in contract.diff_documents(self.document, runtime)))

    def test_control_item_status_uses_wire_values_and_detects_enum_drift(self):
        item_status = self.document["components"]["schemas"]["ControlOperationItem"]["properties"]["status"]
        operation_status = self.document["components"]["schemas"]["ControlOperation"]["properties"]["status"]
        self.assertEqual(set(operation_status["enum"]), set(item_status["enum"]))
        self.assertTrue(all(value == value.lower() for value in item_status["enum"]))
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["ControlOperationItem"]["properties"]["status"]["enum"] = [
            value.upper() for value in item_status["enum"]
        ]
        errors = contract.diff_documents(self.document, runtime)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("response schema mismatch" in error and "control-operations" in error for error in errors))

    def test_workflow_callback_response_requires_json_media_type(self):
        runtime = copy.deepcopy(self.document)
        response = runtime["paths"]["/open-platform/sessions/workflow-callbacks"]["post"]["responses"]["200"]
        response = contract.resolve_ref(runtime, response)
        response["content"]["*/*"] = response["content"].pop("application/json")
        errors = contract.diff_documents(self.document, runtime)
        self.assertTrue(any("response content mismatch" in error and "workflow-callbacks" in error for error in errors))

    def test_workflow_callback_response_rejects_opaque_map_schema(self):
        runtime = copy.deepcopy(self.document)
        runtime["components"]["schemas"]["WorkflowCallback"] = {"type": "object", "additionalProperties": {}}
        errors = contract.diff_documents(self.document, runtime)
        self.assertTrue(any("response schema mismatch" in error and "workflow-callbacks" in error for error in errors))

    def test_runtime_diff_reports_missing_extra_and_scope_drift(self):
        runtime = copy.deepcopy(self.document)
        del runtime["paths"]["/open-platform/sessions/events"]
        runtime["paths"]["/open-platform/workflow/run"] = {
            "post": {"x-required-scope": "sessions.write"}
        }
        runtime["paths"]["/open-platform/resources"]["get"]["x-required-scope"] = "resources.write"
        errors = contract.diff_documents(self.document, runtime)
        self.assertTrue(any("missing GET /open-platform/sessions/events" in error for error in errors))
        self.assertTrue(any("undocumented POST /open-platform/workflow/run" in error for error in errors))
        self.assertTrue(any("scope mismatch" in error for error in errors))

    def test_generated_sdk_is_deterministic_and_publish_manifest_is_hashed(self):
        generated = contract.render_generated_sdk(self.document)
        self.assertIn("OPEN_PLATFORM_V1_OPERATIONS", generated)
        self.assertIn("/open-platform/sessions/events", generated)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "v1-contract.ts"
            self.assertEqual(0, contract.write_generated(CONTRACT_PATH, output, check=False))
            self.assertEqual(generated, output.read_text(encoding="utf-8"))
            artifact = Path(directory) / "artifact"
            contract.publish_artifact(CONTRACT_PATH, artifact)
            manifest = json.loads((artifact / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("open-platform-v1", manifest["contract"])
            self.assertEqual(80, manifest["operationCount"])
            self.assertEqual(
                contract.hashlib.sha256(CONTRACT_PATH.read_bytes()).hexdigest(), manifest["sha256"]
            )


if __name__ == "__main__":
    unittest.main()
