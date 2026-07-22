-- ============================================================
-- SECTION: SCHEMA
-- ============================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS "public";


--
-- Name: SCHEMA "public"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA "public" IS 'standard public schema';


--
-- Name: pg_graphql; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pg_graphql" WITH SCHEMA "graphql";


--
-- Name: EXTENSION "pg_graphql"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "pg_graphql" IS 'pg_graphql: GraphQL support';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA "extensions";


--
-- Name: EXTENSION "pgcrypto"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "pgcrypto" IS 'cryptographic functions';


--
-- Name: supabase_vault; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "supabase_vault" WITH SCHEMA "vault";


--
-- Name: EXTENSION "supabase_vault"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "supabase_vault" IS 'Supabase Vault Extension';


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA "extensions";


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


SET default_tablespace = '';

SET default_table_access_method = "heap";

--
-- Name: cast_commands; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE IF NOT EXISTS "public"."cast_commands" (
    "id" bigint NOT NULL,
    "device_id" "text" NOT NULL,
    "type" "text" NOT NULL,
    "payload" "jsonb",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: cast_commands_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE IF NOT EXISTS "public"."cast_commands_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cast_commands_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE "public"."cast_commands_id_seq" OWNED BY "public"."cast_commands"."id";


--
-- Name: cast_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE IF NOT EXISTS "public"."cast_sessions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "device_id" "text" NOT NULL,
    "device_name" "text" DEFAULT '投屏助手'::"text" NOT NULL,
    "ip" "text" NOT NULL,
    "port" integer DEFAULT 7788 NOT NULL,
    "last_seen" timestamp with time zone DEFAULT "now"() NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: cast_commands id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."cast_commands" ALTER COLUMN "id" SET DEFAULT "nextval"('"public"."cast_commands_id_seq"'::"regclass");


--
-- Name: cast_commands cast_commands_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint con
    JOIN pg_class c ON c.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE con.conname = 'cast_commands_pkey'
      AND n.nspname = 'public'
      AND c.relname = 'cast_commands'
  ) THEN
    EXECUTE $pg_schema_sql$
ALTER TABLE ONLY "public"."cast_commands"
    ADD CONSTRAINT "cast_commands_pkey" PRIMARY KEY ("id");
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_sessions cast_sessions_device_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint con
    JOIN pg_class c ON c.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE con.conname = 'cast_sessions_device_id_key'
      AND n.nspname = 'public'
      AND c.relname = 'cast_sessions'
  ) THEN
    EXECUTE $pg_schema_sql$
ALTER TABLE ONLY "public"."cast_sessions"
    ADD CONSTRAINT "cast_sessions_device_id_key" UNIQUE ("device_id");
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_sessions cast_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint con
    JOIN pg_class c ON c.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE con.conname = 'cast_sessions_pkey'
      AND n.nspname = 'public'
      AND c.relname = 'cast_sessions'
  ) THEN
    EXECUTE $pg_schema_sql$
ALTER TABLE ONLY "public"."cast_sessions"
    ADD CONSTRAINT "cast_sessions_pkey" PRIMARY KEY ("id");
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_commands_device_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX IF NOT EXISTS "cast_commands_device_created" ON "public"."cast_commands" USING "btree" ("device_id", "created_at" DESC);


--
-- Name: cast_commands cast_commands_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint con
    JOIN pg_class c ON c.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE con.conname = 'cast_commands_device_id_fkey'
      AND n.nspname = 'public'
      AND c.relname = 'cast_commands'
  ) THEN
    EXECUTE $pg_schema_sql$
ALTER TABLE ONLY "public"."cast_commands"
    ADD CONSTRAINT "cast_commands_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "public"."cast_sessions"("device_id") ON DELETE CASCADE;
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_commands anon_delete_cast_commands; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_delete_cast_commands'
      AND n.nspname = 'public'
      AND c.relname = 'cast_commands'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_delete_cast_commands" ON "public"."cast_commands" FOR DELETE TO "anon" USING (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_sessions anon_delete_cast_sessions; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_delete_cast_sessions'
      AND n.nspname = 'public'
      AND c.relname = 'cast_sessions'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_delete_cast_sessions" ON "public"."cast_sessions" FOR DELETE TO "anon" USING (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_commands anon_insert_cast_commands; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_insert_cast_commands'
      AND n.nspname = 'public'
      AND c.relname = 'cast_commands'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_insert_cast_commands" ON "public"."cast_commands" FOR INSERT TO "anon" WITH CHECK (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_sessions anon_insert_cast_sessions; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_insert_cast_sessions'
      AND n.nspname = 'public'
      AND c.relname = 'cast_sessions'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_insert_cast_sessions" ON "public"."cast_sessions" FOR INSERT TO "anon" WITH CHECK (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_commands anon_select_cast_commands; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_select_cast_commands'
      AND n.nspname = 'public'
      AND c.relname = 'cast_commands'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_select_cast_commands" ON "public"."cast_commands" FOR SELECT TO "anon" USING (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_sessions anon_select_cast_sessions; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_select_cast_sessions'
      AND n.nspname = 'public'
      AND c.relname = 'cast_sessions'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_select_cast_sessions" ON "public"."cast_sessions" FOR SELECT TO "anon" USING (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_sessions anon_update_cast_sessions; Type: POLICY; Schema: public; Owner: -
--

DO $pg_schema_restore$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy pol
    JOIN pg_class c ON c.oid = pol.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE pol.polname = 'anon_update_cast_sessions'
      AND n.nspname = 'public'
      AND c.relname = 'cast_sessions'
  ) THEN
    EXECUTE $pg_schema_sql$
CREATE POLICY "anon_update_cast_sessions" ON "public"."cast_sessions" FOR UPDATE TO "anon" USING (true) WITH CHECK (true);
$pg_schema_sql$;
  END IF;
END
$pg_schema_restore$;


--
-- Name: cast_commands; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE "public"."cast_commands" ENABLE ROW LEVEL SECURITY;

--
-- Name: cast_sessions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE "public"."cast_sessions" ENABLE ROW LEVEL SECURITY;

--
-- PostgreSQL database dump complete
--




-- ============================================================
-- SECTION: DIFF FILTER OBJECTS
-- ============================================================
-- Objects that match diff-filter.json but cannot be represented
-- precisely by pg_dump --filter.


-- ============================================================
-- SECTION: STORAGE BUCKETS DATA
-- ============================================================


-- ============================================================
-- SECTION: CRON JOBS
-- ============================================================
-- 用户自定义 pg_cron 任务。

