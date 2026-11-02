CREATE TABLE public.nw_platform_agent_tool_receipts (
    actor varchar(128) NOT NULL CHECK (btrim(actor) <> ''),
    request_id uuid NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor, request_id)
);
ALTER TABLE public.nw_platform_agent_tool_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_agent_tool_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_agent_tool_receipts_admin ON public.nw_platform_agent_tool_receipts
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
GRANT SELECT, INSERT, UPDATE ON public.nw_platform_agent_tool_receipts TO skillforge;
CREATE FUNCTION public.platform_agent_tool_receipt_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.nw_platform_agent_tool_receipts
        WHERE actor = NEW.actor AND request_id = NEW.request_id AND response IS NULL) THEN
        RAISE EXCEPTION 'platform_agent_tool_receipt_incomplete';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER platform_agent_tool_receipt_complete
    AFTER INSERT OR UPDATE ON public.nw_platform_agent_tool_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.platform_agent_tool_receipt_complete();
CREATE FUNCTION public.platform_agent_tool_receipt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.response IS NOT NULL OR NEW.actor <> OLD.actor OR NEW.request_id <> OLD.request_id
        OR NEW.fingerprint <> OLD.fingerprint THEN
        RAISE EXCEPTION 'platform_agent_tool_receipt_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER platform_agent_tool_receipt_immutable BEFORE UPDATE ON public.nw_platform_agent_tool_receipts
    FOR EACH ROW EXECUTE FUNCTION public.platform_agent_tool_receipt_immutable();
COMMENT ON TABLE public.nw_platform_agent_tool_receipts IS '系统工具部署原请求回执，与写入事务共同提交';
