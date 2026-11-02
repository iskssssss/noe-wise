-- 交付要求归属artifact领域；固定结构使用列和有序明细，原身份由Runtime唯一保存并核对摘要。
CREATE TABLE public.nw_output_requirement_contracts (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL,
 owner_key varchar(64) NOT NULL, input_key text NOT NULL, goal_id text,
 origin_run_id text NOT NULL, origin_identity_digest varchar(64) NOT NULL, source_digest varchar(64) NOT NULL,
 schema_version text NOT NULL, extractor_version text NOT NULL, policy_version text NOT NULL,
 state text NOT NULL, reason text, disposition text, contract_digest varchar(64),
 invocation_key text NOT NULL, invocation_ref text, configuration_ref text, request_digest varchar(64),
 unresolved text[] NOT NULL DEFAULT '{}', previous_id text, previous_revision bigint, previous_digest varchar(64),
 resolved_contract_id text, resolved_revision bigint, resolved_digest varchar(64),
 created_at timestamptz(6) NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_output_requirement_contracts_pk PRIMARY KEY(tenant_id,contract_id,revision),
 CONSTRAINT skillforge_output_requirement_contracts_revision_ck CHECK(revision>0),
 CONSTRAINT skillforge_output_requirement_contracts_state_ck CHECK(state IN('PROCESSING','AVAILABLE','CLARIFICATION_REQUIRED','EXECUTION_FAILED','OUTCOME_UNKNOWN')),
 CONSTRAINT skillforge_output_requirement_contracts_disposition_ck CHECK(disposition IS NULL OR disposition IN('REQUIRED','NO_FILE','CLARIFICATION')),
 CONSTRAINT skillforge_output_requirement_contracts_accepted_ck CHECK(state NOT IN('AVAILABLE','CLARIFICATION_REQUIRED') OR
   (disposition IS NOT NULL AND contract_digest IS NOT NULL AND invocation_ref IS NOT NULL AND configuration_ref IS NOT NULL)),
 CONSTRAINT skillforge_output_requirement_contracts_previous_fk FOREIGN KEY(tenant_id,previous_id,previous_revision)
   REFERENCES public.nw_output_requirement_contracts(tenant_id,contract_id,revision) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_contracts_resolved_fk FOREIGN KEY(tenant_id,resolved_contract_id,resolved_revision)
   REFERENCES public.nw_output_requirement_contracts(tenant_id,contract_id,revision) ON DELETE RESTRICT
);
CREATE INDEX skillforge_output_requirement_contracts_owner_idx ON public.nw_output_requirement_contracts(tenant_id,owner_key,input_key);

