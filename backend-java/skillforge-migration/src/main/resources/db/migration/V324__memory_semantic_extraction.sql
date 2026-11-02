-- 沿既有来源队列冻结模型输入；候选明细用类型化列保存命令、状态和事实关联。
ALTER TABLE public.agent_runtime_memory_extraction_jobs
 DROP COLUMN candidate_data,
 ADD COLUMN policy_version text NOT NULL DEFAULT 'memory-extraction-policy-v2',
 ADD COLUMN source_time_epoch_second bigint,
 ADD COLUMN source_time_nano integer,
 ADD COLUMN frozen_fact_count integer,
 ADD COLUMN model_status varchar(16),
 ADD COLUMN system_run_id text,
 ADD COLUMN configuration_ref text,
 ADD COLUMN invocation_ref text,
 ADD COLUMN claim_token text,
 ADD COLUMN lease_until timestamptz,
 DROP CONSTRAINT pk_agent_runtime_memory_extraction_job,
 DROP CONSTRAINT ck_agent_runtime_memory_extraction_status,
 ADD CONSTRAINT pk_agent_runtime_memory_extraction_job PRIMARY KEY
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key),
 ADD CONSTRAINT ck_agent_runtime_memory_extraction_status CHECK
  (status IN ('READY','PROCESSING','WAITING','ACK_PENDING','UNKNOWN','COMPLETED','FAILED','SKIPPED')),
 ADD CONSTRAINT ck_memory_extraction_semantic_material CHECK
  (btrim(policy_version)<>'' AND (model_status IS NULL OR model_status IN ('WAITING','COMPLETED','UNKNOWN','REJECTED'))
   AND (source_time_epoch_second IS NULL)=(source_time_nano IS NULL)
   AND (source_time_epoch_second IS NULL)=(frozen_fact_count IS NULL)
   AND (source_time_nano IS NULL OR source_time_nano BETWEEN 0 AND 999999999)
   AND (frozen_fact_count IS NULL OR frozen_fact_count BETWEEN 0 AND 64));
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.policy_version IS '首次登记的宿主落位与治理策略版本';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.source_time_epoch_second IS '原正式来源发生时刻的秒，首次冻结后不可替换';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.source_time_nano IS '原正式来源时刻的纳秒，不降为数据库微秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.frozen_fact_count IS '原模型输入中按顺序固定的事实别名数量；正文只引用原不可变revision';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.claim_token IS '领取fence，迟到Worker不能覆盖新领取者结果';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.lease_until IS '本次本地处理租约；未知模型调用不重新派发';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.model_status IS '原系统模型任务状态，UNKNOWN不自动重新派发';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.system_run_id IS '原系统Agent已受理Run引用';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.configuration_ref IS '原已发布系统Agent配置引用';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.invocation_ref IS '原模型调用引用，用于收束未知结果';

