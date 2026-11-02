-- Agent Skills default 指针只参与后续版本选择；运行挂载仍保存精确 version_id。
ALTER TABLE public.nw_capability_versions
    ADD CONSTRAINT uq_skillforge_capability_versions_id_definition UNIQUE (id, definition_id);

ALTER TABLE public.nw_capability_definitions
    ADD COLUMN default_version_id bigint,
    ADD CONSTRAINT capability_definition_default_version_fkey
        FOREIGN KEY (default_version_id, id)
        REFERENCES public.nw_capability_versions (id, definition_id)
        ON DELETE RESTRICT;

CREATE FUNCTION public.validate_skill_default_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    version_type varchar(32);
    version_status varchar(32);
BEGIN
    IF NEW.default_version_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.capability_type <> 'SKILL' THEN
        RAISE EXCEPTION 'default version pointer is only valid for SKILL definitions';
    END IF;
    SELECT capability_type, status INTO version_type, version_status
      FROM public.nw_capability_versions
     WHERE id = NEW.default_version_id AND definition_id = NEW.id;
    IF NOT FOUND OR version_type <> 'SKILL' OR version_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'skill default version must reference a published version of the same definition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER skill_default_version_guard
    BEFORE INSERT OR UPDATE OF default_version_id, capability_type
    ON public.nw_capability_definitions
    FOR EACH ROW EXECUTE FUNCTION public.validate_skill_default_version();

COMMENT ON COLUMN public.nw_capability_definitions.default_version_id IS
    'SKILL 的默认已发布版本指针；只用于新选择，不改变已有精确挂载或冻结快照';
