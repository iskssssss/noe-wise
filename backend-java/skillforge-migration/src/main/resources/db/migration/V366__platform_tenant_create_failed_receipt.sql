ALTER TABLE public.nw_platform_tenant_create_receipts DROP CONSTRAINT nw_platform_tenant_create_receipts_check;
ALTER TABLE public.nw_platform_tenant_create_receipts ADD CONSTRAINT nw_platform_tenant_create_receipts_check
    CHECK ((status = 'PROCESSING' AND tenant_id IS NULL AND tenant_name IS NULL AND error_message IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND tenant_id IS NOT NULL AND tenant_name IS NOT NULL AND error_message IS NULL AND completed_at IS NOT NULL)
        OR (status IN ('REJECTED', 'FAILED') AND tenant_id IS NULL AND tenant_name IS NULL AND error_message IS NOT NULL AND completed_at IS NOT NULL));