CREATE TABLE public.agent_runtime_memory_extraction_candidates (
 tenant_id text NOT NULL,
 source_event_id text NOT NULL,
 extractor_version text NOT NULL,
 policy_version text NOT NULL,
 memory_type text NOT NULL,
 canonical_fact_key text NOT NULL,
 candidate_ordinal integer NOT NULL,
 command_key text NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'READY',
 candidate_type text NOT NULL,subject text NOT NULL,predicate text NOT NULL,value_json jsonb NOT NULL,
 signal text NOT NULL,sensitive boolean NOT NULL,candidate_scope text NOT NULL,
 temporal_kind text NOT NULL,temporal_from_epoch_second bigint,temporal_from_nano integer,
 temporal_to_epoch_second bigint,temporal_to_nano integer,relation_kind text NOT NULL,relation_target text,
 condition_count integer NOT NULL,uncertainty_count integer NOT NULL,evidence_count integer NOT NULL,
 prepared_type text,prepared_scope text,retention_class text,schema_version text,payload_schema_id text,
 valid_from_epoch_second bigint,valid_from_nano integer,valid_to_epoch_second bigint,valid_to_nano integer,
 expires_epoch_second bigint,expires_nano integer,confidence double precision,importance double precision,
 review_required boolean,initial_status text,review_reason_count integer,
 target_fact_id text,
 target_revision bigint,
 fact_id text,
 fact_revision bigint,
 error_code text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT pk_memory_extraction_candidate PRIMARY KEY
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal),
 CONSTRAINT uq_memory_extraction_candidate_command UNIQUE(tenant_id,command_key),
 CONSTRAINT fk_memory_extraction_candidate_job FOREIGN KEY
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)
  REFERENCES public.agent_runtime_memory_extraction_jobs
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key) ON DELETE RESTRICT,
 CONSTRAINT fk_memory_extraction_candidate_target FOREIGN KEY(tenant_id,target_fact_id,target_revision)
  REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision) DEFERRABLE INITIALLY DEFERRED,
 CONSTRAINT fk_memory_extraction_candidate_result FOREIGN KEY(tenant_id,fact_id,fact_revision)
  REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision) DEFERRABLE INITIALLY DEFERRED,
 CONSTRAINT ck_memory_extraction_candidate CHECK(candidate_ordinal>=0 AND candidate_ordinal<32
  AND btrim(command_key)<>'' AND state IN ('READY','PREPARED','ACK_PENDING','UNKNOWN','RECORDED','FAILED')
  AND (target_fact_id IS NULL)=(target_revision IS NULL) AND (target_revision IS NULL OR target_revision>0)
  AND (fact_id IS NULL)=(fact_revision IS NULL) AND (fact_revision IS NULL OR fact_revision>0)
  AND (state<>'RECORDED' OR fact_id IS NOT NULL)
  AND ((state='READY' AND prepared_scope IS NULL) OR (state<>'READY' AND prepared_scope IS NOT NULL))
  AND candidate_type IN ('preference','episode') AND candidate_scope IN ('user','user_agent')
  AND btrim(subject)<>'' AND btrim(predicate)<>'' AND jsonb_typeof(value_json)='object' AND octet_length(value_json::text)<=16384
  AND signal IN ('observed','explicit_save','repeated_confirmed') AND temporal_kind IN ('stable','task','interval','unknown')
  AND relation_kind IN ('new','similar','possible_conflict','correction') AND (relation_kind='new')=(relation_target IS NULL)
  AND condition_count BETWEEN 0 AND 32 AND uncertainty_count BETWEEN 0 AND 32 AND evidence_count BETWEEN 1 AND 32
  AND (temporal_from_epoch_second IS NULL)=(temporal_from_nano IS NULL) AND (temporal_to_epoch_second IS NULL)=(temporal_to_nano IS NULL)
  AND (temporal_from_nano IS NULL OR temporal_from_nano BETWEEN 0 AND 999999999)
  AND (temporal_to_nano IS NULL OR temporal_to_nano BETWEEN 0 AND 999999999)
  AND ((temporal_kind='interval' AND temporal_from_epoch_second IS NOT NULL
        AND (temporal_to_epoch_second IS NULL OR (temporal_to_epoch_second,temporal_to_nano)>(temporal_from_epoch_second,temporal_from_nano)))
       OR (temporal_kind<>'interval' AND temporal_from_epoch_second IS NULL AND temporal_to_epoch_second IS NULL))
  AND (prepared_scope IS NULL OR COALESCE((prepared_scope IN ('USER','USER_AGENT') AND prepared_type IN ('preference','episode')
    AND btrim(retention_class)<>'' AND btrim(schema_version)<>'' AND btrim(payload_schema_id)<>''
    AND valid_from_epoch_second IS NOT NULL AND valid_from_nano BETWEEN 0 AND 999999999
    AND confidence BETWEEN 0 AND 1 AND importance BETWEEN 0 AND 1 AND review_required IS NOT NULL
    AND initial_status IN ('ACTIVE','PENDING_CONFIRM') AND review_reason_count BETWEEN 0 AND 32
    AND (valid_to_epoch_second IS NULL)=(valid_to_nano IS NULL) AND (expires_epoch_second IS NULL)=(expires_nano IS NULL)
    AND (valid_to_nano IS NULL OR valid_to_nano BETWEEN 0 AND 999999999)
    AND (expires_nano IS NULL OR expires_nano BETWEEN 0 AND 999999999)),false)))
);
CREATE INDEX ix_memory_extraction_candidate_result ON public.agent_runtime_memory_extraction_candidates(tenant_id,fact_id,fact_revision)
 WHERE fact_id IS NOT NULL;
CREATE INDEX ix_memory_extraction_candidate_target ON public.agent_runtime_memory_extraction_candidates(tenant_id,target_fact_id,target_revision)
 WHERE target_fact_id IS NOT NULL;
