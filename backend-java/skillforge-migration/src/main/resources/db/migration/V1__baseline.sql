--
-- PostgreSQL database dump
--


-- Dumped from database version 16.14 (Debian 16.14-1.pgdg12+1)
-- Dumped by pg_dump version 16.14 (Debian 16.14-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--



--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--



SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: agent_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_agent_templates (
    id bigint NOT NULL,
    template_key character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    version character varying(32) NOT NULL,
    category character varying(64),
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    description text,
    icon_url character varying(512),
    author character varying(128),
    author_type character varying(32) DEFAULT 'platform'::character varying NOT NULL,
    definition jsonb NOT NULL,
    config_schema jsonb DEFAULT '[]'::jsonb NOT NULL,
    required_permissions jsonb DEFAULT '[]'::jsonb NOT NULL,
    price_type character varying(32) DEFAULT 'free'::character varying NOT NULL,
    price_cents integer DEFAULT 0 NOT NULL,
    install_count integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'draft'::character varying NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE agent_templates; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.template_key; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.version; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.category; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.tags; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.description; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.icon_url; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.author; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.author_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.definition; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.config_schema; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.required_permissions; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.price_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.price_cents; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.install_count; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.published_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agent_templates.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: agents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_agents (
    id bigint NOT NULL,
    agent_key character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    template_id bigint,
    template_version character varying(32),
    name character varying(128) NOT NULL,
    system_prompt text,
    role_description text,
    planning_rules jsonb,
    llm_endpoint character varying(512),
    llm_model character varying(128),
    llm_api_key_encrypted text,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(32) DEFAULT 'draft'::character varying NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_agents FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE agents; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.agent_key; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.template_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.template_version; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.system_prompt; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.role_description; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.planning_rules; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.llm_endpoint; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.llm_model; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.llm_api_key_encrypted; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.config; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.published_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN agents.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_audit_logs (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    actor_id character varying(128),
    action character varying(64) NOT NULL,
    resource_type character varying(64),
    resource_id character varying(128),
    message text,
    trace_id character varying(64),
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_audit_logs FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE audit_logs; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.actor_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.action; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.resource_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.resource_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.message; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.trace_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.payload; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN audit_logs.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: channel_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_channel_identities (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    end_user_id bigint NOT NULL,
    channel_type character varying(32) NOT NULL,
    channel_user_id character varying(128) NOT NULL,
    raw_claims jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_channel_identities FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE channel_identities; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.end_user_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.channel_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.channel_user_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.raw_claims; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channel_identities.last_seen_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: channels; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_channels (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    channel_type character varying(32) NOT NULL,
    name character varying(128) NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    ui_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    channel_token_hash character varying(128) NOT NULL,
    channel_token_prefix character varying(16) DEFAULT ''::character varying NOT NULL,
    api_key_hash character varying(64),
    api_secret_encrypted text,
    CONSTRAINT uq_skillforge_channels_api_key_hash UNIQUE (api_key_hash),
    CONSTRAINT ck_skillforge_channels_api_credentials_pair CHECK ((api_key_hash IS NULL) = (api_secret_encrypted IS NULL)),
    CONSTRAINT ck_skillforge_channels_api_credentials_type CHECK (api_key_hash IS NULL OR channel_type = 'openapi'),
    CONSTRAINT ck_skillforge_channels_business_config CHECK (jsonb_typeof(config) = 'object' AND NOT (config ?| ARRAY['api_key_hash', 'api_secret_encrypted']))
);

ALTER TABLE ONLY public.nw_channels FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE channels; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.channel_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.config; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.ui_config; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.channel_token_hash; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN channels.channel_token_prefix; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: connector_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_connector_instances (
    id bigint NOT NULL,
    instance_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(128) NOT NULL,
    base_url character varying(512) NOT NULL,
    executor_type character varying(32) DEFAULT 'http'::character varying NOT NULL,
    capabilities jsonb DEFAULT '[]'::jsonb NOT NULL,
    auth_type character varying(32) DEFAULT 'bearer'::character varying NOT NULL,
    auth_credential_encrypted text,
    vault_secret_path character varying(256),
    capability_mapping jsonb DEFAULT '{}'::jsonb NOT NULL,
    headers jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    last_health_check_at timestamp with time zone,
    health_check_result jsonb,
    visibility character varying(32) DEFAULT 'tenant'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_connector_instances FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE connector_instances; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.instance_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.base_url; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.auth_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.auth_credential_encrypted; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.vault_secret_path; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.capability_mapping; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.headers; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.last_health_check_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.health_check_result; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.visibility; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN connector_instances.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: encryption_keys; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_encryption_keys (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    key_version integer DEFAULT 1 NOT NULL,
    encrypted_dek text NOT NULL,
    algorithm character varying(32) DEFAULT 'AES-256-GCM'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    retired_at timestamp with time zone
);

ALTER TABLE ONLY public.nw_encryption_keys FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE encryption_keys; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.key_version; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.encrypted_dek; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.algorithm; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN encryption_keys.retired_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: end_users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_end_users (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    display_name character varying(128),
    phone_encrypted character varying(256),
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    last_active_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_end_users FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE end_users; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.display_name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.phone_encrypted; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.first_seen_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.last_active_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN end_users.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: execution_event_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_execution_event_logs (
    id bigint NOT NULL,
    execution_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    sequence integer DEFAULT 0 NOT NULL,
    event_type character varying(64) NOT NULL,
    event_source character varying(64),
    event_data jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_code character varying(64),
    error_message text,
    trace_id character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_execution_event_logs FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE execution_event_logs; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.execution_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.sequence; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.event_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.event_source; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.event_data; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.error_code; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.error_message; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.trace_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN execution_event_logs.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: human_handoff_tickets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_human_handoff_tickets (
    id bigint NOT NULL,
    ticket_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    end_user_id bigint,
    session_id character varying(64),
    execution_id character varying(64),
    reason character varying(32) NOT NULL,
    summary text,
    assigned_to bigint,
    status character varying(32) DEFAULT 'pending'::character varying NOT NULL,
    resolution_note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    assigned_at timestamp with time zone,
    resolved_at timestamp with time zone,
    closed_at timestamp with time zone,
    priority integer DEFAULT 0 NOT NULL,
    context jsonb DEFAULT '{}'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now()
);

ALTER TABLE ONLY public.nw_human_handoff_tickets FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE human_handoff_tickets; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.ticket_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.end_user_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.session_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.execution_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.reason; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.summary; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.assigned_to; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.resolution_note; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.assigned_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.resolved_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.closed_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.priority; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.context; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN human_handoff_tickets.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_billing_invoices (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    total_amount_cents integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'open'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_billing_invoices FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE invoices; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.period_start; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.period_end; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.total_amount_cents; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN invoices.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: knowledge_bases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_knowledge_bases (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(128) NOT NULL,
    description text,
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    chunk_size integer,
    chunk_overlap integer,
    indexing_mode character varying(32),
    retrieval_mode character varying(32),
    top_k integer,
    score_threshold_enabled boolean DEFAULT false NOT NULL,
    score_threshold double precision,
    vector_weight double precision,
    rerank_enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_knowledge_bases FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE knowledge_bases; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.description; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.chunk_size; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.chunk_overlap; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.indexing_mode; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.retrieval_mode; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.top_k; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.score_threshold_enabled; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.score_threshold; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.vector_weight; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.rerank_enabled; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_bases.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: knowledge_chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_knowledge_chunks (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    kb_id bigint NOT NULL,
    document_id bigint NOT NULL,
    content text NOT NULL,
    chunk_index integer DEFAULT 0 NOT NULL,
    embedding public.vector,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_knowledge_chunks FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE knowledge_chunks; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.kb_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.document_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.content; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.chunk_index; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.embedding; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_chunks.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: knowledge_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_knowledge_documents (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    kb_id bigint NOT NULL,
    title character varying(256) NOT NULL,
    source_type character varying(32) NOT NULL,
    content text,
    original_filename character varying(512),
    extension character varying(32),
    size_bytes bigint,
    parsed_by character varying(32),
    status character varying(32) DEFAULT 'ready'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_knowledge_documents FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE knowledge_documents; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.kb_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.title; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.source_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.content; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.original_filename; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.extension; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.size_bytes; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.parsed_by; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN knowledge_documents.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_billing_plans (
    id bigint NOT NULL,
    plan_code character varying(32) NOT NULL,
    display_name character varying(64) NOT NULL,
    quota_api_calls_per_month integer,
    quota_tokens_included integer DEFAULT 0 NOT NULL,
    quota_max_knowledge_bases integer,
    quota_max_members integer,
    quota_max_channels integer,
    rate_limit_per_minute integer,
    slo_availability_target numeric(5,4) NOT NULL,
    features jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE plans; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.plan_code; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.display_name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.quota_api_calls_per_month; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.quota_tokens_included; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.quota_max_knowledge_bases; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.quota_max_members; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.quota_max_channels; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.rate_limit_per_minute; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.slo_availability_target; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.features; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.is_active; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN plans.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_chat_sessions (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    tenant_id bigint NOT NULL,
    end_user_id bigint NOT NULL,
    channel_id bigint,
    active_execution_id character varying(64),
    message_count integer DEFAULT 0 NOT NULL,
    last_event_id bigint DEFAULT 0 NOT NULL,
    summary_text text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    expired_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    scope_id character varying(64),
    scope_type character varying(32),
    agent_id bigint
);

ALTER TABLE ONLY public.nw_chat_sessions FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE sessions; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.session_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.end_user_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.channel_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.active_execution_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.message_count; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.last_event_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.summary_text; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.metadata; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.expired_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.scope_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.scope_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN sessions.agent_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: workflow_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_workflow_instances (
    id bigint NOT NULL,
    instance_id character varying(128) NOT NULL,
    definition_version_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    status character varying(32) DEFAULT 'draft'::character varying NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.nw_workflow_instances FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE workflow_instances; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.instance_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.definition_version_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.published_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_instances.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: workflow_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_workflow_templates (
    id bigint NOT NULL,
    workflow_id character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    version character varying(32) NOT NULL,
    type character varying(32) NOT NULL,
    category character varying(64),
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    description text,
    config_schema jsonb DEFAULT '[]'::jsonb NOT NULL,
    required_permissions jsonb DEFAULT '[]'::jsonb NOT NULL,
    author character varying(128),
    author_type character varying(32) DEFAULT 'platform'::character varying NOT NULL,
    price_type character varying(32) DEFAULT 'free'::character varying NOT NULL,
    price_cents integer DEFAULT 0 NOT NULL,
    install_count integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'draft'::character varying NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE workflow_templates; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.workflow_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.version; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.category; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.tags; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.description; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.config_schema; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.required_permissions; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.author; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.author_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.price_type; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.price_cents; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.install_count; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.published_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_templates.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: workflow_tool_connector_bindings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_workflow_tool_connector_bindings (
    id bigint NOT NULL,
    workflow_instance_id bigint NOT NULL,
    tool_name character varying(128) NOT NULL,
    required_capability character varying(64) NOT NULL,
    connector_instance_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE workflow_tool_connector_bindings; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_tool_connector_bindings.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_tool_connector_bindings.workflow_instance_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_tool_connector_bindings.tool_name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_tool_connector_bindings.required_capability; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_tool_connector_bindings.connector_instance_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN workflow_tool_connector_bindings.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: tenant_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_tenant_members (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    username character varying(128) NOT NULL,
    phone_encrypted character varying(256),
    role character varying(32) DEFAULT 'operator'::character varying NOT NULL,
    permissions jsonb DEFAULT '[]'::jsonb NOT NULL,
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    password_hash character varying(255),
    email character varying(255),
    external_sub character varying(128)
);

ALTER TABLE ONLY public.nw_tenant_members FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE tenant_members; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.username; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.phone_encrypted; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.role; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.permissions; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.last_login_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.password_hash; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.email; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenant_members.external_sub; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_tenants (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    slug character varying(64) NOT NULL,
    plan_id bigint NOT NULL,
    status character varying(32) DEFAULT 'active'::character varying NOT NULL,
    deployment_mode character varying(32) DEFAULT 'cloud'::character varying NOT NULL,
    billing_timezone character varying(64) DEFAULT 'Asia/Shanghai'::character varying NOT NULL,
    override_quota_api_calls_per_month integer,
    override_quota_max_workflows integer,
    override_quota_max_knowledge_bases integer,
    override_quota_max_members integer,
    override_quota_max_channels integer,
    override_rate_limit_per_minute integer,
    custom_llm_endpoint character varying(512),
    custom_llm_api_key_encrypted text,
    custom_llm_model character varying(64),
    parent_tenant_id bigint,
    settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tenants; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.name; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.slug; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.plan_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.status; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.deployment_mode; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.billing_timezone; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.override_quota_api_calls_per_month; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.override_quota_max_workflows; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.override_quota_max_knowledge_bases; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.override_quota_max_members; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.override_quota_max_channels; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.override_rate_limit_per_minute; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.custom_llm_endpoint; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.custom_llm_api_key_encrypted; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.custom_llm_model; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.parent_tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.settings; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN tenants.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: user_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.nw_user_profiles (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    end_user_id bigint NOT NULL,
    summary text,
    structured_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    preferences jsonb DEFAULT '{}'::jsonb NOT NULL,
    interaction_count integer DEFAULT 0 NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    last_session_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

COMMENT ON COLUMN public.nw_user_profiles.revision IS '所有画像写入递增的并发版本；模型合并仅能覆盖同一快照版本';

ALTER TABLE ONLY public.nw_user_profiles FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE user_profiles; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.tenant_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.end_user_id; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.summary; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.structured_fields; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.preferences; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.interaction_count; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.last_session_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.created_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: COLUMN user_profiles.updated_at; Type: COMMENT; Schema: public; Owner: -
--



--
-- Name: agent_templates agent_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_agent_templates
    ADD CONSTRAINT skillforge_agent_templates_pkey PRIMARY KEY (id);


--
-- Name: agent_templates agent_templates_template_key_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_agent_templates
    ADD CONSTRAINT skillforge_agent_templates_template_key_version_key UNIQUE (template_key, version);


--
-- Name: agents agents_agent_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_agents
    ADD CONSTRAINT skillforge_agents_agent_key_key UNIQUE (agent_key);


--
-- Name: agents agents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_agents
    ADD CONSTRAINT skillforge_agents_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_audit_logs
    ADD CONSTRAINT skillforge_audit_logs_pkey PRIMARY KEY (id);


--
-- Name: channel_identities channel_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channel_identities
    ADD CONSTRAINT skillforge_channel_identities_pkey PRIMARY KEY (id);


--
-- Name: channels channels_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channels
    ADD CONSTRAINT skillforge_channels_pkey PRIMARY KEY (id);


--
-- Name: connector_instances chk_ci_visibility; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_connector_instances
    ADD CONSTRAINT chk_ci_visibility CHECK (((visibility)::text = ANY ((ARRAY['tenant'::character varying, 'platform'::character varying])::text[]))) NOT VALID;


--
-- Name: knowledge_documents chk_kd_source_type; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_knowledge_documents
    ADD CONSTRAINT chk_kd_source_type CHECK ((source_type)::text = ANY (ARRAY['file'::text, 'projection'::text]));


--
-- Name: workflow_instances chk_wi_status; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_workflow_instances
    ADD CONSTRAINT chk_wi_status CHECK (((status)::text = ANY ((ARRAY['draft'::character varying, 'published'::character varying, 'disabled'::character varying, 'archived'::character varying])::text[]))) NOT VALID;


--
-- Name: workflow_templates chk_wt_price_cents; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_workflow_templates
    ADD CONSTRAINT chk_wt_price_cents CHECK ((price_cents >= 0)) NOT VALID;


--
-- Name: workflow_templates chk_wt_status; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_workflow_templates
    ADD CONSTRAINT chk_wt_status CHECK (((status)::text = ANY ((ARRAY['draft'::character varying, 'review'::character varying, 'published'::character varying, 'deprecated'::character varying, 'archived'::character varying])::text[]))) NOT VALID;


--
-- Name: tenants chk_tenants_deployment_mode; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_tenants
    ADD CONSTRAINT chk_skillforge_tenants_deployment_mode CHECK (((deployment_mode)::text = ANY ((ARRAY['cloud'::character varying, 'hybrid'::character varying, 'on_premise'::character varying])::text[]))) NOT VALID;


--
-- Name: tenants chk_tenants_status; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.nw_tenants
    ADD CONSTRAINT chk_skillforge_tenants_status CHECK (((status)::text = ANY ((ARRAY['active'::character varying, 'warning'::character varying, 'suspended'::character varying, 'terminated'::character varying])::text[]))) NOT VALID;


--
-- Name: connector_instances connector_instances_instance_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_connector_instances
    ADD CONSTRAINT skillforge_connector_instances_instance_id_key UNIQUE (instance_id);

ALTER TABLE ONLY public.nw_connector_instances
    ADD CONSTRAINT uq_skillforge_connector_instances_tenant_instance UNIQUE (tenant_id, instance_id);

COMMENT ON CONSTRAINT uq_skillforge_connector_instances_tenant_instance ON public.nw_connector_instances IS
    '连接器租户与实例业务标识的唯一身份，供 ToolDeployment 跨表引用。';


--
-- Name: connector_instances connector_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_connector_instances
    ADD CONSTRAINT skillforge_connector_instances_pkey PRIMARY KEY (id);


--
-- Name: encryption_keys encryption_keys_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_encryption_keys
    ADD CONSTRAINT skillforge_encryption_keys_pkey PRIMARY KEY (id);


--
-- Name: end_users end_users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_end_users
    ADD CONSTRAINT skillforge_end_users_pkey PRIMARY KEY (id);


--
-- Name: execution_event_logs execution_event_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_execution_event_logs
    ADD CONSTRAINT skillforge_execution_event_logs_pkey PRIMARY KEY (id);


--
-- Name: human_handoff_tickets human_handoff_tickets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_human_handoff_tickets
    ADD CONSTRAINT skillforge_human_handoff_tickets_pkey PRIMARY KEY (id);


--
-- Name: human_handoff_tickets human_handoff_tickets_ticket_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_human_handoff_tickets
    ADD CONSTRAINT skillforge_human_handoff_tickets_ticket_id_key UNIQUE (ticket_id);


--
-- Name: invoices invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_billing_invoices
    ADD CONSTRAINT skillforge_billing_invoices_pkey PRIMARY KEY (id);


--
-- Name: knowledge_bases knowledge_bases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_bases
    ADD CONSTRAINT skillforge_knowledge_bases_pkey PRIMARY KEY (id);


--
-- Name: knowledge_chunks knowledge_chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT skillforge_knowledge_chunks_pkey PRIMARY KEY (id);


--
-- Name: knowledge_documents knowledge_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_documents
    ADD CONSTRAINT skillforge_knowledge_documents_pkey PRIMARY KEY (id);


--
-- Name: plans plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_billing_plans
    ADD CONSTRAINT skillforge_billing_plans_pkey PRIMARY KEY (id);


--
-- Name: plans plans_plan_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_billing_plans
    ADD CONSTRAINT skillforge_billing_plans_plan_code_key UNIQUE (plan_code);


--
-- Name: sessions sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_chat_sessions
    ADD CONSTRAINT skillforge_chat_sessions_pkey PRIMARY KEY (id);


--
-- Name: sessions sessions_session_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_chat_sessions
    ADD CONSTRAINT skillforge_chat_sessions_session_id_key UNIQUE (session_id);


--
-- Name: workflow_instances workflow_instances_instance_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_instances
    ADD CONSTRAINT skillforge_workflow_instances_instance_id_key UNIQUE (instance_id);


--
-- Name: workflow_instances workflow_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_instances
    ADD CONSTRAINT skillforge_workflow_instances_pkey PRIMARY KEY (id);


--
-- Name: workflow_templates workflow_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_templates
    ADD CONSTRAINT skillforge_workflow_templates_pkey PRIMARY KEY (id);


--
-- Name: workflow_templates workflow_templates_workflow_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_templates
    ADD CONSTRAINT skillforge_workflow_templates_workflow_id_key UNIQUE (workflow_id);


--
-- Name: workflow_tool_connector_bindings workflow_tool_connector_bindings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_tool_connector_bindings
    ADD CONSTRAINT skillforge_workflow_tool_connector_bindings_pkey PRIMARY KEY (id);


--
-- Name: tenant_members tenant_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenant_members
    ADD CONSTRAINT skillforge_tenant_members_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenants
    ADD CONSTRAINT skillforge_tenants_pkey PRIMARY KEY (id);


--
-- Name: tenants tenants_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenants
    ADD CONSTRAINT skillforge_tenants_slug_key UNIQUE (slug);


--
-- Name: channel_identities uq_channel_identities; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channel_identities
    ADD CONSTRAINT uq_skillforge_channel_identities UNIQUE (tenant_id, channel_type, channel_user_id);


--
-- Name: channels uq_channels_token_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channels
    ADD CONSTRAINT uq_skillforge_channels_token_hash UNIQUE (channel_token_hash);


--
-- Name: knowledge_chunks uq_chunks_doc_chunk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT uq_chunks_doc_chunk UNIQUE (document_id, chunk_index);


--
-- Name: encryption_keys uq_encryption_keys_tenant_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_encryption_keys
    ADD CONSTRAINT uq_skillforge_encryption_keys_tenant_version UNIQUE (tenant_id, key_version);


--
-- Name: workflow_tool_connector_bindings uq_workflow_tool_binding; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_tool_connector_bindings
    ADD CONSTRAINT uq_workflow_tool_binding UNIQUE (workflow_instance_id, tool_name);


--
-- Name: tenant_members uq_tenant_member; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenant_members
    ADD CONSTRAINT uq_tenant_member UNIQUE (tenant_id, username);


--
-- Name: user_profiles uq_user_profiles; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_user_profiles
    ADD CONSTRAINT uq_skillforge_user_profiles UNIQUE (tenant_id, end_user_id);


--
-- Name: user_profiles user_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_user_profiles
    ADD CONSTRAINT skillforge_user_profiles_pkey PRIMARY KEY (id);


--
-- Name: idx_agents_template; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_agents_template ON public.nw_agents USING btree (template_id);


--
-- Name: idx_agents_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_agents_tenant ON public.nw_agents USING btree (tenant_id);


--
-- Name: idx_audit_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_tenant ON public.nw_audit_logs USING btree (tenant_id, created_at DESC);


--
-- Name: idx_audit_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_trace ON public.nw_audit_logs USING btree (trace_id);


--
-- Name: idx_channel_identities_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_channel_identities_user ON public.nw_channel_identities USING btree (end_user_id);


--
-- Name: idx_channels_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_channels_tenant ON public.nw_channels USING btree (tenant_id);


--
-- Name: idx_chunks_tenant_kb; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunks_tenant_kb ON public.nw_knowledge_chunks USING btree (tenant_id, kb_id);


--
-- Name: idx_conn_instances_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conn_instances_tenant ON public.nw_connector_instances USING btree (tenant_id);


--
-- Name: idx_eel_exec_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eel_exec_seq ON public.nw_execution_event_logs USING btree (execution_id, sequence);


--
-- Name: idx_encryption_keys_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_skillforge_encryption_keys_active ON public.nw_encryption_keys USING btree (tenant_id) WHERE (retired_at IS NULL);


--
-- Name: idx_end_users_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_end_users_tenant ON public.nw_end_users USING btree (tenant_id);


--
-- Name: idx_exec_events_exec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exec_events_exec ON public.nw_execution_event_logs USING btree (execution_id);


--
-- Name: idx_exec_events_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exec_events_tenant ON public.nw_execution_event_logs USING btree (tenant_id, created_at);


--
-- Name: idx_invoices_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_billing_invoices_tenant ON public.nw_billing_invoices USING btree (tenant_id);


--
-- Name: idx_kb_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_tenant ON public.nw_knowledge_bases USING btree (tenant_id);


--
-- Name: idx_kd_kb; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kd_kb ON public.nw_knowledge_documents USING btree (kb_id);


--
-- Name: idx_kd_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kd_tenant ON public.nw_knowledge_documents USING btree (tenant_id);


--
-- Name: idx_plans_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_billing_plans_code ON public.nw_billing_plans USING btree (plan_code);


--
-- Name: idx_profiles_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profiles_tenant ON public.nw_user_profiles USING btree (tenant_id);


--
-- Name: idx_sessions_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_chat_sessions_agent ON public.nw_chat_sessions USING btree (agent_id);


--
-- Name: idx_sessions_expired; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_chat_sessions_expired ON public.nw_chat_sessions USING btree (expired_at) WHERE (expired_at IS NOT NULL);


--
-- Name: idx_sessions_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_chat_sessions_tenant ON public.nw_chat_sessions USING btree (tenant_id);


--
-- Name: idx_sessions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_chat_sessions_user ON public.nw_chat_sessions USING btree (tenant_id, end_user_id);


--
-- Name: idx_sessions_user_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_chat_sessions_user_time ON public.nw_chat_sessions USING btree (tenant_id, end_user_id, created_at);


--
-- Name: idx_wt_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wt_status ON public.nw_workflow_templates USING btree (status);


--
-- Name: idx_wt_status_installs; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wt_status_installs ON public.nw_workflow_templates USING btree (status, install_count);


--
-- Name: idx_stcb_connector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stcb_connector ON public.nw_workflow_tool_connector_bindings USING btree (connector_instance_id);


--
-- Name: idx_tenants_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_tenants_parent ON public.nw_tenants USING btree (parent_tenant_id);


--
-- Name: idx_tenants_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_tenants_plan ON public.nw_tenants USING btree (plan_id);


--
-- Name: idx_tenants_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_skillforge_tenants_status ON public.nw_tenants USING btree (status);


--
-- Name: idx_tickets_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_status ON public.nw_human_handoff_tickets USING btree (tenant_id, status);


--
-- Name: idx_tickets_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_tenant ON public.nw_human_handoff_tickets USING btree (tenant_id);


--
-- Name: agents agents_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_agents
    ADD CONSTRAINT skillforge_agents_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.nw_agent_templates(id);


--
-- Name: agents agents_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_agents
    ADD CONSTRAINT skillforge_agents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: audit_logs audit_logs_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_audit_logs
    ADD CONSTRAINT skillforge_audit_logs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: channel_identities fk_channel_identities_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channel_identities
    ADD CONSTRAINT fk_skillforge_channel_identities_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: channel_identities fk_channel_identities_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channel_identities
    ADD CONSTRAINT fk_skillforge_channel_identities_user FOREIGN KEY (end_user_id) REFERENCES public.nw_end_users(id) ON DELETE CASCADE;


--
-- Name: channels fk_channels_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_channels
    ADD CONSTRAINT fk_skillforge_channels_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: knowledge_chunks fk_chunks_doc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT fk_chunks_doc FOREIGN KEY (document_id) REFERENCES public.nw_knowledge_documents(id) ON DELETE CASCADE;


--
-- Name: knowledge_chunks fk_chunks_kb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT fk_chunks_kb FOREIGN KEY (kb_id) REFERENCES public.nw_knowledge_bases(id) ON DELETE CASCADE;


--
-- Name: connector_instances fk_conn_instances_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_connector_instances
    ADD CONSTRAINT fk_conn_instances_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: encryption_keys fk_encryption_keys_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_encryption_keys
    ADD CONSTRAINT fk_skillforge_encryption_keys_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: end_users fk_end_users_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_end_users
    ADD CONSTRAINT fk_skillforge_end_users_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: knowledge_documents fk_kd_kb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_documents
    ADD CONSTRAINT fk_kd_kb FOREIGN KEY (kb_id) REFERENCES public.nw_knowledge_bases(id) ON DELETE CASCADE;


--
-- Name: sessions fk_sessions_agent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_chat_sessions
    ADD CONSTRAINT fk_skillforge_chat_sessions_agent FOREIGN KEY (agent_id) REFERENCES public.nw_agents(id);


--
-- Name: sessions fk_sessions_channel; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_chat_sessions
    ADD CONSTRAINT fk_skillforge_chat_sessions_channel FOREIGN KEY (channel_id) REFERENCES public.nw_channels(id);


--
-- Name: sessions fk_sessions_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_chat_sessions
    ADD CONSTRAINT fk_skillforge_chat_sessions_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: sessions fk_sessions_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_chat_sessions
    ADD CONSTRAINT fk_skillforge_chat_sessions_user FOREIGN KEY (end_user_id) REFERENCES public.nw_end_users(id);


--
-- Name: workflow_instances fk_workflow_instances_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_instances
    ADD CONSTRAINT fk_skillforge_workflow_instances_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: workflow_tool_connector_bindings fk_wtcb_connector; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_tool_connector_bindings
    ADD CONSTRAINT fk_wtcb_connector FOREIGN KEY (connector_instance_id) REFERENCES public.nw_connector_instances(id);


--
-- Name: workflow_tool_connector_bindings fk_wtcb_instance; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_workflow_tool_connector_bindings
    ADD CONSTRAINT fk_wtcb_instance FOREIGN KEY (workflow_instance_id) REFERENCES public.nw_workflow_instances(id) ON DELETE CASCADE;


--
-- Name: tenant_members fk_tenant_members_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenant_members
    ADD CONSTRAINT fk_skillforge_tenant_members_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: tenants fk_tenants_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenants
    ADD CONSTRAINT fk_skillforge_tenants_parent FOREIGN KEY (parent_tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: tenants fk_tenants_plan; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_tenants
    ADD CONSTRAINT fk_skillforge_tenants_plan FOREIGN KEY (plan_id) REFERENCES public.nw_billing_plans(id);


--
-- Name: human_handoff_tickets fk_tickets_member; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_human_handoff_tickets
    ADD CONSTRAINT fk_tickets_member FOREIGN KEY (assigned_to) REFERENCES public.nw_tenant_members(id);


--
-- Name: human_handoff_tickets fk_tickets_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_human_handoff_tickets
    ADD CONSTRAINT fk_tickets_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: human_handoff_tickets fk_tickets_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_human_handoff_tickets
    ADD CONSTRAINT fk_tickets_user FOREIGN KEY (end_user_id) REFERENCES public.nw_end_users(id);


--
-- Name: user_profiles fk_user_profiles_tenant; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_user_profiles
    ADD CONSTRAINT fk_skillforge_user_profiles_tenant FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: user_profiles fk_user_profiles_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_user_profiles
    ADD CONSTRAINT fk_skillforge_user_profiles_user FOREIGN KEY (end_user_id) REFERENCES public.nw_end_users(id) ON DELETE CASCADE;


--
-- Name: invoices invoices_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_billing_invoices
    ADD CONSTRAINT skillforge_billing_invoices_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: knowledge_bases knowledge_bases_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_bases
    ADD CONSTRAINT skillforge_knowledge_bases_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: knowledge_chunks knowledge_chunks_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT skillforge_knowledge_chunks_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.nw_knowledge_documents(id);


--
-- Name: knowledge_chunks knowledge_chunks_kb_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT skillforge_knowledge_chunks_kb_id_fkey FOREIGN KEY (kb_id) REFERENCES public.nw_knowledge_bases(id);


--
-- Name: knowledge_chunks knowledge_chunks_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_chunks
    ADD CONSTRAINT skillforge_knowledge_chunks_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: knowledge_documents knowledge_documents_kb_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_documents
    ADD CONSTRAINT skillforge_knowledge_documents_kb_id_fkey FOREIGN KEY (kb_id) REFERENCES public.nw_knowledge_bases(id);


--
-- Name: knowledge_documents knowledge_documents_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.nw_knowledge_documents
    ADD CONSTRAINT skillforge_knowledge_documents_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id);


--
-- Name: agents; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_agents ENABLE ROW LEVEL SECURITY;

--
-- Name: agents agents_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_agents_tenant_isolation ON public.nw_agents USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint));


--
-- Name: audit_logs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_audit_logs ENABLE ROW LEVEL SECURITY;

--
-- Name: audit_logs audit_logs_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_audit_logs_tenant_isolation ON public.nw_audit_logs USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint));


--
-- Name: channel_identities; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_channel_identities ENABLE ROW LEVEL SECURITY;

--
-- Name: channel_identities channel_identities_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_channel_identities_tenant_isolation ON public.nw_channel_identities USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: channels; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_channels ENABLE ROW LEVEL SECURITY;

--
-- Name: channels channels_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_channels_tenant_isolation ON public.nw_channels USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: connector_instances; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_connector_instances ENABLE ROW LEVEL SECURITY;

--
-- Name: connector_instances connector_instances_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_connector_instances_tenant_isolation ON public.nw_connector_instances USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: encryption_keys; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_encryption_keys ENABLE ROW LEVEL SECURITY;

--
-- Name: encryption_keys encryption_keys_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_encryption_keys_tenant_isolation ON public.nw_encryption_keys USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: end_users; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_end_users ENABLE ROW LEVEL SECURITY;

--
-- Name: end_users end_users_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_end_users_tenant_isolation ON public.nw_end_users USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: execution_event_logs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_execution_event_logs ENABLE ROW LEVEL SECURITY;

--
-- Name: execution_event_logs execution_event_logs_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_execution_event_logs_tenant_isolation ON public.nw_execution_event_logs USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: human_handoff_tickets; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_human_handoff_tickets ENABLE ROW LEVEL SECURITY;

--
-- Name: human_handoff_tickets human_handoff_tickets_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_human_handoff_tickets_tenant_isolation ON public.nw_human_handoff_tickets USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: invoices; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_billing_invoices ENABLE ROW LEVEL SECURITY;

--
-- Name: invoices invoices_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_billing_invoices_tenant_isolation ON public.nw_billing_invoices USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint));


--
-- Name: knowledge_bases; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_knowledge_bases ENABLE ROW LEVEL SECURITY;

--
-- Name: knowledge_bases knowledge_bases_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_knowledge_bases_tenant_isolation ON public.nw_knowledge_bases USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint));


--
-- Name: knowledge_chunks; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_knowledge_chunks ENABLE ROW LEVEL SECURITY;

--
-- Name: knowledge_chunks knowledge_chunks_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_knowledge_chunks_tenant_isolation ON public.nw_knowledge_chunks USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint));


--
-- Name: knowledge_documents; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_knowledge_documents ENABLE ROW LEVEL SECURITY;

--
-- Name: knowledge_documents knowledge_documents_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_knowledge_documents_tenant_isolation ON public.nw_knowledge_documents USING ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint)) WITH CHECK ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint));


--
-- Name: sessions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_chat_sessions ENABLE ROW LEVEL SECURITY;

--
-- Name: sessions sessions_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_chat_sessions_tenant_isolation ON public.nw_chat_sessions USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: workflow_instances; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_workflow_instances ENABLE ROW LEVEL SECURITY;

--
-- Name: workflow_instances workflow_instances_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_workflow_instances_tenant_isolation ON public.nw_workflow_instances USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: tenant_members; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_tenant_members ENABLE ROW LEVEL SECURITY;

--
-- Name: tenant_members tenant_members_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_tenant_members_tenant_isolation ON public.nw_tenant_members USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- Name: user_profiles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.nw_user_profiles ENABLE ROW LEVEL SECURITY;

--
-- Name: user_profiles user_profiles_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY skillforge_user_profiles_tenant_isolation ON public.nw_user_profiles USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))) WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));


--
-- PostgreSQL database dump complete
--

