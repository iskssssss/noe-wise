-- 峰谷规则由配置数据维护；模型和预设均引用精确的不可变关系版本。
CREATE TABLE nw_model_time_pricing_rules (
 id bigint NOT NULL,
 time_zone text NOT NULL,
 valid_from date NOT NULL,
 valid_through date NOT NULL,
 peak_multiplier numeric(24,12),
 off_peak_multiplier numeric(24,12),
 source_url text,
 source_version varchar(256),
 calendar_source_url text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_transaction xid8 NOT NULL DEFAULT pg_current_xact_id(),
 CONSTRAINT nw_model_time_pricing_rules_pkey PRIMARY KEY(id),
 CONSTRAINT nw_model_time_pricing_rules_dates_check CHECK(valid_through>=valid_from),
 CONSTRAINT nw_model_time_pricing_rules_peak_check CHECK(peak_multiplier>=0),
 CONSTRAINT nw_model_time_pricing_rules_off_peak_check CHECK(off_peak_multiplier>=0)
);
CREATE TABLE nw_model_time_pricing_weekdays (
 rule_id bigint NOT NULL,
 ordinal integer NOT NULL,
 weekday varchar(16) NOT NULL,
 CONSTRAINT nw_model_time_pricing_weekdays_pkey PRIMARY KEY(rule_id,ordinal),
 CONSTRAINT nw_model_time_pricing_weekdays_rule_fk FOREIGN KEY(rule_id) REFERENCES nw_model_time_pricing_rules(id),
 CONSTRAINT nw_model_time_pricing_weekdays_day_uq UNIQUE(rule_id,weekday),
 CONSTRAINT nw_model_time_pricing_weekdays_ordinal_check CHECK(ordinal>=0),
 CONSTRAINT nw_model_time_pricing_weekdays_day_check CHECK(weekday IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'))
);
CREATE TABLE nw_model_time_pricing_periods (
 rule_id bigint NOT NULL,
 ordinal integer NOT NULL,
 start_nanos bigint NOT NULL,
 end_nanos bigint NOT NULL,
 CONSTRAINT nw_model_time_pricing_periods_pkey PRIMARY KEY(rule_id,ordinal),
 CONSTRAINT nw_model_time_pricing_periods_rule_fk FOREIGN KEY(rule_id) REFERENCES nw_model_time_pricing_rules(id),
 CONSTRAINT nw_model_time_pricing_periods_ordinal_check CHECK(ordinal>=0),
 CONSTRAINT nw_model_time_pricing_periods_time_check CHECK(start_nanos>=0 AND end_nanos>start_nanos AND end_nanos<=86399999999999)
);
CREATE TABLE nw_model_time_pricing_dates (
 rule_id bigint NOT NULL,
 band varchar(16) NOT NULL,
 ordinal integer NOT NULL,
 starts_on date NOT NULL,
 ends_on date NOT NULL,
 CONSTRAINT nw_model_time_pricing_dates_pkey PRIMARY KEY(rule_id,band,ordinal),
 CONSTRAINT nw_model_time_pricing_dates_rule_fk FOREIGN KEY(rule_id) REFERENCES nw_model_time_pricing_rules(id),
 CONSTRAINT nw_model_time_pricing_dates_band_check CHECK(band IN ('PEAK','OFF_PEAK')),
 CONSTRAINT nw_model_time_pricing_dates_ordinal_check CHECK(ordinal>=0),
 CONSTRAINT nw_model_time_pricing_dates_dates_check CHECK(ends_on>=starts_on)
);
CREATE TABLE nw_model_time_pricing_scopes (
 rule_id bigint NOT NULL,
 ordinal integer NOT NULL,
 provider_id varchar(128),
 service_id varchar(128),
 profile_version varchar(128),
 model_id text,
 CONSTRAINT nw_model_time_pricing_scopes_pkey PRIMARY KEY(rule_id,ordinal),
 CONSTRAINT nw_model_time_pricing_scopes_rule_fk FOREIGN KEY(rule_id) REFERENCES nw_model_time_pricing_rules(id),
 CONSTRAINT nw_model_time_pricing_scopes_ordinal_check CHECK(ordinal>=0),
 CONSTRAINT nw_model_time_pricing_scopes_provider_check CHECK(provider_id IS NULL OR btrim(provider_id)<>''),
 CONSTRAINT nw_model_time_pricing_scopes_service_check CHECK(service_id IS NULL OR btrim(service_id)<>''),
 CONSTRAINT nw_model_time_pricing_scopes_profile_check CHECK(profile_version IS NULL OR btrim(profile_version)<>''),
 CONSTRAINT nw_model_time_pricing_scopes_model_check CHECK(model_id IS NULL OR btrim(model_id)<>'')
);
CREATE TABLE nw_model_time_pricing_presets (
 id bigint NOT NULL,
 name varchar(128) NOT NULL,
 draft_rule_id bigint NOT NULL,
 draft_revision bigint NOT NULL DEFAULT 1,
 published_revision bigint,
 draft_changed boolean NOT NULL DEFAULT TRUE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_by text NOT NULL,
 updated_by text NOT NULL,
 CONSTRAINT nw_model_time_pricing_presets_pkey PRIMARY KEY(id),
 CONSTRAINT nw_model_time_pricing_presets_rule_fk FOREIGN KEY(draft_rule_id) REFERENCES nw_model_time_pricing_rules(id),
 CONSTRAINT nw_model_time_pricing_presets_revision_check CHECK(draft_revision>0 AND (published_revision IS NULL OR published_revision>0)),
 CONSTRAINT nw_model_time_pricing_presets_name_check CHECK(btrim(name)<>'')
);
CREATE TABLE nw_model_time_pricing_preset_versions (
 preset_id bigint NOT NULL,
 revision bigint NOT NULL,
 name varchar(128) NOT NULL,
 rule_id bigint NOT NULL,
 published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 published_by text NOT NULL,
 CONSTRAINT nw_model_time_pricing_preset_versions_pkey PRIMARY KEY(preset_id,revision),
 CONSTRAINT nw_model_time_pricing_preset_versions_preset_fk FOREIGN KEY(preset_id) REFERENCES nw_model_time_pricing_presets(id),
 CONSTRAINT nw_model_time_pricing_preset_versions_rule_fk FOREIGN KEY(rule_id) REFERENCES nw_model_time_pricing_rules(id),
 CONSTRAINT nw_model_time_pricing_preset_versions_revision_check CHECK(revision>0),
 CONSTRAINT nw_model_time_pricing_preset_versions_name_check CHECK(btrim(name)<>'')
);
ALTER TABLE nw_model_time_pricing_presets ADD CONSTRAINT nw_model_time_pricing_presets_publication_fk
 FOREIGN KEY(id,published_revision) REFERENCES nw_model_time_pricing_preset_versions(preset_id,revision) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE nw_platform_models ADD COLUMN time_pricing_rule_id bigint;
ALTER TABLE nw_platform_models ADD CONSTRAINT nw_platform_models_time_pricing_rule_fk FOREIGN KEY(time_pricing_rule_id) REFERENCES nw_model_time_pricing_rules(id);
ALTER TABLE nw_platform_model_versions ADD COLUMN time_pricing_rule_id bigint;
ALTER TABLE nw_platform_model_versions ADD CONSTRAINT nw_platform_model_versions_time_pricing_rule_fk FOREIGN KEY(time_pricing_rule_id) REFERENCES nw_model_time_pricing_rules(id);
COMMENT ON COLUMN nw_platform_models.time_pricing_rule_id IS '工作草稿峰谷规则的精确不可变引用；NULL表示不启用';
COMMENT ON COLUMN nw_platform_model_versions.time_pricing_rule_id IS '发布时冻结的峰谷规则精确引用；NULL表示不启用，后续预设发布不改变该引用';

-- 供应商初始预设仅在迁移时创建；后续维护通过管理接口保存与发布，无启动覆盖。
INSERT INTO nw_model_time_pricing_rules(id,time_zone,valid_from,valid_through,peak_multiplier,off_peak_multiplier,source_url,source_version,calendar_source_url)
 VALUES(863700000000001,'Asia/Shanghai','2026-01-01','2026-12-31',1,0.5,'https://api-docs.deepseek.com/quick_start/pricing/','DeepSeek 2026-10-26 / 国办发明电〔2025〕7号','https://www.wuhan.gov.cn/zwgk/tzgg/202511/t20251105_2672471.shtml');
INSERT INTO nw_model_time_pricing_weekdays(rule_id,ordinal,weekday) VALUES
 (863700000000001,0,'MONDAY'),(863700000000001,1,'TUESDAY'),(863700000000001,2,'WEDNESDAY'),(863700000000001,3,'THURSDAY'),(863700000000001,4,'FRIDAY');
INSERT INTO nw_model_time_pricing_periods(rule_id,ordinal,start_nanos,end_nanos) VALUES
 (863700000000001,0,32400000000000,43200000000000),(863700000000001,1,50400000000000,64800000000000);
INSERT INTO nw_model_time_pricing_dates(rule_id,band,ordinal,starts_on,ends_on) VALUES
 (863700000000001,'OFF_PEAK',0,'2026-01-01','2026-01-03'),
 (863700000000001,'OFF_PEAK',1,'2026-02-15','2026-02-23'),
 (863700000000001,'OFF_PEAK',2,'2026-04-04','2026-04-06'),
 (863700000000001,'OFF_PEAK',3,'2026-05-01','2026-05-05'),
 (863700000000001,'OFF_PEAK',4,'2026-06-19','2026-06-21'),
 (863700000000001,'OFF_PEAK',5,'2026-09-25','2026-09-27'),
 (863700000000001,'OFF_PEAK',6,'2026-10-01','2026-10-07');
INSERT INTO nw_model_time_pricing_scopes(rule_id,ordinal,provider_id,service_id,profile_version,model_id) VALUES
 (863700000000001,0,'deepseek','deepseek-api','2026-09-13','deepseek-flash'),
 (863700000000001,1,'deepseek','deepseek-api','2026-09-13','deepseek-v4-pro'),
 (863700000000001,2,'deepseek','deepseek-api','2026-09-13','deepseek-v4-flash'),
 (863700000000001,3,'deepseek','deepseek-api','2026-09-13','deepseek-v4-flash-vision-exp');
INSERT INTO nw_model_time_pricing_presets(id,name,draft_rule_id,published_revision,draft_changed,created_by,updated_by)
 VALUES(863700000000002,'DeepSeek 繁忙/闲时（2026）',863700000000001,1,FALSE,'system:V340','system:V340');
INSERT INTO nw_model_time_pricing_preset_versions(preset_id,revision,name,rule_id,published_by)
 VALUES(863700000000002,1,'DeepSeek 繁忙/闲时（2026）',863700000000001,'system:V340');

-- 种子的发布指针和版本已齐备，先完成其延迟外键检查，再安装表级安全策略。
-- 恢复默认延迟语义，正常发布仍能在同一事务原子推进指针并追加版本。
SET CONSTRAINTS nw_model_time_pricing_presets_publication_fk IMMEDIATE;
SET CONSTRAINTS nw_model_time_pricing_presets_publication_fk DEFERRED;

CREATE FUNCTION nw_model_time_pricing_child_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM nw_model_time_pricing_rules WHERE id=NEW.rule_id AND created_transaction=pg_current_xact_id()) THEN
  RAISE EXCEPTION 'configuration_version_immutable';
 END IF;
 RETURN NEW;