CREATE TABLE public.nw_output_requirement_sources (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL, ordinal integer NOT NULL,
 source_id text NOT NULL, source_kind text NOT NULL, source_text text NOT NULL, source_digest varchar(64) NOT NULL,
 goal_id text, plan_revision bigint, task_id text,
 CONSTRAINT skillforge_output_requirement_sources_pk PRIMARY KEY(tenant_id,contract_id,revision,source_id),
 CONSTRAINT skillforge_output_requirement_sources_order_uq UNIQUE(tenant_id,contract_id,revision,ordinal),
 CONSTRAINT skillforge_output_requirement_sources_contract_fk FOREIGN KEY(tenant_id,contract_id,revision)
   REFERENCES public.nw_output_requirement_contracts(tenant_id,contract_id,revision) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_sources_kind_ck CHECK(source_kind IN('REQUEST','GOAL','PLAN_TERMINAL')),
 CONSTRAINT skillforge_output_requirement_sources_scope_ck CHECK((source_kind<>'GOAL' OR goal_id IS NOT NULL)
   AND(source_kind<>'PLAN_TERMINAL' OR(plan_revision>0 AND task_id IS NOT NULL)))
);
CREATE TABLE public.nw_output_requirement_items (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL, item_id text NOT NULL, ordinal integer NOT NULL,
 CONSTRAINT skillforge_output_requirement_items_pk PRIMARY KEY(tenant_id,contract_id,revision,item_id),
 CONSTRAINT skillforge_output_requirement_items_order_uq UNIQUE(tenant_id,contract_id,revision,ordinal),
 CONSTRAINT skillforge_output_requirement_items_contract_fk FOREIGN KEY(tenant_id,contract_id,revision)
   REFERENCES public.nw_output_requirement_contracts(tenant_id,contract_id,revision) ON DELETE RESTRICT
);
CREATE TABLE public.nw_output_requirement_artifact_context (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL, ordinal integer NOT NULL,
 logical_key text NOT NULL, base_revision text NOT NULL,
 CONSTRAINT skillforge_output_requirement_artifact_context_pk PRIMARY KEY(tenant_id,contract_id,revision,logical_key),
 CONSTRAINT skillforge_output_requirement_artifact_context_order_uq UNIQUE(tenant_id,contract_id,revision,ordinal),
 CONSTRAINT skillforge_output_requirement_artifact_context_contract_fk FOREIGN KEY(tenant_id,contract_id,revision)
   REFERENCES public.nw_output_requirement_contracts(tenant_id,contract_id,revision) ON DELETE RESTRICT
);
CREATE TABLE public.nw_output_requirement_nodes (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL, item_id text NOT NULL,
 node_path text NOT NULL, parent_path text, ordinal integer NOT NULL, node_type text NOT NULL,
 formats text[] NOT NULL DEFAULT '{}', filenames text[] NOT NULL DEFAULT '{}', excluded_formats text[] NOT NULL DEFAULT '{}',
 single_file boolean NOT NULL DEFAULT false, logical_key text, base_revision text,
 CONSTRAINT skillforge_output_requirement_nodes_pk PRIMARY KEY(tenant_id,contract_id,revision,item_id,node_path),
 CONSTRAINT skillforge_output_requirement_nodes_item_fk FOREIGN KEY(tenant_id,contract_id,revision,item_id)
   REFERENCES public.nw_output_requirement_items(tenant_id,contract_id,revision,item_id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_nodes_parent_fk FOREIGN KEY(tenant_id,contract_id,revision,item_id,parent_path)
   REFERENCES public.nw_output_requirement_nodes(tenant_id,contract_id,revision,item_id,node_path) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_nodes_type_ck CHECK(node_type IN('ALL','ANY','ARTIFACT')),
 CONSTRAINT skillforge_output_requirement_nodes_base_ck CHECK((logical_key IS NULL)=(base_revision IS NULL))
);
CREATE TABLE public.nw_output_requirement_evidence (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL, item_id text NOT NULL,
 ordinal integer NOT NULL, source_id text NOT NULL, start_offset integer NOT NULL, end_offset integer NOT NULL, quote text NOT NULL,
 CONSTRAINT skillforge_output_requirement_evidence_pk PRIMARY KEY(tenant_id,contract_id,revision,item_id,ordinal),
 CONSTRAINT skillforge_output_requirement_evidence_item_fk FOREIGN KEY(tenant_id,contract_id,revision,item_id)
   REFERENCES public.nw_output_requirement_items(tenant_id,contract_id,revision,item_id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_evidence_source_fk FOREIGN KEY(tenant_id,contract_id,revision,source_id)
   REFERENCES public.nw_output_requirement_sources(tenant_id,contract_id,revision,source_id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_evidence_range_ck CHECK(start_offset>=0 AND end_offset>start_offset)
);
CREATE TABLE public.nw_output_requirement_run_bindings (
 tenant_id text NOT NULL, run_id text NOT NULL, owner_key varchar(64) NOT NULL, identity_digest varchar(64) NOT NULL,
 contract_id text NOT NULL, revision bigint NOT NULL, contract_digest varchar(64) NOT NULL,
 CONSTRAINT skillforge_output_requirement_run_bindings_pk PRIMARY KEY(tenant_id,run_id),
 CONSTRAINT skillforge_output_requirement_run_bindings_contract_fk FOREIGN KEY(tenant_id,contract_id,revision)
   REFERENCES public.nw_output_requirement_contracts(tenant_id,contract_id,revision) ON DELETE RESTRICT
);
CREATE TABLE public.nw_output_requirement_changes (
 tenant_id text NOT NULL, contract_id text NOT NULL, revision bigint NOT NULL, change_ordinal integer NOT NULL,
 change_kind text NOT NULL, target_item_id text, evidence_ordinal integer NOT NULL,
 source_id text NOT NULL, start_offset integer NOT NULL, end_offset integer NOT NULL, quote text NOT NULL,
 CONSTRAINT skillforge_output_requirement_changes_pk PRIMARY KEY(tenant_id,contract_id,revision,change_ordinal,evidence_ordinal),
 CONSTRAINT skillforge_output_requirement_changes_source_fk FOREIGN KEY(tenant_id,contract_id,revision,source_id)
   REFERENCES public.nw_output_requirement_sources(tenant_id,contract_id,revision,source_id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_output_requirement_changes_kind_ck CHECK(change_kind IN('ADD','REPLACE','REMOVE')),
 CONSTRAINT skillforge_output_requirement_changes_target_ck CHECK(change_kind='ADD' OR target_item_id IS NOT NULL),
 CONSTRAINT skillforge_output_requirement_changes_range_ck CHECK(start_offset>=0 AND end_offset>start_offset)
);

CREATE FUNCTION public.output_requirement_immutable() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 IF TG_TABLE_NAME='nw_output_requirement_contracts' AND TG_OP='UPDATE' AND OLD.state='PROCESSING'
    AND NEW.state<>'PROCESSING'
    AND ROW(NEW.tenant_id,NEW.contract_id,NEW.revision,NEW.owner_key,NEW.input_key,NEW.goal_id,NEW.origin_run_id,NEW.origin_identity_digest,
      NEW.source_digest,NEW.schema_version,NEW.extractor_version,NEW.policy_version,NEW.invocation_key,NEW.request_digest,NEW.created_at)
      IS NOT DISTINCT FROM ROW(OLD.tenant_id,OLD.contract_id,OLD.revision,OLD.owner_key,OLD.input_key,OLD.goal_id,OLD.origin_run_id,OLD.origin_identity_digest,
      OLD.source_digest,OLD.schema_version,OLD.extractor_version,OLD.policy_version,OLD.invocation_key,OLD.request_digest,OLD.created_at) THEN RETURN NEW; END IF;
 RAISE EXCEPTION 'output_requirement_immutable';
END $body$;
DO $body$
DECLARE relation_name text;
BEGIN
 FOREACH relation_name IN ARRAY ARRAY['nw_output_requirement_contracts','nw_output_requirement_sources','nw_output_requirement_items',
   'nw_output_requirement_nodes','nw_output_requirement_evidence','nw_output_requirement_run_bindings','nw_output_requirement_changes','nw_output_requirement_artifact_context'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',relation_name);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',relation_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING(tenant_id=current_setting(''app.tenant_id'',true)) WITH CHECK(tenant_id=current_setting(''app.tenant_id'',true))',relation_name);
  EXECUTE format('CREATE TRIGGER output_requirement_immutable BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.output_requirement_immutable()',relation_name);
  EXECUTE format('GRANT SELECT,INSERT ON public.%I TO skillforge',relation_name);
 END LOOP;
END $body$;
GRANT UPDATE(state,reason,disposition,contract_digest,invocation_ref,configuration_ref,unresolved,previous_id,previous_revision,previous_digest,resolved_contract_id,resolved_revision,resolved_digest)
 ON public.nw_output_requirement_contracts TO skillforge;

COMMENT ON TABLE public.nw_output_requirement_contracts IS '可信交付要求的单次解释受理及不可变接受版本';
COMMENT ON TABLE public.nw_output_requirement_sources IS '精确输入Goal和已批准终端计划来源快照';
COMMENT ON TABLE public.nw_output_requirement_items IS '可独立CAS修订的交付义务及稳定宿主item标识';
COMMENT ON TABLE public.nw_output_requirement_nodes IS 'All Any Artifact有限表达式有序节点';
COMMENT ON TABLE public.nw_output_requirement_evidence IS '候选依据的UTF16半开字符区间和精确原文';
COMMENT ON TABLE public.nw_output_requirement_run_bindings IS '首个依赖MODEL准备冻结的完整Run身份到精确要求版本';
COMMENT ON TABLE public.nw_output_requirement_changes IS '局部修订的动作目标和当前可信修改正文依据，包括被移除的义务';
COMMENT ON TABLE public.nw_output_requirement_artifact_context IS '首次受理冻结的真实既有成果逻辑键与精确当前版本，模型只能引用这些值';
COMMENT ON COLUMN public.nw_output_requirement_contracts.resolved_contract_id IS '局部修改解释受理最终指向的原契约修订标识';
COMMENT ON COLUMN public.nw_output_requirement_contracts.resolved_revision IS '局部修改解释受理最终指向的精确修订号';
COMMENT ON COLUMN public.nw_output_requirement_contracts.resolved_digest IS '局部修改解释受理最终指向的接受摘要';

COMMENT ON COLUMN public.nw_output_requirement_contracts.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_contracts.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_contracts.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_contracts.input_key IS '原始已接受输入或持久Goal目标的稳定键';
COMMENT ON COLUMN public.nw_output_requirement_contracts.goal_id IS '精确绑定的可选Goal标识';
COMMENT ON COLUMN public.nw_output_requirement_contracts.origin_run_id IS '原始受理Run引用，完整身份由ExecutionStore唯一持有';
COMMENT ON COLUMN public.nw_output_requirement_contracts.schema_version IS '候选和契约的固定schema版本';
COMMENT ON COLUMN public.nw_output_requirement_contracts.extractor_version IS '冻结解释器版本，重试不随模型升级重解释';
COMMENT ON COLUMN public.nw_output_requirement_contracts.policy_version IS '来源接受和有限表达式校验策略版本';
COMMENT ON COLUMN public.nw_output_requirement_contracts.state IS '显式处理可用澄清失败未知状态';
COMMENT ON COLUMN public.nw_output_requirement_contracts.reason IS '稳定失败或未知原因码，不保存外部异常正文';
COMMENT ON COLUMN public.nw_output_requirement_contracts.disposition IS '成功解释的文件义务、无文件或澄清决议';
COMMENT ON COLUMN public.nw_output_requirement_contracts.invocation_key IS '原SystemAgent调用幂等键，重试不补发新调用';
COMMENT ON COLUMN public.nw_output_requirement_contracts.invocation_ref IS '原始解释或结构化接受调用引用';
COMMENT ON COLUMN public.nw_output_requirement_contracts.configuration_ref IS '冻结模型配置或权威结构化接受策略引用';
COMMENT ON COLUMN public.nw_output_requirement_contracts.unresolved IS '澄清状态的有序未决问题';
COMMENT ON COLUMN public.nw_output_requirement_contracts.previous_id IS '上一份接受契约标识';
COMMENT ON COLUMN public.nw_output_requirement_contracts.previous_revision IS '上一份接受契约精确修订号';
COMMENT ON COLUMN public.nw_output_requirement_sources.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_sources.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_sources.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_sources.ordinal IS '同一父对象内零起始稳定顺序';
COMMENT ON COLUMN public.nw_output_requirement_sources.source_id IS '当前版本内唯一可信来源标识';
COMMENT ON COLUMN public.nw_output_requirement_sources.source_kind IS '当前请求、Goal原目标或已批准终端计划来源';
COMMENT ON COLUMN public.nw_output_requirement_sources.source_text IS '原始可信正文材料快照，不是固定配置JSON';
COMMENT ON COLUMN public.nw_output_requirement_sources.goal_id IS '精确绑定的可选Goal标识';
COMMENT ON COLUMN public.nw_output_requirement_sources.plan_revision IS '已批准或免审批DAG计划的精确修订';
COMMENT ON COLUMN public.nw_output_requirement_sources.task_id IS '计划终端任务精确标识';
COMMENT ON COLUMN public.nw_output_requirement_items.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_items.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_items.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_items.item_id IS '宿主分配的稳定义务item标识';
COMMENT ON COLUMN public.nw_output_requirement_items.ordinal IS '同一父对象内零起始稳定顺序';
COMMENT ON COLUMN public.nw_output_requirement_artifact_context.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_artifact_context.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_artifact_context.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_artifact_context.ordinal IS '同一父对象内零起始稳定顺序';
COMMENT ON COLUMN public.nw_output_requirement_artifact_context.logical_key IS '真实既有成果的会话内逻辑键';
COMMENT ON COLUMN public.nw_output_requirement_artifact_context.base_revision IS '真实既有成果的精确当前版本';
COMMENT ON COLUMN public.nw_output_requirement_nodes.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_nodes.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_nodes.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_nodes.item_id IS '宿主分配的稳定义务item标识';
COMMENT ON COLUMN public.nw_output_requirement_nodes.node_path IS '表达式树内确定的节点路径';
COMMENT ON COLUMN public.nw_output_requirement_nodes.parent_path IS '父节点路径，根节点为空';
COMMENT ON COLUMN public.nw_output_requirement_nodes.ordinal IS '同一父对象内零起始稳定顺序';
COMMENT ON COLUMN public.nw_output_requirement_nodes.node_type IS '有限ALL、ANY或ARTIFACT节点类型';
COMMENT ON COLUMN public.nw_output_requirement_nodes.formats IS '同一成果必须覆盖的真实小写格式';
COMMENT ON COLUMN public.nw_output_requirement_nodes.filenames IS '同一成果必须覆盖的精确文件basename';
COMMENT ON COLUMN public.nw_output_requirement_nodes.excluded_formats IS '同一成果明确排除的格式';
COMMENT ON COLUMN public.nw_output_requirement_nodes.single_file IS '是否明确要求单个真实受管文件';
COMMENT ON COLUMN public.nw_output_requirement_nodes.logical_key IS '真实既有成果的会话内逻辑键';
COMMENT ON COLUMN public.nw_output_requirement_nodes.base_revision IS '真实既有成果的精确当前版本';
COMMENT ON COLUMN public.nw_output_requirement_evidence.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_evidence.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_evidence.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_evidence.item_id IS '宿主分配的稳定义务item标识';
COMMENT ON COLUMN public.nw_output_requirement_evidence.ordinal IS '同一父对象内零起始稳定顺序';
COMMENT ON COLUMN public.nw_output_requirement_evidence.source_id IS '当前版本内唯一可信来源标识';
COMMENT ON COLUMN public.nw_output_requirement_evidence.start_offset IS '来源正文UTF16半开区间起点';
COMMENT ON COLUMN public.nw_output_requirement_evidence.end_offset IS '来源正文UTF16半开区间终点';
COMMENT ON COLUMN public.nw_output_requirement_evidence.quote IS '与来源区间逐字符一致的原文';
COMMENT ON COLUMN public.nw_output_requirement_run_bindings.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_run_bindings.run_id IS '消费方真实Run引用';
COMMENT ON COLUMN public.nw_output_requirement_run_bindings.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_run_bindings.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_changes.tenant_id IS '可信机构身份，与RLS机构设置一致';
COMMENT ON COLUMN public.nw_output_requirement_changes.contract_id IS '宿主分配的稳定契约标识';
COMMENT ON COLUMN public.nw_output_requirement_changes.revision IS '契约不可变修订号，局部修订CAS递增';
COMMENT ON COLUMN public.nw_output_requirement_changes.change_ordinal IS '同次局部修订中的动作顺序';
COMMENT ON COLUMN public.nw_output_requirement_changes.change_kind IS '有限新增、替换或移除动作';
COMMENT ON COLUMN public.nw_output_requirement_changes.target_item_id IS '被明确修改或移除的原稳定item标识';
COMMENT ON COLUMN public.nw_output_requirement_changes.evidence_ordinal IS '同次修改动作的证据顺序';
COMMENT ON COLUMN public.nw_output_requirement_changes.source_id IS '当前版本内唯一可信来源标识';
COMMENT ON COLUMN public.nw_output_requirement_changes.start_offset IS '来源正文UTF16半开区间起点';
COMMENT ON COLUMN public.nw_output_requirement_changes.end_offset IS '来源正文UTF16半开区间终点';
COMMENT ON COLUMN public.nw_output_requirement_changes.quote IS '与来源区间逐字符一致的原文';