ALTER TABLE public.agent_runtime_memory_extraction_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_extraction_candidates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON public.agent_runtime_memory_extraction_candidates
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
COMMENT ON TABLE public.agent_runtime_memory_extraction_candidates IS '既有提取任务专属候选明细；租约与领取仅由父任务拥有，未知及在途材料不压缩';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.value_json IS '唯一开放业务object值，结构由来源业务定义；固定候选解释及草稿字段均不存入此列';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.prepared_scope IS '首次治理落位范围，非空表示固定草稿字段已准备；来源与正文从原任务/候选重建';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.target_fact_id IS '宿主验证后冻结的原事实修订目标；模型别名和payload元数据不授予替换权';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.target_revision IS '宿主验证后的精确原事实revision；确认前仍需双CAS';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.candidate_type IS '模型解释的受支持记忆类型';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.subject IS '被描述对象的规范身份';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.predicate IS '原候选关系槽位';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.signal IS '观察、显式保存或重复确认信号';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.sensitive IS '模型声明的敏感性，不能绕过宿主审核';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.candidate_scope IS '模型建议范围，不构成写入授权';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.temporal_kind IS 'stable/task/interval/unknown原时间性质';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.temporal_from_epoch_second IS '明确业务区间开始秒；非区间为空';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.temporal_from_nano IS '明确业务区间开始纳秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.temporal_to_epoch_second IS '明确业务区间结束秒；开放结束为空';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.temporal_to_nano IS '明确业务区间结束纳秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.relation_kind IS '原候选new/similar/possible_conflict/correction解释';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.relation_target IS '原模型事实别名，外键引用被冻结的精确revision';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.condition_count IS '原有序适用条件数量，明细不能在冻结后追加';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.uncertainty_count IS '原有序不确定项数量';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.evidence_count IS '原有序来源证据数量，至少一项';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.prepared_type IS '首次宿主治理后固定的写入类型';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.retention_class IS '首次准备使用的Runtime保留策略';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.schema_version IS '首次准备使用的类型schema版本';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.payload_schema_id IS '唯一Runtime写入payload的schema身份';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.valid_from_epoch_second IS '首次准备草稿的有效起点秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.valid_from_nano IS '首次准备草稿的有效起点纳秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.valid_to_epoch_second IS '首次准备草稿的可空有效终点秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.valid_to_nano IS '首次准备草稿的可空有效终点纳秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.expires_epoch_second IS '首次准备草稿的可空过期秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.expires_nano IS '首次准备草稿的可空过期纳秒';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.confidence IS '首次准备草稿的置信度，范围0至1';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.importance IS '首次准备草稿的重要度，范围0至1';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.review_required IS '首次治理确定的待审核要求';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.initial_status IS '原Create命令初始状态，仅ACTIVE/PENDING_CONFIRM';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.review_reason_count IS '首次准备固定的有序审核原因数量';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.command_key IS '本候选唯一原Runtime命令键，重试和未知恢复不得换键';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.state IS '既有候选进度，由父任务领取fence控制';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.fact_id IS '原Runtime结果事实身份';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.fact_revision IS '原Runtime结果精确修订，不能由最新head替换';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.error_code IS '稳定失败或待收束原因码，不含供应商原文';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.created_at IS '候选首次持久化时间';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.updated_at IS '候选最后本地进度更新时间';

CREATE TABLE public.agent_runtime_memory_extraction_sources (
 tenant_id text NOT NULL,source_event_id text NOT NULL,extractor_version text NOT NULL,policy_version text NOT NULL,
 memory_type text NOT NULL,canonical_fact_key text NOT NULL,source_alias text NOT NULL,source_ordinal integer NOT NULL,
 PRIMARY KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,source_alias),
 UNIQUE(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,source_ordinal),
 FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)
 REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key) ON DELETE RESTRICT,
 CHECK(source_alias='s0' AND source_ordinal=0)
);
CREATE TABLE public.agent_runtime_memory_extraction_fact_aliases (
 tenant_id text NOT NULL,source_event_id text NOT NULL,extractor_version text NOT NULL,policy_version text NOT NULL,
 memory_type text NOT NULL,canonical_fact_key text NOT NULL,alias_ordinal integer NOT NULL,alias text NOT NULL,
 fact_id text NOT NULL,fact_revision bigint NOT NULL,
 PRIMARY KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,alias),
 UNIQUE(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,alias_ordinal),
 FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)
 REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,fact_id,fact_revision) REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision) ON DELETE RESTRICT,
 CHECK(alias_ordinal BETWEEN 0 AND 63 AND btrim(alias)<>'' AND fact_revision>0)
);
CREATE INDEX memory_extraction_alias_revision ON public.agent_runtime_memory_extraction_fact_aliases(tenant_id,fact_id,fact_revision);
ALTER TABLE public.agent_runtime_memory_extraction_candidates ADD CONSTRAINT memory_extraction_relation_alias_fk
 FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,relation_target)
 REFERENCES public.agent_runtime_memory_extraction_fact_aliases(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,alias) ON DELETE RESTRICT;