END $$;
COMMENT ON FUNCTION nw_model_time_pricing_child_insert() IS '规则明细只允许在规则创建的同一事务写入，禁止事后给历史规则追加条件';
COMMENT ON TABLE nw_model_time_pricing_rules IS '不可变峰谷规则，内容由平台配置数据维护';
COMMENT ON COLUMN nw_model_time_pricing_rules.id IS '规则雪花身份';
COMMENT ON COLUMN nw_model_time_pricing_rules.time_zone IS '规则IANA时区';
COMMENT ON COLUMN nw_model_time_pricing_rules.valid_from IS '适用起始日期，包含当日';
COMMENT ON COLUMN nw_model_time_pricing_rules.valid_through IS '适用结束日期，包含当日';
COMMENT ON COLUMN nw_model_time_pricing_rules.peak_multiplier IS '繁忙倍率；NULL表示未知';
COMMENT ON COLUMN nw_model_time_pricing_rules.off_peak_multiplier IS '闲时倍率；NULL表示未知';
COMMENT ON COLUMN nw_model_time_pricing_rules.source_url IS '供应商价格规则来源地址；NULL未提供';
COMMENT ON COLUMN nw_model_time_pricing_rules.source_version IS '来源版本描述；NULL未提供';
COMMENT ON COLUMN nw_model_time_pricing_rules.calendar_source_url IS '日历来源地址；NULL未提供';
COMMENT ON COLUMN nw_model_time_pricing_rules.created_at IS '规则保存时刻';
COMMENT ON COLUMN nw_model_time_pricing_rules.created_transaction IS '规则创建事务，仅用于明细不可变约束';
CREATE TRIGGER nw_model_time_pricing_rules_immutable BEFORE UPDATE OR DELETE ON nw_model_time_pricing_rules FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
ALTER TABLE nw_model_time_pricing_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_rules_read ON nw_model_time_pricing_rules FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_rules_admin ON nw_model_time_pricing_rules FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_rules FROM PUBLIC;
COMMENT ON TABLE nw_model_time_pricing_weekdays IS '规则的繁忙星期列表';
COMMENT ON COLUMN nw_model_time_pricing_weekdays.rule_id IS '所属不可变规则';
COMMENT ON COLUMN nw_model_time_pricing_weekdays.ordinal IS '列表顺序，从0开始';
COMMENT ON COLUMN nw_model_time_pricing_weekdays.weekday IS '星期稳定名称';
CREATE TRIGGER nw_model_time_pricing_weekdays_immutable BEFORE UPDATE OR DELETE ON nw_model_time_pricing_weekdays FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
CREATE TRIGGER nw_model_time_pricing_weekdays_insert BEFORE INSERT ON nw_model_time_pricing_weekdays FOR EACH ROW EXECUTE FUNCTION nw_model_time_pricing_child_insert();
ALTER TABLE nw_model_time_pricing_weekdays ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_weekdays FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_weekdays_read ON nw_model_time_pricing_weekdays FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_weekdays_admin ON nw_model_time_pricing_weekdays FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_weekdays FROM PUBLIC;
COMMENT ON TABLE nw_model_time_pricing_periods IS '规则的每日繁忙时段，左闭右开';
COMMENT ON COLUMN nw_model_time_pricing_periods.rule_id IS '所属不可变规则';
COMMENT ON COLUMN nw_model_time_pricing_periods.ordinal IS '列表顺序，从0开始';
COMMENT ON COLUMN nw_model_time_pricing_periods.start_nanos IS '当日起始纳秒，包含此时刻';
COMMENT ON COLUMN nw_model_time_pricing_periods.end_nanos IS '当日结束纳秒，不含此时刻；保存纳秒精度避免time微秒截断';
CREATE TRIGGER nw_model_time_pricing_periods_immutable BEFORE UPDATE OR DELETE ON nw_model_time_pricing_periods FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
CREATE TRIGGER nw_model_time_pricing_periods_insert BEFORE INSERT ON nw_model_time_pricing_periods FOR EACH ROW EXECUTE FUNCTION nw_model_time_pricing_child_insert();
ALTER TABLE nw_model_time_pricing_periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_periods FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_periods_read ON nw_model_time_pricing_periods FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_periods_admin ON nw_model_time_pricing_periods FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_periods FROM PUBLIC;
COMMENT ON TABLE nw_model_time_pricing_dates IS '规则的额外繁忙日与整日闲时日期区间';
COMMENT ON COLUMN nw_model_time_pricing_dates.rule_id IS '所属不可变规则';
COMMENT ON COLUMN nw_model_time_pricing_dates.band IS 'PEAK为额外繁忙日，仍应用每日时段；OFF_PEAK为整日闲时';
COMMENT ON COLUMN nw_model_time_pricing_dates.ordinal IS '同一时段类别内的列表顺序，从0开始';
COMMENT ON COLUMN nw_model_time_pricing_dates.starts_on IS '区间起始日期，包含当日';
COMMENT ON COLUMN nw_model_time_pricing_dates.ends_on IS '区间结束日期，包含当日';
CREATE TRIGGER nw_model_time_pricing_dates_immutable BEFORE UPDATE OR DELETE ON nw_model_time_pricing_dates FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
CREATE TRIGGER nw_model_time_pricing_dates_insert BEFORE INSERT ON nw_model_time_pricing_dates FOR EACH ROW EXECUTE FUNCTION nw_model_time_pricing_child_insert();
ALTER TABLE nw_model_time_pricing_dates ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_dates FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_dates_read ON nw_model_time_pricing_dates FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_dates_admin ON nw_model_time_pricing_dates FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_dates FROM PUBLIC;
COMMENT ON TABLE nw_model_time_pricing_scopes IS '规则适用的模型匹配范围；无明细表示所有LLM';
COMMENT ON COLUMN nw_model_time_pricing_scopes.rule_id IS '所属不可变规则';
COMMENT ON COLUMN nw_model_time_pricing_scopes.ordinal IS '匹配范围列表顺序，从0开始；多行逻辑或';
COMMENT ON COLUMN nw_model_time_pricing_scopes.provider_id IS '服务商编码；NULL不限';
COMMENT ON COLUMN nw_model_time_pricing_scopes.service_id IS '服务档案编码；NULL不限';
COMMENT ON COLUMN nw_model_time_pricing_scopes.profile_version IS '服务档案版本；NULL不限';
COMMENT ON COLUMN nw_model_time_pricing_scopes.model_id IS '模型外部编码；NULL不限';
CREATE TRIGGER nw_model_time_pricing_scopes_immutable BEFORE UPDATE OR DELETE ON nw_model_time_pricing_scopes FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
CREATE TRIGGER nw_model_time_pricing_scopes_insert BEFORE INSERT ON nw_model_time_pricing_scopes FOR EACH ROW EXECUTE FUNCTION nw_model_time_pricing_child_insert();
ALTER TABLE nw_model_time_pricing_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_scopes FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_scopes_read ON nw_model_time_pricing_scopes FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_scopes_admin ON nw_model_time_pricing_scopes FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_scopes FROM PUBLIC;
COMMENT ON TABLE nw_model_time_pricing_presets IS '平台价格规则预设的工作草稿与发布指针';
COMMENT ON COLUMN nw_model_time_pricing_presets.id IS '预设雪花身份';
COMMENT ON COLUMN nw_model_time_pricing_presets.name IS '预设名称';
COMMENT ON COLUMN nw_model_time_pricing_presets.draft_rule_id IS '草稿引用的不可变规则';
COMMENT ON COLUMN nw_model_time_pricing_presets.draft_revision IS '草稿保存CAS修订号';
COMMENT ON COLUMN nw_model_time_pricing_presets.published_revision IS '当前发布版本；NULL表示尚未发布';
COMMENT ON COLUMN nw_model_time_pricing_presets.draft_changed IS '存在未发布修改';
COMMENT ON COLUMN nw_model_time_pricing_presets.created_at IS '创建时刻';
COMMENT ON COLUMN nw_model_time_pricing_presets.updated_at IS '最后保存或发布时刻';
COMMENT ON COLUMN nw_model_time_pricing_presets.created_by IS '可信创建操作者身份';
COMMENT ON COLUMN nw_model_time_pricing_presets.updated_by IS '可信最后修改操作者身份';
ALTER TABLE nw_model_time_pricing_presets ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_presets FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_presets_read ON nw_model_time_pricing_presets FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_presets_admin ON nw_model_time_pricing_presets FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_presets FROM PUBLIC;
COMMENT ON TABLE nw_model_time_pricing_preset_versions IS '平台价格规则预设的不可变发布历史';
COMMENT ON COLUMN nw_model_time_pricing_preset_versions.preset_id IS '稳定预设身份';
COMMENT ON COLUMN nw_model_time_pricing_preset_versions.revision IS '发布修订号';
COMMENT ON COLUMN nw_model_time_pricing_preset_versions.name IS '发布时预设名称';
COMMENT ON COLUMN nw_model_time_pricing_preset_versions.rule_id IS '发布时冻结的不可变规则';
COMMENT ON COLUMN nw_model_time_pricing_preset_versions.published_at IS '发布时刻';
COMMENT ON COLUMN nw_model_time_pricing_preset_versions.published_by IS '可信发布操作者身份';
CREATE TRIGGER nw_model_time_pricing_preset_versions_immutable BEFORE UPDATE OR DELETE ON nw_model_time_pricing_preset_versions FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
ALTER TABLE nw_model_time_pricing_preset_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_model_time_pricing_preset_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY nw_model_time_pricing_preset_versions_read ON nw_model_time_pricing_preset_versions FOR SELECT USING(TRUE);
CREATE POLICY nw_model_time_pricing_preset_versions_admin ON nw_model_time_pricing_preset_versions FOR ALL USING(current_setting('app.platform_admin',TRUE)='true') WITH CHECK(current_setting('app.platform_admin',TRUE)='true');
REVOKE ALL ON nw_model_time_pricing_preset_versions FROM PUBLIC;
DO $$ DECLARE principal record; BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge_platform') THEN
  REVOKE ALL ON nw_model_time_pricing_rules,nw_model_time_pricing_weekdays,nw_model_time_pricing_periods,nw_model_time_pricing_dates,nw_model_time_pricing_scopes,nw_model_time_pricing_presets,nw_model_time_pricing_preset_versions FROM skillforge_platform;
  GRANT SELECT,INSERT,UPDATE ON nw_model_time_pricing_rules,nw_model_time_pricing_weekdays,nw_model_time_pricing_periods,nw_model_time_pricing_dates,nw_model_time_pricing_scopes,nw_model_time_pricing_presets,nw_model_time_pricing_preset_versions TO skillforge_platform;
 END IF;
 FOR principal IN SELECT rolname FROM pg_roles WHERE rolname LIKE 'skillforge_institution%' LOOP
  EXECUTE format('REVOKE ALL ON nw_model_time_pricing_rules,nw_model_time_pricing_weekdays,nw_model_time_pricing_periods,nw_model_time_pricing_dates,nw_model_time_pricing_scopes,nw_model_time_pricing_presets,nw_model_time_pricing_preset_versions FROM %I',principal.rolname);
  EXECUTE format('GRANT SELECT ON nw_model_time_pricing_rules,nw_model_time_pricing_weekdays,nw_model_time_pricing_periods,nw_model_time_pricing_dates,nw_model_time_pricing_scopes,nw_model_time_pricing_presets,nw_model_time_pricing_preset_versions TO %I',principal.rolname);
 END LOOP;
