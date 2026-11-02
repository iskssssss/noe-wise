-- 群任务继续进入统一提取队列；真实作者、固定群归属和系统执行身份分别保存。
ALTER TABLE public.agent_runtime_memory_extraction_jobs
 ADD COLUMN integration_id varchar(128),
 ADD COLUMN group_binding_id bigint,
 ADD COLUMN frozen_subject_count integer,
 ADD CONSTRAINT memory_extraction_group_owner_ck CHECK (
  (source_target_kind IN ('AGENT','TEAM') AND integration_id IS NULL AND group_binding_id IS NULL)
  OR (source_target_kind='GROUP' AND integration_id IS NOT NULL AND btrim(integration_id)<>''
      AND group_binding_id IS NOT NULL AND group_binding_id>0 AND source_target_id=group_binding_id::text
      AND source_event_type='group.memory.source')),
 ADD CONSTRAINT memory_extraction_group_subject_count_ck CHECK (
  (frozen_subject_count IS NULL OR frozen_subject_count>=0)
  AND (source_time_epoch_second IS NULL OR
       (source_target_kind='GROUP' AND coalesce(frozen_subject_count>0,false))
       OR (source_target_kind<>'GROUP' AND coalesce(frozen_subject_count,0)=0)));
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.integration_id IS '正式群来源集成域；个人提取为空';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.group_binding_id IS '正式群身份；不随执行Agent变化';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_jobs.frozen_subject_count IS '首次冻结的可信描述成员数量，个人输入为零';

ALTER TABLE public.agent_runtime_memory_extraction_candidates
 DROP CONSTRAINT ck_memory_extraction_candidate,
 ADD CONSTRAINT ck_memory_extraction_candidate CHECK(candidate_ordinal>=0 AND candidate_ordinal<32
  AND btrim(command_key)<>'' AND state IN ('READY','PREPARED','ACK_PENDING','UNKNOWN','RECORDED','FAILED')
  AND (target_fact_id IS NULL)=(target_revision IS NULL) AND (target_revision IS NULL OR target_revision>0)
  AND (fact_id IS NULL)=(fact_revision IS NULL) AND (fact_revision IS NULL OR fact_revision>0)
  AND (state<>'RECORDED' OR fact_id IS NOT NULL)
  AND ((state='READY' AND prepared_scope IS NULL)
       OR (state='FAILED' AND prepared_scope IS NULL AND fact_id IS NULL AND target_fact_id IS NULL
           AND error_code IN ('memory_forgotten_source_suppressed','memory_superseded_source_suppressed'))
       OR (state<>'READY' AND prepared_scope IS NOT NULL))
  AND candidate_type IN ('preference','episode') AND candidate_scope IN ('user','user_agent','group','group_member')
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
  AND (prepared_scope IS NULL OR COALESCE((prepared_scope IN ('USER','USER_AGENT','GROUP','GROUP_MEMBER') AND prepared_type IN ('preference','episode')
    AND btrim(retention_class)<>'' AND btrim(schema_version)<>'' AND btrim(payload_schema_id)<>''
    AND valid_from_epoch_second IS NOT NULL AND valid_from_nano BETWEEN 0 AND 999999999
    AND confidence BETWEEN 0 AND 1 AND importance BETWEEN 0 AND 1 AND review_required IS NOT NULL
    AND initial_status IN ('ACTIVE','PENDING_CONFIRM') AND review_reason_count BETWEEN 0 AND 32
    AND (valid_to_epoch_second IS NULL)=(valid_to_nano IS NULL) AND (expires_epoch_second IS NULL)=(expires_nano IS NULL)
    AND (valid_to_nano IS NULL OR valid_to_nano BETWEEN 0 AND 999999999)
    AND (expires_nano IS NULL OR expires_nano BETWEEN 0 AND 999999999)),false)));

CREATE TABLE public.agent_runtime_memory_extraction_subjects (
 tenant_id text NOT NULL,source_event_id text NOT NULL,extractor_version text NOT NULL,policy_version text NOT NULL,
 memory_type text NOT NULL,canonical_fact_key text NOT NULL,subject_ordinal integer NOT NULL,subject_end_user_id bigint NOT NULL,
 PRIMARY KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,subject_ordinal),
 UNIQUE(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key,subject_end_user_id),
 FOREIGN KEY(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)
 REFERENCES public.agent_runtime_memory_extraction_jobs(tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key) ON DELETE RESTRICT,
 CHECK(subject_ordinal>=0 AND subject_end_user_id>0)
);
COMMENT ON TABLE public.agent_runtime_memory_extraction_subjects IS '首次输入冻结的真实群描述对象；恢复不从当前成员表重建';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.subject_end_user_id IS '可信同群成员身份，不是当前系统操作者';
ALTER TABLE public.agent_runtime_memory_extraction_subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runtime_memory_extraction_subjects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON public.agent_runtime_memory_extraction_subjects
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));