-- 三个固定含义的有序文本集合各自拥有类型化表，不保存通用key/value或JSON数组。
DO $$ DECLARE item_table text; BEGIN
 FOREACH item_table IN ARRAY ARRAY['agent_runtime_memory_extraction_conditions','agent_runtime_memory_extraction_uncertainties','agent_runtime_memory_extraction_review_reasons'] LOOP
  EXECUTE format('CREATE TABLE public.%I (
   tenant_id text NOT NULL,source_event_id text NOT NULL,extractor_version text NOT NULL,policy_version text NOT NULL,
   memory_type text NOT NULL,canonical_fact_key text NOT NULL,candidate_ordinal integer NOT NULL,item_ordinal integer NOT NULL,item_text text NOT NULL,
   PRIMARY KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal,item_ordinal),
   FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal)
   REFERENCES public.agent_runtime_memory_extraction_candidates(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal) ON DELETE RESTRICT,
   CHECK(item_ordinal BETWEEN 0 AND 31 AND btrim(item_text)<>''''))',item_table);
 END LOOP;
END $$;
CREATE TABLE public.agent_runtime_memory_extraction_candidate_evidence (
 tenant_id text NOT NULL,source_event_id text NOT NULL,extractor_version text NOT NULL,policy_version text NOT NULL,
 memory_type text NOT NULL,canonical_fact_key text NOT NULL,candidate_ordinal integer NOT NULL,item_ordinal integer NOT NULL,
 source_alias text NOT NULL,quote text NOT NULL,
 PRIMARY KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal,item_ordinal),
 FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal)
 REFERENCES public.agent_runtime_memory_extraction_candidates(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,source_alias)
 REFERENCES public.agent_runtime_memory_extraction_sources(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,source_alias) ON DELETE RESTRICT,
 CHECK(item_ordinal BETWEEN 0 AND 31 AND btrim(quote)<>'')
);
DO $$ DECLARE item_table text; BEGIN
 FOREACH item_table IN ARRAY ARRAY['agent_runtime_memory_extraction_sources','agent_runtime_memory_extraction_fact_aliases',
 'agent_runtime_memory_extraction_conditions','agent_runtime_memory_extraction_uncertainties',
 'agent_runtime_memory_extraction_review_reasons','agent_runtime_memory_extraction_candidate_evidence'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',item_table);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',item_table);
  EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING(tenant_id=current_setting(''app.tenant_id'',true)) WITH CHECK(tenant_id=current_setting(''app.tenant_id'',true))',item_table);
  EXECUTE format('CREATE TRIGGER frozen_material_immutable BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable()',item_table);
  EXECUTE format('GRANT SELECT,INSERT ON public.%I TO skillforge',item_table);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON public.agent_runtime_memory_extraction_candidates TO skillforge;
COMMENT ON TABLE public.agent_runtime_memory_extraction_sources IS '单一正式来源的固定有序别名；原事件身份继承父任务及来源FK，不复制正文';
COMMENT ON TABLE public.agent_runtime_memory_extraction_fact_aliases IS '模型输入事实别名的固定顺序与精确不可变revision引用；不复制原事实';
COMMENT ON TABLE public.agent_runtime_memory_extraction_conditions IS '候选适用条件原有序文本集合';
COMMENT ON TABLE public.agent_runtime_memory_extraction_uncertainties IS '候选不确定项原有序文本集合';
COMMENT ON TABLE public.agent_runtime_memory_extraction_review_reasons IS '首次治理审核原因的原有序集合，准备后不变';
COMMENT ON TABLE public.agent_runtime_memory_extraction_candidate_evidence IS '候选证据原有序明细；来源别名FK到原正式来源，引用片段不授予写入权限';
DO $$ DECLARE item_table text; field_description record; BEGIN
 FOREACH item_table IN ARRAY ARRAY['agent_runtime_memory_extraction_candidates','agent_runtime_memory_extraction_sources',
 'agent_runtime_memory_extraction_fact_aliases','agent_runtime_memory_extraction_conditions',
 'agent_runtime_memory_extraction_uncertainties','agent_runtime_memory_extraction_review_reasons','agent_runtime_memory_extraction_candidate_evidence'] LOOP
  FOR field_description IN SELECT * FROM (VALUES
   ('tenant_id','可信原机构，与父来源及事实revision同租户'),('source_event_id','父任务引用的原不可变正式事件身份'),
   ('extractor_version','首次登记的提取器版本'),('policy_version','首次登记的宿主治理策略版本'),
   ('memory_type','父任务登记的来源提取分类'),('canonical_fact_key','父任务的固定来源候选身份摘要')) AS descriptions(column_name,description) LOOP
   EXECUTE format('COMMENT ON COLUMN public.%I.%I IS %L',item_table,field_description.column_name,field_description.description);
  END LOOP;
 END LOOP;
 FOREACH item_table IN ARRAY ARRAY['agent_runtime_memory_extraction_conditions','agent_runtime_memory_extraction_uncertainties',
 'agent_runtime_memory_extraction_review_reasons','agent_runtime_memory_extraction_candidate_evidence'] LOOP
  EXECUTE format('COMMENT ON COLUMN public.%I.candidate_ordinal IS %L',item_table,'原候选顺序，外键引用同任务候选');
  EXECUTE format('COMMENT ON COLUMN public.%I.item_ordinal IS %L',item_table,'原有序明细下标，从零连续冻结');
 END LOOP;
 FOREACH item_table IN ARRAY ARRAY['agent_runtime_memory_extraction_conditions','agent_runtime_memory_extraction_uncertainties','agent_runtime_memory_extraction_review_reasons'] LOOP
  EXECUTE format('COMMENT ON COLUMN public.%I.item_text IS %L',item_table,'原typed集合项的非空文本，首次持久化后不变');
 END LOOP;
END $$;
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidates.candidate_ordinal IS '原模型结果候选顺序，从零开始且最多32项';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_sources.source_alias IS '原模型来源别名，单一正式来源固定s0';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_sources.source_ordinal IS '原模型来源顺序，单一正式来源固定零';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_fact_aliases.alias_ordinal IS '原模型输入已有事实顺序，从零开始';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_fact_aliases.alias IS '原模型可读事实别名，不能被当作当前写入权限';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_fact_aliases.fact_id IS '原已授权事实身份，不引用最新搜索结果替代';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_fact_aliases.fact_revision IS '原已授权精确不可变revision，恢复读取必须保持';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidate_evidence.source_alias IS '证据对应的原正式来源别名，受同任务FK约束';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_candidate_evidence.quote IS '来源中的连续原文片段，不存完整来源或解释JSON';

-- 明细序号只能落在父材料首次声明的集合内，禁止冻结后追加解释或新别名。
CREATE FUNCTION public.guard_memory_extraction_detail_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item_limit integer;
BEGIN
 IF TG_TABLE_NAME='agent_runtime_memory_extraction_fact_aliases' THEN
  SELECT frozen_fact_count INTO item_limit FROM public.agent_runtime_memory_extraction_jobs
   WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
    (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
  IF item_limit IS NULL OR NEW.alias_ordinal>=item_limit THEN RAISE EXCEPTION 'memory_extraction_alias_outside_frozen_input'; END IF;
 ELSIF TG_TABLE_NAME<>'agent_runtime_memory_extraction_sources' THEN
  SELECT CASE TG_TABLE_NAME
   WHEN 'agent_runtime_memory_extraction_conditions' THEN condition_count
   WHEN 'agent_runtime_memory_extraction_uncertainties' THEN uncertainty_count
   WHEN 'agent_runtime_memory_extraction_candidate_evidence' THEN evidence_count
   WHEN 'agent_runtime_memory_extraction_review_reasons' THEN review_reason_count END
  INTO item_limit FROM public.agent_runtime_memory_extraction_candidates
  WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal)=
   (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key,NEW.candidate_ordinal);
  IF item_limit IS NULL OR NEW.item_ordinal>=item_limit THEN RAISE EXCEPTION 'memory_extraction_detail_outside_frozen_candidate'; END IF;
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE item_table text; BEGIN
 FOREACH item_table IN ARRAY ARRAY['agent_runtime_memory_extraction_sources','agent_runtime_memory_extraction_fact_aliases',
 'agent_runtime_memory_extraction_conditions','agent_runtime_memory_extraction_uncertainties',
 'agent_runtime_memory_extraction_review_reasons','agent_runtime_memory_extraction_candidate_evidence'] LOOP
  EXECUTE format('CREATE TRIGGER frozen_detail_bounds BEFORE INSERT ON public.%I FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_detail_insert()',item_table);
 END LOOP;
END $$;

CREATE FUNCTION public.assert_memory_extraction_material_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j public.agent_runtime_memory_extraction_jobs; c public.agent_runtime_memory_extraction_candidates;
BEGIN
 IF TG_TABLE_NAME='agent_runtime_memory_extraction_jobs' THEN
  SELECT * INTO j FROM public.agent_runtime_memory_extraction_jobs
   WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
    (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
  IF j.source_time_epoch_second IS NOT NULL AND
   ((SELECT count(*) FROM public.agent_runtime_memory_extraction_sources a
     WHERE (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key)=
      (j.tenant_id,j.source_event_id,j.extractor_version,j.policy_version,j.memory_type,j.canonical_fact_key))<>1
    OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_fact_aliases a
     WHERE (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key)=
      (j.tenant_id,j.source_event_id,j.extractor_version,j.policy_version,j.memory_type,j.canonical_fact_key))<>j.frozen_fact_count) THEN
   RAISE EXCEPTION 'memory_extraction_frozen_input_incomplete';
  END IF;
 ELSE
  SELECT * INTO c FROM public.agent_runtime_memory_extraction_candidates
   WHERE (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,candidate_ordinal)=
    (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key,NEW.candidate_ordinal);
  IF (SELECT count(*) FROM public.agent_runtime_memory_extraction_conditions a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>c.condition_count
   OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_uncertainties a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>c.uncertainty_count
   OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_candidate_evidence a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>c.evidence_count
   OR (SELECT count(*) FROM public.agent_runtime_memory_extraction_review_reasons a WHERE
    (a.tenant_id,a.source_event_id,a.extractor_version,a.policy_version,a.memory_type,a.canonical_fact_key,a.candidate_ordinal)=
    (c.tenant_id,c.source_event_id,c.extractor_version,c.policy_version,c.memory_type,c.canonical_fact_key,c.candidate_ordinal))<>coalesce(c.review_reason_count,0) THEN
   RAISE EXCEPTION 'memory_extraction_candidate_material_incomplete';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER frozen_input_complete AFTER INSERT OR UPDATE ON public.agent_runtime_memory_extraction_jobs
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_extraction_material_complete();
CREATE CONSTRAINT TRIGGER frozen_candidate_complete AFTER INSERT OR UPDATE ON public.agent_runtime_memory_extraction_candidates
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_extraction_material_complete();

-- 只保护首次冻结材料；状态转换仍由原队列与父任务fence负责。
CREATE FUNCTION public.guard_memory_extraction_frozen_material() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='agent_runtime_memory_extraction_jobs' THEN
  IF OLD.source_time_epoch_second IS NOT NULL AND
    (NEW.source_time_epoch_second,NEW.source_time_nano,NEW.frozen_fact_count) IS DISTINCT FROM
    (OLD.source_time_epoch_second,OLD.source_time_nano,OLD.frozen_fact_count) THEN
   RAISE EXCEPTION 'memory_extraction_input_immutable';
  END IF;
 ELSE
  IF (NEW.candidate_type,NEW.subject,NEW.predicate,NEW.value_json,NEW.signal,NEW.sensitive,NEW.candidate_scope,
      NEW.temporal_kind,NEW.temporal_from_epoch_second,NEW.temporal_from_nano,NEW.temporal_to_epoch_second,NEW.temporal_to_nano,
      NEW.relation_kind,NEW.relation_target,NEW.condition_count,NEW.uncertainty_count,NEW.evidence_count,NEW.command_key)
   IS DISTINCT FROM (OLD.candidate_type,OLD.subject,OLD.predicate,OLD.value_json,OLD.signal,OLD.sensitive,OLD.candidate_scope,
      OLD.temporal_kind,OLD.temporal_from_epoch_second,OLD.temporal_from_nano,OLD.temporal_to_epoch_second,OLD.temporal_to_nano,
      OLD.relation_kind,OLD.relation_target,OLD.condition_count,OLD.uncertainty_count,OLD.evidence_count,OLD.command_key)
   OR (OLD.prepared_scope IS NOT NULL AND
    (NEW.prepared_type,NEW.prepared_scope,NEW.retention_class,NEW.schema_version,NEW.payload_schema_id,NEW.valid_from_epoch_second,
     NEW.valid_from_nano,NEW.valid_to_epoch_second,NEW.valid_to_nano,NEW.expires_epoch_second,NEW.expires_nano,
     NEW.confidence,NEW.importance,NEW.review_required,NEW.initial_status,NEW.review_reason_count,NEW.target_fact_id,NEW.target_revision)
    IS DISTINCT FROM (OLD.prepared_type,OLD.prepared_scope,OLD.retention_class,OLD.schema_version,OLD.payload_schema_id,OLD.valid_from_epoch_second,
     OLD.valid_from_nano,OLD.valid_to_epoch_second,OLD.valid_to_nano,OLD.expires_epoch_second,OLD.expires_nano,
     OLD.confidence,OLD.importance,OLD.review_required,OLD.initial_status,OLD.review_reason_count,OLD.target_fact_id,OLD.target_revision)) THEN
   RAISE EXCEPTION 'memory_extraction_candidate_immutable';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER guard_memory_extraction_input BEFORE UPDATE ON public.agent_runtime_memory_extraction_jobs
 FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_frozen_material();
CREATE TRIGGER guard_memory_extraction_candidate BEFORE UPDATE ON public.agent_runtime_memory_extraction_candidates
 FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_frozen_material();

CREATE OR REPLACE FUNCTION public.enqueue_agent_runtime_memory_extraction()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE candidate jsonb; memory_type text; canonical_key text; payload jsonb;
BEGIN
 IF NEW.target_kind='GROUP' THEN RETURN NEW; END IF;
 IF NEW.event_type NOT IN ('inbound.message','agent.tool-result','agent.workflow-result') THEN RETURN NEW; END IF;
 payload:=public.agent_runtime_session_event_payload(NEW.tenant_id,NEW.session_id,NEW.target_kind,NEW.target_id,
  NEW.end_user_id,NEW.run_id,NEW.event_id,NEW.event_type,NEW.payload_schema_id,NEW.payload_canonical_json)::jsonb;
 candidate:=COALESCE(payload #> '{metadata,memoryCandidate}',payload->'memoryCandidate');
 IF candidate IS NOT NULL AND jsonb_typeof(candidate)='object' THEN
  memory_type:=COALESCE(NULLIF(btrim(candidate->>'type'),''),'preference');
  canonical_key:=md5(candidate::text);
  INSERT INTO public.agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,memory_type,
   canonical_fact_key,agent_id,end_user_id,source_session_id,source_target_kind,source_target_id,source_execution_id,source_event_type)
  VALUES(NEW.tenant_id,NEW.event_id,'memory-extractor-v2','memory-extraction-policy-v2',memory_type,canonical_key,NEW.agent_id,
   NEW.end_user_id,NEW.session_id,NEW.target_kind,NEW.target_id,NEW.execution_id,NEW.event_type) ON CONFLICT DO NOTHING;
  RETURN NEW;
 END IF;
 IF NEW.event_type<>'inbound.message' OR NOT EXISTS(SELECT 1 FROM public.nw_agents a
  JOIN public.nw_agent_publications p ON p.tenant_id=a.tenant_id AND p.agent_id=a.id AND p.id=a.published_revision_id
  WHERE a.tenant_id::text=NEW.tenant_id AND a.id::text=NEW.agent_id AND a.status='published'
   AND p.snapshot #> '{agent,config,memory,automatic_extraction}'='true'::jsonb) THEN RETURN NEW; END IF;
 INSERT INTO public.agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,memory_type,
  canonical_fact_key,agent_id,end_user_id,source_session_id,source_target_kind,source_target_id,source_execution_id,source_event_type)
 VALUES(NEW.tenant_id,NEW.event_id,'memory-extractor-v2','memory-extraction-policy-v2','inbound-hydrate','source',NEW.agent_id,
  NEW.end_user_id,NEW.session_id,NEW.target_kind,NEW.target_id,NEW.execution_id,NEW.event_type) ON CONFLICT DO NOTHING;
 RETURN NEW;
END;
$$;
COMMENT ON FUNCTION public.enqueue_agent_runtime_memory_extraction() IS '正式来源原子登记v2提取；来源、策略和候选身份不可因重试替换';