END $$;
COMMENT ON CONSTRAINT nw_model_time_pricing_rules_pkey ON nw_model_time_pricing_rules IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_rules_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_rules_dates_check ON nw_model_time_pricing_rules IS '适用日期区间按起始到结束有序';
COMMENT ON CONSTRAINT nw_model_time_pricing_rules_peak_check ON nw_model_time_pricing_rules IS '已知繁忙倍率不得为负';
COMMENT ON CONSTRAINT nw_model_time_pricing_rules_off_peak_check ON nw_model_time_pricing_rules IS '已知闲时倍率不得为负';
COMMENT ON CONSTRAINT nw_model_time_pricing_weekdays_pkey ON nw_model_time_pricing_weekdays IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_weekdays_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_weekdays_rule_fk ON nw_model_time_pricing_weekdays IS '关联精确不可变规则，禁止引用缺失版本';
COMMENT ON CONSTRAINT nw_model_time_pricing_weekdays_day_uq ON nw_model_time_pricing_weekdays IS '同一规则内星期不能重复';
COMMENT ON INDEX nw_model_time_pricing_weekdays_day_uq IS '同一规则内星期不能重复';
COMMENT ON CONSTRAINT nw_model_time_pricing_weekdays_ordinal_check ON nw_model_time_pricing_weekdays IS '列表位置不得为负';
COMMENT ON CONSTRAINT nw_model_time_pricing_weekdays_day_check ON nw_model_time_pricing_weekdays IS '星期仅允许稳定名称';
COMMENT ON CONSTRAINT nw_model_time_pricing_periods_pkey ON nw_model_time_pricing_periods IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_periods_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_periods_rule_fk ON nw_model_time_pricing_periods IS '关联精确不可变规则，禁止引用缺失版本';
COMMENT ON CONSTRAINT nw_model_time_pricing_periods_ordinal_check ON nw_model_time_pricing_periods IS '列表位置不得为负';
COMMENT ON CONSTRAINT nw_model_time_pricing_periods_time_check ON nw_model_time_pricing_periods IS '单日纳秒范围及左闭右开时段合法';
COMMENT ON CONSTRAINT nw_model_time_pricing_dates_pkey ON nw_model_time_pricing_dates IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_dates_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_dates_rule_fk ON nw_model_time_pricing_dates IS '关联精确不可变规则，禁止引用缺失版本';
COMMENT ON CONSTRAINT nw_model_time_pricing_dates_band_check ON nw_model_time_pricing_dates IS '日期范围只属于繁忙或闲时';
COMMENT ON CONSTRAINT nw_model_time_pricing_dates_ordinal_check ON nw_model_time_pricing_dates IS '列表位置不得为负';
COMMENT ON CONSTRAINT nw_model_time_pricing_dates_dates_check ON nw_model_time_pricing_dates IS '适用日期区间按起始到结束有序';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_pkey ON nw_model_time_pricing_scopes IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_scopes_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_rule_fk ON nw_model_time_pricing_scopes IS '关联精确不可变规则，禁止引用缺失版本';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_ordinal_check ON nw_model_time_pricing_scopes IS '列表位置不得为负';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_provider_check ON nw_model_time_pricing_scopes IS '服务商范围为空或非空白';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_service_check ON nw_model_time_pricing_scopes IS '服务范围为空或非空白';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_profile_check ON nw_model_time_pricing_scopes IS '档案版本范围为空或非空白';
COMMENT ON CONSTRAINT nw_model_time_pricing_scopes_model_check ON nw_model_time_pricing_scopes IS '模型范围为空或非空白';
COMMENT ON CONSTRAINT nw_model_time_pricing_presets_pkey ON nw_model_time_pricing_presets IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_presets_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_presets_rule_fk ON nw_model_time_pricing_presets IS '关联精确不可变规则，禁止引用缺失版本';
COMMENT ON CONSTRAINT nw_model_time_pricing_presets_revision_check ON nw_model_time_pricing_presets IS '草稿和发布修订必须为正数';
COMMENT ON CONSTRAINT nw_model_time_pricing_presets_name_check ON nw_model_time_pricing_presets IS '预设显示名称不得为空白';
COMMENT ON CONSTRAINT nw_model_time_pricing_preset_versions_pkey ON nw_model_time_pricing_preset_versions IS '身份或规则内明细位置的唯一性';
COMMENT ON INDEX nw_model_time_pricing_preset_versions_pkey IS '身份或规则内明细位置的唯一性';
COMMENT ON CONSTRAINT nw_model_time_pricing_preset_versions_preset_fk ON nw_model_time_pricing_preset_versions IS '关联所属稳定预设';
COMMENT ON CONSTRAINT nw_model_time_pricing_preset_versions_rule_fk ON nw_model_time_pricing_preset_versions IS '关联精确不可变规则，禁止引用缺失版本';
COMMENT ON CONSTRAINT nw_model_time_pricing_preset_versions_revision_check ON nw_model_time_pricing_preset_versions IS '草稿和发布修订必须为正数';
COMMENT ON CONSTRAINT nw_model_time_pricing_preset_versions_name_check ON nw_model_time_pricing_preset_versions IS '预设显示名称不得为空白';
COMMENT ON CONSTRAINT nw_model_time_pricing_presets_publication_fk ON nw_model_time_pricing_presets IS '当前发布指针必须引用同一预设的不可变发布版本；事务结束校验';
COMMENT ON CONSTRAINT nw_platform_models_time_pricing_rule_fk ON nw_platform_models IS '草稿只引用已存在的不可变峰谷规则';
COMMENT ON CONSTRAINT nw_platform_model_versions_time_pricing_rule_fk ON nw_platform_model_versions IS '发布版本只引用已存在的不可变峰谷规则';
