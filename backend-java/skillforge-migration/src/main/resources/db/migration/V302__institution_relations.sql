ALTER TABLE public.nw_tenants ADD COLUMN relation_revision bigint NOT NULL DEFAULT 1 CHECK(relation_revision>0);
ALTER TABLE public.nw_tenants ADD CONSTRAINT skillforge_tenants_no_self_parent CHECK(parent_tenant_id IS DISTINCT FROM id);
COMMENT ON COLUMN public.nw_tenants.relation_revision IS '机构当前直属关系CAS修订；挂接、迁移或解除时递增';

CREATE TABLE public.nw_institution_tree_revision (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0)
);
INSERT INTO public.nw_institution_tree_revision(singleton,revision) VALUES(true,1);
COMMENT ON TABLE public.nw_institution_tree_revision IS '唯一机构树修订；只串行树写入，执行和目录读取不加锁';
COMMENT ON COLUMN public.nw_institution_tree_revision.singleton IS '唯一树版本行';
COMMENT ON COLUMN public.nw_institution_tree_revision.revision IS '成功机构关系变更的全树修订';

CREATE TABLE public.nw_institution_relation_revisions (
    id bigint PRIMARY KEY,
    child_tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    parent_tenant_id bigint REFERENCES public.nw_tenants(id),
    revision bigint NOT NULL CHECK(revision>0),
    tree_revision bigint NOT NULL CHECK(tree_revision>0),
    actor_ref varchar(160) NOT NULL,
    command_ref varchar(256) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(child_tenant_id,revision),
    CHECK(parent_tenant_id IS DISTINCT FROM child_tenant_id)
);
COMMENT ON TABLE public.nw_institution_relation_revisions IS '不可变直属关系修订；解除与迁移保留原依据供授权追溯';
COMMENT ON COLUMN public.nw_institution_relation_revisions.id IS '稳定关系依据雪花ID';
COMMENT ON COLUMN public.nw_institution_relation_revisions.child_tenant_id IS '本次关系修订所属机构';
COMMENT ON COLUMN public.nw_institution_relation_revisions.parent_tenant_id IS '本次直属父机构；解除为NULL';
COMMENT ON COLUMN public.nw_institution_relation_revisions.revision IS '机构精确关系修订';
COMMENT ON COLUMN public.nw_institution_relation_revisions.tree_revision IS '本次成功变更对应的树修订';
COMMENT ON COLUMN public.nw_institution_relation_revisions.actor_ref IS '已认证稳定操作者';
COMMENT ON COLUMN public.nw_institution_relation_revisions.command_ref IS '本次幂等命令键';
COMMENT ON COLUMN public.nw_institution_relation_revisions.recorded_at IS '数据库记录时间';
CREATE INDEX institution_relation_parent_history_idx ON public.nw_institution_relation_revisions(parent_tenant_id,child_tenant_id,revision);

CREATE TABLE public.nw_institution_relation_requests (
    actor_ref varchar(160) NOT NULL,
    operation varchar(64) NOT NULL,
    command_ref varchar(256) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    child_tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    result jsonb NOT NULL CHECK(jsonb_typeof(result)='object'),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(actor_ref,operation,command_ref)
);
COMMENT ON TABLE public.nw_institution_relation_requests IS '关系变更和直属开通的原成功安全回执；与关系及审计同事务提交';
COMMENT ON COLUMN public.nw_institution_relation_requests.actor_ref IS '稳定操作者，不含凭据版本';
COMMENT ON COLUMN public.nw_institution_relation_requests.operation IS '明确关系动作或直属开通';
COMMENT ON COLUMN public.nw_institution_relation_requests.command_ref IS '调用者幂等键';
COMMENT ON COLUMN public.nw_institution_relation_requests.fingerprint IS '类型化参数规范摘要';
COMMENT ON COLUMN public.nw_institution_relation_requests.child_tenant_id IS '原成功结果机构';
COMMENT ON COLUMN public.nw_institution_relation_requests.result IS '不可变Relation安全JSON；不含凭证、成员或业务数据';
COMMENT ON COLUMN public.nw_institution_relation_requests.recorded_at IS '数据库提交前记录时间';

CREATE TABLE public.nw_institution_management_grants (
    id bigint PRIMARY KEY,
    actor_domain varchar(16) NOT NULL CHECK(actor_domain='tenant'),
    actor_ref varchar(160) NOT NULL,
    target_tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    action varchar(96) NOT NULL CHECK(action='tenant.sub_institutions.read'),
    status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN('active','revoked')),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
    relation_ref bigint NOT NULL REFERENCES public.nw_institution_relation_revisions(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(actor_domain,actor_ref,target_tenant_id,action,relation_ref)
);
COMMENT ON TABLE public.nw_institution_management_grants IS '可信总部针对精确下级和关系修订的显式目录授权；不授角色、业务或费用权限';
COMMENT ON COLUMN public.nw_institution_management_grants.id IS '稳定目录管理授权ID';
COMMENT ON COLUMN public.nw_institution_management_grants.actor_domain IS '可信主体域；首版总部Tenant';
COMMENT ON COLUMN public.nw_institution_management_grants.actor_ref IS '可信总部稳定引用tenant:ID';
COMMENT ON COLUMN public.nw_institution_management_grants.target_tenant_id IS '明确目录目标机构';
COMMENT ON COLUMN public.nw_institution_management_grants.action IS '仅安全直属目录read动作';
COMMENT ON COLUMN public.nw_institution_management_grants.status IS '当前授权状态';
COMMENT ON COLUMN public.nw_institution_management_grants.revision IS '当前授权修订';
COMMENT ON COLUMN public.nw_institution_management_grants.relation_ref IS '必须与当前父及关系修订相同；重新挂接不复活旧授权';
COMMENT ON COLUMN public.nw_institution_management_grants.created_at IS '数据库创建时间';
CREATE INDEX institution_management_target_idx ON public.nw_institution_management_grants(actor_ref,target_tenant_id,status);

