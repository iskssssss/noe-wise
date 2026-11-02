-- 实际调用的群与后台责任归属随用量事实冻结，总部读取无需额外读取系统执行表。
ALTER TABLE public.nw_usage_events
 ADD COLUMN group_binding_id bigint,
 ADD COLUMN requester_kind varchar(32),
 ADD COLUMN requester_ref text,
 ADD COLUMN origin_session_id text,
 ADD COLUMN origin_event_id text,
 ADD COLUMN responsibility_agent_id bigint,
 ADD COLUMN system_agent_run_id bigint,
 ADD COLUMN system_agent_configuration_ref text,
 ADD COLUMN system_agent_invocation_ref text,
 ADD CONSTRAINT usage_group_source_ck CHECK (
  (group_binding_id IS NULL OR group_binding_id>0)
  AND (responsibility_agent_id IS NULL OR responsibility_agent_id>0)
  AND (system_agent_run_id IS NULL OR system_agent_run_id>0)
  AND (requester_kind IS NULL)=(requester_ref IS NULL)
  AND (origin_session_id IS NULL)=(origin_event_id IS NULL)
  AND (requester_kind IS NULL OR requester_kind IN ('END_USER','SYSTEM'))
  AND (requester_ref IS NULL OR btrim(requester_ref)<>'')
  AND (origin_session_id IS NULL OR btrim(origin_session_id)<>'' AND btrim(origin_event_id)<>'')
  AND (system_agent_run_id IS NULL)=(system_agent_configuration_ref IS NULL)
  AND (system_agent_run_id IS NULL)=(system_agent_invocation_ref IS NULL)
  AND (system_agent_configuration_ref IS NULL OR btrim(system_agent_configuration_ref)<>'')
  AND (system_agent_invocation_ref IS NULL OR btrim(system_agent_invocation_ref)<>''));
COMMENT ON COLUMN public.nw_usage_events.group_binding_id IS '真实群生命周期身份；个人来源为空';
COMMENT ON COLUMN public.nw_usage_events.requester_kind IS '实际原请求者类别，后台任务不冒充来源作者';
COMMENT ON COLUMN public.nw_usage_events.requester_ref IS '实际原请求者身份，随原调用冻结';
COMMENT ON COLUMN public.nw_usage_events.origin_session_id IS '后台或群调用的正式来源Session';
COMMENT ON COLUMN public.nw_usage_events.origin_event_id IS '后台或群调用的正式来源事件';
COMMENT ON COLUMN public.nw_usage_events.responsibility_agent_id IS '承担本次后台执行责任的机构Agent，不替换实际系统Agent';
COMMENT ON COLUMN public.nw_usage_events.system_agent_run_id IS '已受理的系统用途Run精确引用';
COMMENT ON COLUMN public.nw_usage_events.system_agent_configuration_ref IS '系统用途原冻结配置引用，不复制配置正文';
COMMENT ON COLUMN public.nw_usage_events.system_agent_invocation_ref IS '系统用途原调用引用，不由当前配置重建';
CREATE INDEX skillforge_usage_events_group_source ON public.nw_usage_events(tenant_id,group_binding_id,id) WHERE group_binding_id IS NOT NULL;
CREATE UNIQUE INDEX memory_extraction_system_run_unique ON public.agent_runtime_memory_extraction_jobs(tenant_id,system_run_id)
 WHERE system_run_id IS NOT NULL;
CREATE UNIQUE INDEX system_agent_runs_tenant_identity ON public.nw_platform_system_agent_runs(tenant_id,id);
ALTER TABLE public.nw_usage_events ADD CONSTRAINT usage_system_run_tenant_fk
 FOREIGN KEY(tenant_id,system_agent_run_id) REFERENCES public.nw_platform_system_agent_runs(tenant_id,id) ON DELETE RESTRICT;