CREATE FUNCTION public.guard_memory_extraction_subject() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_count integer;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'memory_extraction_subject_immutable'; END IF;
 SELECT frozen_subject_count INTO expected_count FROM public.agent_runtime_memory_extraction_jobs WHERE
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
  (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
 IF expected_count IS NULL OR NEW.subject_ordinal>=expected_count THEN
  RAISE EXCEPTION 'memory_extraction_subject_outside_frozen_input';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER extraction_subject_immutable BEFORE INSERT OR UPDATE OR DELETE ON public.agent_runtime_memory_extraction_subjects
 FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_subject();

CREATE FUNCTION public.assert_memory_extraction_subjects() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_job public.agent_runtime_memory_extraction_jobs%ROWTYPE;
BEGIN
 SELECT * INTO current_job FROM public.agent_runtime_memory_extraction_jobs WHERE
  (tenant_id,source_event_id,extractor_version,policy_version,memory_type,canonical_fact_key)=
  (NEW.tenant_id,NEW.source_event_id,NEW.extractor_version,NEW.policy_version,NEW.memory_type,NEW.canonical_fact_key);
 IF current_job.source_time_epoch_second IS NOT NULL AND
  (SELECT count(*) FROM public.agent_runtime_memory_extraction_subjects s WHERE
   (s.tenant_id,s.source_event_id,s.extractor_version,s.policy_version,s.memory_type,s.canonical_fact_key)=
   (current_job.tenant_id,current_job.source_event_id,current_job.extractor_version,current_job.policy_version,current_job.memory_type,current_job.canonical_fact_key))
   <>coalesce(current_job.frozen_subject_count,0) THEN
  RAISE EXCEPTION 'memory_extraction_subjects_incomplete';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER frozen_subjects_complete AFTER INSERT OR UPDATE ON public.agent_runtime_memory_extraction_jobs
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_extraction_subjects();

CREATE FUNCTION public.guard_memory_extraction_group_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.integration_id,NEW.group_binding_id,NEW.agent_id,NEW.end_user_id,NEW.source_target_kind,NEW.source_target_id,NEW.source_session_id)
 IS DISTINCT FROM (OLD.integration_id,OLD.group_binding_id,OLD.agent_id,OLD.end_user_id,OLD.source_target_kind,OLD.source_target_id,OLD.source_session_id)
 OR (OLD.source_time_epoch_second IS NOT NULL AND NEW.frozen_subject_count IS DISTINCT FROM OLD.frozen_subject_count) THEN
  RAISE EXCEPTION 'memory_extraction_group_identity_immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER extraction_group_identity_immutable BEFORE UPDATE ON public.agent_runtime_memory_extraction_jobs
 FOR EACH ROW EXECUTE FUNCTION public.guard_memory_extraction_group_identity();

CREATE TABLE public.nw_group_memory_extraction_policies (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,
 enabled boolean NOT NULL,extractor_agent_id bigint NOT NULL,revision bigint NOT NULL,
 updated_by_principal varchar(128) NOT NULL,updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES public.nw_group_bindings(tenant_id,integration_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,extractor_agent_id) REFERENCES public.nw_agents(tenant_id,id) ON DELETE RESTRICT,
 CHECK(revision>0 AND btrim(updated_by_principal)<>'')
);
COMMENT ON TABLE public.nw_group_memory_extraction_policies IS '群级显式后台提取策略；无行表示未启用，修改不改写已登记任务';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.extractor_agent_id IS '首次登记任务的执行与用量责任Agent；群记忆归属不绑定该Agent';
ALTER TABLE public.nw_group_memory_extraction_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_group_memory_extraction_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_policy ON public.nw_group_memory_extraction_policies
 USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.tenant_id IS '可信机构身份，参与统一任务主键与租户隔离';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.source_event_id IS '原已提交来源事件，参与统一任务主键';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.extractor_version IS '原提取器版本，参与统一任务主键';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.policy_version IS '原治理策略版本，参与统一任务主键';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.memory_type IS '原任务提取类型，参与统一任务主键';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.canonical_fact_key IS '原逻辑任务键，参与统一任务主键';
COMMENT ON COLUMN public.agent_runtime_memory_extraction_subjects.subject_ordinal IS '原描述对象顺序，从零连续冻结';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.tenant_id IS '可信机构身份，非请求自由指定';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.integration_id IS '固定外部集成域，随正式群归属';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.group_binding_id IS '正式群绑定生命周期身份';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.enabled IS '群管理员显式启用开关，无策略时关闭';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.revision IS '策略并发修改版本，首次为一';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.updated_by_principal IS '最近更新策略的当前认证服务主体';
COMMENT ON COLUMN public.nw_group_memory_extraction_policies.updated_at IS '策略最近成功修改时间';