CREATE FUNCTION public.institution_tree_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM revision FROM public.nw_institution_tree_revision WHERE singleton FOR UPDATE;
    IF TG_OP='DELETE' THEN
        IF EXISTS(SELECT 1 FROM public.nw_tenants WHERE parent_tenant_id=OLD.id) THEN
            RAISE EXCEPTION 'institution_has_children' USING ERRCODE='23503';
        END IF;
        UPDATE public.nw_institution_tree_revision SET revision=revision+1 WHERE singleton;
        RETURN OLD;
    END IF;
    IF TG_OP='INSERT' THEN
        UPDATE public.nw_institution_tree_revision SET revision=revision+1 WHERE singleton;
    ELSIF NEW.parent_tenant_id IS DISTINCT FROM OLD.parent_tenant_id THEN
        UPDATE public.nw_institution_tree_revision SET revision=revision+1 WHERE singleton;
    END IF;
    IF NEW.parent_tenant_id IS NOT NULL AND EXISTS(
        WITH RECURSIVE ancestors(id,parent_tenant_id) AS (
            SELECT id,parent_tenant_id FROM public.nw_tenants WHERE id=NEW.parent_tenant_id
            UNION SELECT parent.id,parent.parent_tenant_id FROM public.nw_tenants parent JOIN ancestors a ON parent.id=a.parent_tenant_id)
        SELECT 1 FROM ancestors WHERE id=NEW.id) THEN
        RAISE EXCEPTION 'institution_tree_cycle' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
COMMENT ON FUNCTION public.institution_tree_integrity() IS '机构树写入串行和无环/有下级不可删除的数据库最终约束';
CREATE TRIGGER institution_tree_parent_integrity BEFORE INSERT OR UPDATE OF parent_tenant_id OR DELETE ON public.nw_tenants
FOR EACH ROW EXECUTE FUNCTION public.institution_tree_integrity();

CREATE FUNCTION public.initialize_mapped_institution_integration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE caller_tenant text := COALESCE(current_setting('app.tenant_id',true),'');
BEGIN
    -- 总部只能初始化本事务刚创建的直属机构；普通INSERT不能向既有下级或任意机构加执行域。
    IF caller_tenant<>'' AND NOT EXISTS(SELECT 1 FROM public.nw_tenants t
        WHERE t.id=NEW.tenant_id AND t.parent_tenant_id::text=caller_tenant
          AND t.xmin::text::bigint=mod(txid_current(),4294967296)
          AND (NEW.created_by LIKE ('tenant:'||caller_tenant||':member:%')
            OR EXISTS(SELECT 1 FROM public.nw_service_principals principal WHERE principal.tenant_id::text=caller_tenant
              AND NEW.created_by='service_principal:'||principal.principal_id AND principal.status='active'
              AND principal.scopes @> '["tenant.sub_institutions.provision"]'::jsonb))
          AND NEW.provision_fingerprint IS NOT NULL) THEN
        RAISE EXCEPTION 'institution_mapping_initialization_denied' USING ERRCODE='42501';
    END IF;
    PERFORM set_config('app.tenant_id',NEW.tenant_id::text,true);
    INSERT INTO public.nw_institution_integrations(tenant_id,integration_id)
        VALUES(NEW.tenant_id,NEW.integration_id) ON CONFLICT DO NOTHING;
    PERFORM set_config('app.tenant_id',caller_tenant,true);
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config('app.tenant_id',caller_tenant,true);
    RAISE;
END $$;
COMMENT ON FUNCTION public.initialize_mapped_institution_integration() IS '映射插入仅原子初始化其固定执行域；总部Java保持原Tenant且不签发凭证或成员';
CREATE TRIGGER institution_mapping_integration_initialization AFTER INSERT ON public.nw_external_institution_mappings
FOR EACH ROW EXECUTE FUNCTION public.initialize_mapped_institution_integration();

CREATE FUNCTION public.reject_institution_relation_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'institution_relation_history_is_immutable' USING ERRCODE='23514'; END $$;
COMMENT ON FUNCTION public.reject_institution_relation_history_change() IS '已提交关系修订和幂等回执只可追加';
CREATE TRIGGER skillforge_institution_relation_revisions_immutable BEFORE UPDATE OR DELETE ON public.nw_institution_relation_revisions
FOR EACH ROW EXECUTE FUNCTION public.reject_institution_relation_history_change();
CREATE TRIGGER skillforge_institution_relation_requests_immutable BEFORE UPDATE OR DELETE ON public.nw_institution_relation_requests
FOR EACH ROW EXECUTE FUNCTION public.reject_institution_relation_history_change();
GRANT SELECT,UPDATE ON public.nw_institution_tree_revision TO skillforge;
GRANT SELECT,INSERT ON public.nw_institution_relation_revisions,public.nw_institution_relation_requests TO skillforge;
GRANT SELECT,INSERT,UPDATE ON public.nw_institution_management_grants TO skillforge;
