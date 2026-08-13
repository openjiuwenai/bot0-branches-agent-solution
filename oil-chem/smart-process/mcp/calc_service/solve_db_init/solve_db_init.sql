--
-- PostgreSQL database dump
--

\restrict WiduP3tEhqs4Z2N2cJd9uS8wfiSH2sKsGbiuSOInVm5mVJa0wNchrGoHWn0KMQX

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Name: solve_db; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA solve_db;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: connections; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.connections (
    connection_id character varying(32) NOT NULL,
    from_device_id character varying(32),
    from_product_id character varying(64),
    to_device_id character varying(32),
    priority smallint,
    is_unique_target boolean,
    special_var character varying(32)
);


--
-- Name: cp_sat_plan_details; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.cp_sat_plan_details (
    id character varying(64) NOT NULL,
    plan_id character varying(32),
    plan_date date,
    day_of_month smallint,
    daily_input numeric,
    blend_detail jsonb,
    crude_stock_status jsonb,
    device_load_rate numeric(8,4),
    hours numeric
);


--
-- Name: device_yields; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.device_yields (
    side_line_id character varying(64) NOT NULL,
    crude_type character varying(64) NOT NULL,
    yield_rate numeric(6,4),
    yield_rate_2 numeric(6,4),
    yield_rate_3 numeric(6,4),
    yield_rate_4 numeric(6,4)
);


--
-- Name: devices_tanks; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.devices_tanks (
    device_id character varying(32) NOT NULL,
    name character varying(128) NOT NULL,
    max_capacity numeric(14,3),
    safety_stock_thrd numeric(14,3),
    low_safety_thrd numeric(14,3),
    current_capacity numeric(14,3),
    refinery_unit_load_pct numeric(5,2),
    tank_category character varying(16) NOT NULL,
    note text,
    enabled boolean DEFAULT true,
    material_id integer,
    CONSTRAINT devices_tanks_category_chk CHECK (((tank_category)::text = ANY ((ARRAY['intermediate'::character varying, 'product'::character varying, 'crude'::character varying])::text[])))
);


--
-- Name: devices_units; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.devices_units (
    device_id character varying(32) NOT NULL,
    name character varying(128) NOT NULL,
    type character varying(16) NOT NULL,
    max_capacity numeric(14,3),
    safety_stock_thrd numeric(14,3),
    low_safety_thrd numeric(14,3),
    current_capacity numeric(14,3),
    refinery_unit_load_pct numeric(5,2),
    device_id_2 character varying(32),
    backend_device_id integer,
    note text,
    enabled boolean DEFAULT true,
    CONSTRAINT devices_units_type_chk CHECK (((type)::text = ANY ((ARRAY['normal'::character varying, 'start'::character varying])::text[])))
);


--
-- Name: energy; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.energy (
    id character varying(32) NOT NULL,
    device_id character varying(32),
    consumption_per_ton numeric(18,8),
    price_per_unit numeric(14,4),
    energy_type character varying(64)
);


--
-- Name: material_flows; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.material_flows (
    flow_id character varying(64) NOT NULL,
    source_type character varying(16) NOT NULL,
    source_device_id character varying(32),
    source_product_id character varying(64),
    source_name character varying(64),
    tank_id character varying(32),
    target_device_id character varying(32),
    flow_type character varying(16) NOT NULL,
    special_var character varying(32),
    priority smallint DEFAULT 1,
    is_unique_target boolean DEFAULT false,
    split_ratio numeric(5,4) DEFAULT 1.0,
    target_product_id character varying(64)
);


--
-- Name: production_plan_details; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.production_plan_details (
    id character varying(64) NOT NULL,
    plan_id character varying(32),
    plan_date date,
    day_of_month smallint,
    daily_input numeric,
    blend_detail jsonb,
    crude_stock_status jsonb,
    device_load_rate numeric(8,4),
    hours numeric
);


--
-- Name: production_plans_input; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.production_plans_input (
    planned_month character varying(7) NOT NULL,
    crude_type_id character varying(64) NOT NULL,
    crude_type_name character varying(64),
    arrival_plan jsonb,
    monthly_processing_capacity numeric,
    current_stock numeric,
    max_level_stock numeric,
    min_level_stock numeric,
    cost numeric
);


--
-- Name: scheduling_tasks; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.scheduling_tasks (
    plan_id character varying(32) NOT NULL,
    planned_month character varying(7),
    status character varying(32),
    locked boolean,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    generated_at timestamp with time zone
);


--
-- Name: side_lines; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.side_lines (
    side_line_id character varying(64) NOT NULL,
    name character varying(128),
    source_device_id character varying(32),
    material_type character varying(16) DEFAULT 'product'::character varying,
    is_final boolean,
    material_id integer,
    note text,
    CONSTRAINT side_lines_material_type_chk CHECK (((material_type)::text = ANY ((ARRAY['product'::character varying, 'main_feed'::character varying, 'auxiliary'::character varying])::text[])))
);


--
-- Name: tank_monthly_initial; Type: TABLE; Schema: solve_db; Owner: -
--

CREATE TABLE solve_db.tank_monthly_initial (
    tank_id character varying(32) NOT NULL,
    year_month character varying(7) NOT NULL,
    initial_capacity numeric(14,3)
);


--
-- Name: connections connections_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.connections
    ADD CONSTRAINT connections_pkey PRIMARY KEY (connection_id);


--
-- Name: cp_sat_plan_details cp_sat_plan_details_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.cp_sat_plan_details
    ADD CONSTRAINT cp_sat_plan_details_pkey PRIMARY KEY (id);


--
-- Name: device_yields device_yields_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.device_yields
    ADD CONSTRAINT device_yields_pkey PRIMARY KEY (side_line_id, crude_type);


--
-- Name: devices_tanks devices_tanks_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.devices_tanks
    ADD CONSTRAINT devices_tanks_pkey PRIMARY KEY (device_id);


--
-- Name: devices_units devices_units_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.devices_units
    ADD CONSTRAINT devices_units_pkey PRIMARY KEY (device_id);


--
-- Name: energy energy_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.energy
    ADD CONSTRAINT energy_pkey PRIMARY KEY (id);


--
-- Name: material_flows material_flows_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.material_flows
    ADD CONSTRAINT material_flows_pkey PRIMARY KEY (flow_id);


--
-- Name: production_plan_details production_plan_details_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.production_plan_details
    ADD CONSTRAINT production_plan_details_pkey PRIMARY KEY (id);


--
-- Name: production_plans_input production_plans_input_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.production_plans_input
    ADD CONSTRAINT production_plans_input_pkey PRIMARY KEY (planned_month, crude_type_id);


--
-- Name: scheduling_tasks scheduling_tasks_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.scheduling_tasks
    ADD CONSTRAINT scheduling_tasks_pkey PRIMARY KEY (plan_id);


--
-- Name: side_lines side_lines_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.side_lines
    ADD CONSTRAINT side_lines_pkey PRIMARY KEY (side_line_id);


--
-- Name: tank_monthly_initial tank_monthly_initial_pkey; Type: CONSTRAINT; Schema: solve_db; Owner: -
--

ALTER TABLE ONLY solve_db.tank_monthly_initial
    ADD CONSTRAINT tank_monthly_initial_pkey PRIMARY KEY (tank_id, year_month);


--
-- Name: idx_cpsat_details_plan_id; Type: INDEX; Schema: solve_db; Owner: -
--

CREATE INDEX idx_cpsat_details_plan_id ON solve_db.cp_sat_plan_details USING btree (plan_id);


--
-- Name: idx_plan_details_plan_id; Type: INDEX; Schema: solve_db; Owner: -
--

CREATE INDEX idx_plan_details_plan_id ON solve_db.production_plan_details USING btree (plan_id);


--
-- Name: idx_side_lines_material_id; Type: INDEX; Schema: solve_db; Owner: -
--

CREATE INDEX idx_side_lines_material_id ON solve_db.side_lines USING btree (material_id);


--
-- Name: idx_side_lines_source_device; Type: INDEX; Schema: solve_db; Owner: -
--

CREATE INDEX idx_side_lines_source_device ON solve_db.side_lines USING btree (source_device_id);


--
-- FK 约束移至文件末尾（所有 COPY 之后），避免 device_yields 先于 side_lines 加载时违约
--


--
-- PostgreSQL database dump complete
--

\unrestrict WiduP3tEhqs4Z2N2cJd9uS8wfiSH2sKsGbiuSOInVm5mVJa0wNchrGoHWn0KMQX

--
-- PostgreSQL database dump
--

\restrict 3lyRMVOTt5bY5ElETlkuQmMCgPD3OpqmaTeYahmbokrYwBTPGPUN7PbQ0ATsHya

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: crude_types; Type: TABLE; Schema: public; Owner: -
--

DROP TABLE IF EXISTS public.crude_types CASCADE;
CREATE TABLE public.crude_types (
    crude_type_id character varying(64) NOT NULL,
    crude_name character varying(128) NOT NULL,
    crude_code character varying(20),
    aliases text[],
    is_active boolean DEFAULT true,
    is_default boolean DEFAULT false,
    sort_order integer DEFAULT 0,
    note text
);


--
-- Name: crude_types crude_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crude_types
    ADD CONSTRAINT crude_types_pkey PRIMARY KEY (crude_type_id);


--
-- PostgreSQL database dump complete
--

\unrestrict 3lyRMVOTt5bY5ElETlkuQmMCgPD3OpqmaTeYahmbokrYwBTPGPUN7PbQ0ATsHya


-- 配置数据（solve_db 配置表 + public.crude_types）

-- solve_db.connections
--
-- PostgreSQL database dump
--

\restrict PaNz6JoIsFrKKROleORyn31cEGEdhzDqDDGGD2KMSOdzSLC4IYgd1LngRqNMVbz

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: connections; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.connections (connection_id, from_device_id, from_product_id, to_device_id, priority, is_unique_target, special_var) FROM stdin;
conn_1	cjy_01	cjy_01_chang1	gyrly_tank_01	1	t	\N
conn_2	cjy_01	cjy_01_chang2	gyrly_tank_01	1	t	\N
conn_3	cjy_01	cjy_01_chang3	gyrly_tank_01	1	t	\N
conn_4	cjy_01	cjy_01_jian1	gyrly_tank_01	1	f	jian1_to_diesel
conn_5	cjy_01	cjy_01_jian1	hc_tank_01	1	f	jian1_to_wax
conn_6	cjy_01	cjy_01_jian2	hc_tank_01	1	t	\N
conn_7	cjy_01	cjy_01_jian3	hc_tank_01	1	t	\N
conn_8	cjy_01	cjy_01_jian4	hc_tank_01	1	t	\N
conn_9	cjy_01	cjy_01_gq	cjydbzh_tank_01	1	t	\N
conn_10	cjy_01	cjy_01_zlsny	cjydbzh_tank_01	1	t	\N
conn_11	cjy_01	cjy_01_250rly	rly250_tank_01	1	t	\N
conn_12	cjy_01	cjy_01_jyzy	jyzy_tank_01	1	t	\N
conn_13	cyjq_01	cyjq_01_hangmei	hm_tank_01	1	t	\N
conn_14	cyjq_01	cyjq_01_zsny	zsny_tank_01	1	t	\N
conn_15	cyjq_01	cyjq_01_rlydmx	rlydmx_tank_01	1	t	\N
conn_16	cyjq_01	cyjq_01_hlgq	lyjq_01	1	t	\N
conn_17	cyjq_01	cyjq_01_hldfq	lyjq_01	1	t	\N
conn_18	cyjq_01	cyjq_01_hlyhq	lyjq_01	1	t	\N
conn_19	cyjq_01	cyjq_01_qsny	lyjq_01	1	t	\N
conn_20	lyjq_01	lyjq_01_rlydmb	rlydmb_tank_01	1	t	\N
conn_21	lyjq_01	lyjq_01_hangmei	hm_tank_01	1	t	\N
conn_22	lyjq_01	lyjq_01_rlydmx	rlydmx_tank_01	1	t	\N
conn_23	lyjq_01	lyjq_01_jqwy	jqwy_tank_01	1	t	\N
conn_24	lyjq_01	lyjq_01_c5	c5_tank_01	1	t	\N
conn_25	lyjq_01	lyjq_01_yhq	yhq_tank_01	1	t	\N
conn_26	gyrly_tank_01	gyrly_tank_01_gyrly	cyjq_01	1	t	\N
conn_27	hc_tank_01	hc_tank_01_hc	lyjq_01	1	t	\N
\.


--
-- PostgreSQL database dump complete
--

\unrestrict PaNz6JoIsFrKKROleORyn31cEGEdhzDqDDGGD2KMSOdzSLC4IYgd1LngRqNMVbz


-- solve_db.device_yields
--
-- PostgreSQL database dump
--

\restrict yLyGhRZjbIU5rYUhUhV1dgoU8YDHIvS8KZrNdYrYhMdauJ0ldeiOae9YnYORukF

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: device_yields; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.device_yields (side_line_id, crude_type, yield_rate, yield_rate_2, yield_rate_3, yield_rate_4) FROM stdin;
dcc01_yhq	default	0.4135	0.0000	0.0000	0.0000
fgh_01_cb	default	0.0000	0.0000	0.0000	0.0000
jz_01_jhgq	default	0.0000	0.0000	0.0000	0.0000
dxe_01_bqgq	default	0.0000	0.0000	0.0000	0.0000
dxe_01_ss	default	0.0000	0.0000	0.0000	0.0000
dxe_01_ybwan	default	0.0000	0.0000	0.0000	0.0000
ct_01_c9fx	default	0.0000	0.0000	0.0000	0.0000
ct_01_ctc9lf	default	0.0000	0.0000	0.0000	0.0000
ct_01_hf	default	0.0000	0.0000	0.0000	0.0000
ct_01_cyy	default	0.0000	0.0000	0.0000	0.0000
ct_01_c10ctfx	default	0.0000	0.0000	0.0000	0.0000
ct_01_hexc	default	0.0000	0.0000	0.0000	0.0000
ct_01_cf	default	0.0000	0.0000	0.0000	0.0000
ct_01_c6c8fx	default	0.0000	0.0000	0.0000	0.0000
ct_01_jb	default	0.0000	0.0000	0.0000	0.0000
ct_01_rq	default	0.0000	0.0000	0.0000	0.0000
ct_01_ss	default	0.0000	0.0000	0.0000	0.0000
ct_01_wq	default	0.0000	0.0000	0.0000	0.0000
ct_02_c6c8	default	0.0000	0.0000	0.0000	0.0000
ct_02_bjb	default	0.0000	0.0000	0.0000	0.0000
ct_02_ben	default	0.0000	0.0000	0.0000	0.0000
ct_02_cf	default	0.0000	0.0000	0.0000	0.0000
ct_02_cyy	default	0.0000	0.0000	0.0000	0.0000
ct_02_hexc	default	0.0000	0.0000	0.0000	0.0000
ct_02_jb	default	0.0000	0.0000	0.0000	0.0000
ct_02_ss	default	0.0000	0.0000	0.0000	0.0000
ct_02_wy	default	0.0000	0.0000	0.0000	0.0000
fgh_01_mhts	default	0.0000	0.0000	0.0000	0.0000
fgh_01_qsny	default	0.0000	0.0000	0.0000	0.0000
fgh_01_c5	default	0.0000	0.0000	0.0000	0.0000
fgh_01_c5xt	default	0.0000	0.0000	0.0000	0.0000
fgh_01_cyy	default	0.0000	0.0000	0.0000	0.0000
fgh_01_yhq	default	0.0000	0.0000	0.0000	0.0000
fgh_01_zc4	default	0.0000	0.0000	0.0000	0.0000
fgh_01_tlgq	default	0.0000	0.0000	0.0000	0.0000
fgh_01_twgq	default	0.0000	0.0000	0.0000	0.0000
fgh_01_c8	default	0.0000	0.0000	0.0000	0.0000
fgh_01_ben	default	0.0000	0.0000	0.0000	0.0000
fgh_01_bwan	default	0.0000	0.0000	0.0000	0.0000
lt01_jyzy	default	1.0000	0.0000	0.0000	0.0000
fgh_01_c4ff	default	0.0000	0.0000	0.0000	0.0000
fgh_01_c5xt_out	default	0.0000	0.0000	0.0000	0.0000
yb_01_ben	default	0.0000	0.0000	0.0000	0.0000
yb_01_jhgq	default	0.0000	0.0000	0.0000	0.0000
yb_01_hhy	default	0.0000	0.0000	0.0000	0.0000
yb_01_dccjsq	default	0.0000	0.0000	0.0000	0.0000
yb_01_bb	default	0.0000	0.0000	0.0000	0.0000
yb_01_bqgq	default	0.0000	0.0000	0.0000	0.0000
cjy_01_chang3	luda_10_1	0.0841	0.0000	0.0841	0.0000
cjy_01_jian1	luda_10_1	0.0655	0.0000	0.0655	0.0000
cjy_01_jian2	luda_10_1	0.1374	0.0000	0.1374	0.0000
cjy_01_jian3	luda_10_1	0.0527	0.0000	0.0527	0.0000
lyjq_01_aux_c5	caofeidian	0.0230	0.0000	0.0000	0.0000
lyjq_01_aux_hlgq	caofeidian	0.0129	0.0000	0.0000	0.0000
lyjq_01_aux_h2	caofeidian	0.0086	0.0000	0.0000	0.0000
lyjq_01_aux_hldfq	caofeidian	0.0061	0.0000	0.0000	0.0000
lyjq_01_aux_hlyhq	caofeidian	0.0351	0.0000	0.0000	0.0000
cjy_01_jyzy	caofeidian	0.3488	0.0000	0.3488	0.0000
cjy_01_zlsny	caofeidian	0.0473	0.0000	0.0473	0.0000
cyjq_01_main_zlcy	caofeidian	0.9779	0.0000	0.0000	0.0000
cyjq_01_qsny	caofeidian	0.0853	0.0929	0.0853	0.0929
cyjq_01_rlydmx	caofeidian	0.3096	0.3726	0.1514	0.1627
cyjq_01_zsny	caofeidian	0.4295	0.4110	0.4295	0.4110
cyjq_01_hlgq	caofeidian	0.0134	0.0158	0.0134	0.0158
cyjq_01_hlyhq	caofeidian	0.0661	0.0656	0.0661	0.0656
cjy_01_chang1	caofeidian	0.0181	0.0000	0.0181	0.0000
lyjq_01_c5	caofeidian	0.0624	0.0551	0.0000	0.0000
lyjq_01_rlydmx	bozhong_25_1	0.1364	0.1426	0.0000	0.0000
lyjq_01_main_zlyy	bozhong_25_1	0.8394	0.0000	0.0000	0.0000
lyjq_01_tldqf	bozhong_25_1	0.0175	0.0168	0.0000	0.0000
lyjq_01_tlgq	bozhong_25_1	0.0050	0.0055	0.0000	0.0000
lyjq_01_yhq	bozhong_25_1	0.0526	0.0457	0.0000	0.0000
lyjq_01_aux_c5	bozhong_25_1	0.0219	0.0000	0.0000	0.0000
lyjq_01_aux_h2	bozhong_25_1	0.0076	0.0000	0.0000	0.0000
lyjq_01_aux_hldfq	bozhong_25_1	0.0043	0.0000	0.0000	0.0000
dxe_01_jc	default	0.0000	0.0000	0.0000	0.0000
dxe_01_h2	default	0.0000	0.0000	0.0000	0.0000
dxe_01_mtbe	default	0.0000	0.0000	0.0000	0.0000
ft_01_fywgq	default	0.0000	0.0000	0.0000	0.0000
qh_01_c8	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_lpny	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_cb	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_xq	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_c6c8	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_c9fxlf	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_jc5lf	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_ss	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_tdq	default	0.0000	0.0000	0.0000	0.0000
snyjy_01_wy	default	0.0000	0.0000	0.0000	0.0000
yb_01_fbxgq	default	0.0000	0.0000	0.0000	0.0000
yb_01_gfw	default	0.0000	0.0000	0.0000	0.0000
yb_01_ss	default	0.0000	0.0000	0.0000	0.0000
yb_01_hhy_out	default	0.0000	0.0000	0.0000	0.0000
dcc01_byxjy	default	0.0009	0.0000	0.0000	0.0000
cz_01_p1	default	0.0000	0.0000	0.0000	0.0000
yjq_01_jzsny	default	0.9141	0.0000	0.0000	0.0000
cjy_01_jian4	luda_10_1	0.1466	0.0000	0.1466	0.0000
cjy_01_chang2	caofeidian	0.0615	0.0000	0.0615	0.0000
lyjq_01_rlydmx	caofeidian	0.1280	0.1341	0.0000	0.0000
lyjq_01_tldqf	caofeidian	0.0191	0.0185	0.0000	0.0000
lyjq_01_aux_hlgq	bozhong_25_1	0.0090	0.0000	0.0000	0.0000
fgh_01_fqgq	default	0.0000	0.0000	0.0000	0.0000
fgh_01_jb	default	0.0000	0.0000	0.0000	0.0000
fgh_01_jsq	default	0.0000	0.0000	0.0000	0.0000
fgh_01_ss	default	0.0000	0.0000	0.0000	0.0000
byx_01_bjb	default	0.0000	0.0000	0.0000	0.0000
byx_01_byxjy	default	0.0000	0.0000	0.0000	0.0000
byx_01_byx	default	0.0000	0.0000	0.0000	0.0000
byx_01_tqwq	default	0.0000	0.0000	0.0000	0.0000
qh_01_c9fx	default	0.0000	0.0000	0.0000	0.0000
qh_01_jb	default	0.0000	0.0000	0.0000	0.0000
qh_01_c9j	default	0.0000	0.0000	0.0000	0.0000
qh_01_hf	default	0.0000	0.0000	0.0000	0.0000
qh_01_fb	default	0.0000	0.0000	0.0000	0.0000
qh_01_h2	default	0.0000	0.0000	0.0000	0.0000
qh_01_c9	default	0.0000	0.0000	0.0000	0.0000
qh_01_bdb	default	0.0000	0.0000	0.0000	0.0000
qh_01_ben	default	0.0000	0.0000	0.0000	0.0000
qh_01_fywgq	default	0.0000	0.0000	0.0000	0.0000
dcc01_jqwy	default	0.3448	0.0000	0.0000	0.0000
dcc01_fbxgq	default	0.0050	0.0000	0.0000	0.0000
lt01_qty	default	0.4212	0.0000	0.0000	0.0000
dcc01_rlybcp	default	0.1228	0.0000	0.0000	0.0000
lt01_	default	0.5220	0.0000	0.0000	0.0000
dcc01_qty	default	0.2626	0.0000	0.0000	0.0000
dcc01_zlly	default	0.0799	0.0000	0.0000	0.0000
dcc01_sj	default	0.0804	0.0000	0.0000	0.0000
cyjq_02_sny	default	0.0210	0.0210	0.0210	0.0210
cyjq_02_zlcy	default	0.9185	0.9185	0.9185	0.9185
jz_01_jhqgq	default	0.0000	0.0000	0.0000	0.0000
jz_01_sny	default	0.0000	0.0000	0.0000	0.0000
jz_01_ss	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_hhc4	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_jc	default	0.0000	0.0000	0.0000	0.0000
cz_01_p8	default	0.0000	0.0000	0.0000	0.0000
cz_01_p9	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_glzf	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_mtbe	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_sh	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_ss	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_yhsyg	default	0.0000	0.0000	0.0000	0.0000
mtbe_01_mhc4b	default	0.0000	0.0000	0.0000	0.0000
dxe_01_yhsyg	default	0.0000	0.0000	0.0000	0.0000
yb_01_wjq	default	0.0000	0.0000	0.0000	0.0000
yb_01_wy	default	0.0000	0.0000	0.0000	0.0000
yb_01_yb	default	0.0000	0.0000	0.0000	0.0000
byx_01_yb	default	0.0000	0.0000	0.0000	0.0000
ft_01_c9fx	default	0.0000	0.0000	0.0000	0.0000
ft_01_hexc	default	0.0000	0.0000	0.0000	0.0000
ft_01_zzhq	default	0.0000	0.0000	0.0000	0.0000
ft_01_c9fx_out	default	0.0000	0.0000	0.0000	0.0000
ft_01_px	default	0.0000	0.0000	0.0000	0.0000
ft_01_v402dq	default	0.0000	0.0000	0.0000	0.0000
dcc01_jyzy	default	0.2328	0.0000	0.0000	0.0000
ft_01_fb	default	0.0000	0.0000	0.0000	0.0000
ft_01_fywgq_out	default	0.0000	0.0000	0.0000	0.0000
ft_01_hexc_out	default	0.0000	0.0000	0.0000	0.0000
ft_01_zfc10	default	0.0000	0.0000	0.0000	0.0000
ft_01_ss	default	0.0000	0.0000	0.0000	0.0000
jz_01_yhq	default	0.0000	0.0000	0.0000	0.0000
cz_01_p2	default	0.0000	0.0000	0.0000	0.0000
cjy_01_jyzy	luda_10_1	0.2822	0.0000	0.2822	0.0000
cjy_01_zlsny	luda_10_1	0.0712	0.0000	0.0712	0.0000
cyjq_01_aux_h2	luda_10_1	0.0224	0.0000	0.0000	0.0000
cyjq_01_hangmei	luda_10_1	0.0578	0.0073	0.2178	0.2180
cyjq_01_hldfq	luda_10_1	0.0110	0.0109	0.0110	0.0109
cyjq_01_hlgq	luda_10_1	0.0141	0.0144	0.0141	0.0144
cyjq_01_hlyhq	luda_10_1	0.0691	0.0630	0.0691	0.0630
cyjq_01_main_rlybcp	luda_10_1	0.0000	0.0000	0.0000	0.0000
cyjq_01_main_zlcy	luda_10_1	0.9776	0.0000	0.0000	0.0000
cyjq_01_qsny	luda_10_1	0.0908	0.0891	0.0908	0.0891
cyjq_01_rlydmx	luda_10_1	0.3260	0.3890	0.1306	0.1419
cyjq_01_zsny	luda_10_1	0.4396	0.4211	0.4396	0.4211
lyjq_01_aux_c5	luda_10_1	0.0219	0.0000	0.0000	0.0000
lyjq_01_aux_h2	luda_10_1	0.0076	0.0000	0.0000	0.0000
lyjq_01_aux_hldfq	luda_10_1	0.0043	0.0000	0.0000	0.0000
lyjq_01_aux_hlgq	luda_10_1	0.0090	0.0000	0.0000	0.0000
cjy_01_chang2	luda_10_1	0.0800	0.0000	0.0800	0.0000
lyjq_01_c5	luda_10_1	0.0510	0.0436	0.0000	0.0000
cjy_01_250rly	luda_10_1	0.0388	0.0000	0.0388	0.0000
cjy_01_chang1	luda_10_1	0.0393	0.0000	0.0393	0.0000
lyjq_01_main_zlyy	luda_10_1	0.8330	0.0000	0.0000	0.0000
lyjq_01_gyyw	luda_10_1	0.0170	0.0156	0.0000	0.0000
lyjq_01_hangmei	luda_10_1	0.0881	0.0938	0.0000	0.0000
lyjq_01_aux_hlyhq	luda_10_1	0.0252	0.0000	0.0000	0.0000
lyjq_01_aux_qsny	luda_10_1	0.0355	0.0000	0.0000	0.0000
lyjq_01_aux_tdq	luda_10_1	0.0006	0.0000	0.0000	0.0000
lyjq_01_main_dcc	luda_10_1	0.0300	0.0000	0.0000	0.0000
lyjq_01_main_dny	luda_10_1	0.0330	0.0000	0.0000	0.0000
yjq_01_cyy	default	0.1435	0.0000	0.0000	0.0000
yjq_01_qt	default	0.0178	0.0000	0.0000	0.0000
yjq_01_sny	default	0.4105	0.0000	0.0000	0.0000
lyjq_01_yhq	luda_10_1	0.0527	0.0458	0.0000	0.0000
lyjq_01_hcsny	luda_10_1	0.1631	0.1679	0.0000	0.0000
lyjq_01_jqwy	luda_10_1	0.3210	0.3213	0.0000	0.0000
lyjq_01_rlydmb	luda_10_1	0.1481	0.1470	0.0000	0.0000
lyjq_01_rlydmx	luda_10_1	0.1366	0.1427	0.0000	0.0000
lyjq_01_tldqf	luda_10_1	0.0175	0.0168	0.0000	0.0000
lyjq_01_tlgq	luda_10_1	0.0050	0.0055	0.0000	0.0000
lyjq_01_aux_qsny	caofeidian	0.0490	0.0000	0.0000	0.0000
lyjq_01_aux_tdq	caofeidian	0.0007	0.0000	0.0000	0.0000
lyjq_01_main_dcc	caofeidian	0.0269	0.0000	0.0000	0.0000
lyjq_01_main_dny	caofeidian	0.0290	0.0000	0.0000	0.0000
lyjq_01_main_zlyy	caofeidian	0.8087	0.0000	0.0000	0.0000
lyjq_01_gyyw	caofeidian	0.0205	0.0191	0.0000	0.0000
lyjq_01_hangmei	caofeidian	0.0760	0.0817	0.0000	0.0000
lyjq_01_hcsny	caofeidian	0.1582	0.1629	0.0000	0.0000
lyjq_01_jqwy	caofeidian	0.3184	0.3187	0.0000	0.0000
lyjq_01_rlydmb	caofeidian	0.1463	0.1451	0.0000	0.0000
lyjq_01_tlgq	caofeidian	0.0061	0.0068	0.0000	0.0000
lyjq_01_yhq	caofeidian	0.0649	0.0581	0.0000	0.0000
cjy_01_250rly	caofeidian	0.0353	0.0000	0.0353	0.0000
cjy_01_chang3	caofeidian	0.0705	0.0000	0.0705	0.0000
cjy_01_jian1	caofeidian	0.1160	0.0000	0.1160	0.0000
cjy_01_jian2	caofeidian	0.1208	0.0000	0.1208	0.0000
cjy_01_jian3	caofeidian	0.0541	0.0000	0.0541	0.0000
cyjq_01_aux_h2	caofeidian	0.0221	0.0000	0.0000	0.0000
cyjq_01_hangmei	caofeidian	0.0606	0.0101	0.2147	0.2149
cyjq_01_hldfq	caofeidian	0.0106	0.0115	0.0106	0.0115
cjy_01_jian4	caofeidian	0.1287	0.0000	0.1287	0.0000
dxe_01_zc4	default	0.0000	0.0000	0.0000	0.0000
dxe_01_1dc	default	0.0000	0.0000	0.0000	0.0000
cz_01_p10	default	0.0000	0.0000	0.0000	0.0000
dcc01_gq	default	0.0930	0.0000	0.0000	0.0000
dcc01_ljsny	default	0.2057	0.0000	0.0000	0.0000
cyjq_02_h2	default	0.0251	0.0251	0.0251	0.0251
cyjq_01_main_rlybcp	caofeidian	0.0000	0.0000	0.0000	0.0000
cjy_01_chang3	bozhong_25_1	0.0922	0.0000	0.0922	0.0000
cjy_01_jian1	bozhong_25_1	0.1135	0.0000	0.1135	0.0000
cjy_01_jian2	bozhong_25_1	0.1166	0.0000	0.1166	0.0000
cjy_01_jian3	bozhong_25_1	0.0448	0.0000	0.0448	0.0000
cjy_01_jian4	bozhong_25_1	0.1244	0.0000	0.1244	0.0000
cjy_01_250rly	bozhong_25_1	0.0359	0.0000	0.0359	0.0000
cjy_01_chang1	bozhong_25_1	0.0430	0.0000	0.0430	0.0000
cjy_01_chang2	bozhong_25_1	0.0877	0.0000	0.0877	0.0000
cyjq_01_hangmei	bozhong_25_1	0.0638	0.0133	0.1998	0.2001
cyjq_01_hldfq	bozhong_25_1	0.0111	0.0111	0.0111	0.0111
cyjq_01_hlgq	bozhong_25_1	0.0143	0.0152	0.0143	0.0152
cyjq_01_hlyhq	bozhong_25_1	0.0687	0.0636	0.0687	0.0636
cyjq_01_main_rlybcp	bozhong_25_1	0.0000	0.0000	0.0000	0.0000
cyjq_01_main_zlcy	bozhong_25_1	0.9773	0.0000	0.0000	0.0000
cyjq_01_qsny	bozhong_25_1	0.0879	0.0886	0.0879	0.0886
cyjq_01_rlydmx	bozhong_25_1	0.2903	0.3533	0.1409	0.1521
cyjq_01_zsny	bozhong_25_1	0.4506	0.4321	0.4506	0.4321
cjy_01_jyzy	bozhong_25_1	0.2616	0.0000	0.2616	0.0000
cjy_01_zlsny	bozhong_25_1	0.0781	0.0000	0.0781	0.0000
cyjq_01_aux_h2	bozhong_25_1	0.0227	0.0000	0.0000	0.0000
lyjq_01_c5	bozhong_25_1	0.0510	0.0436	0.0000	0.0000
lyjq_01_gyyw	bozhong_25_1	0.0170	0.0155	0.0000	0.0000
lyjq_01_hangmei	bozhong_25_1	0.0851	0.0908	0.0000	0.0000
lyjq_01_hcsny	bozhong_25_1	0.1669	0.1717	0.0000	0.0000
lyjq_01_jqwy	bozhong_25_1	0.3236	0.3239	0.0000	0.0000
lyjq_01_rlydmb	bozhong_25_1	0.1450	0.1438	0.0000	0.0000
ct_01_c8fx	default	0.0000	0.0000	0.0000	0.0000
ct_01_bdb	default	0.0000	0.0000	0.0000	0.0000
ct_01_cb	default	0.0000	0.0000	0.0000	0.0000
ct_01_h2	default	0.0000	0.0000	0.0000	0.0000
ct_01_ben	default	0.0000	0.0000	0.0000	0.0000
qf_01_p1	default	0.0000	0.0000	0.0000	0.0000
dcc01_jbxwq	default	0.0042	0.0000	0.0000	0.0000
dcc01_hhc4	default	0.0708	0.0000	0.0000	0.0000
dcc01_c5xt	default	0.0345	0.0000	0.0000	0.0000
cyjq_02_hldfq	default	0.0117	0.0117	0.0117	0.0117
yjq_01_jqzs	default	0.4277	0.0000	0.0000	0.0000
yjq_01_h2	default	0.0005	0.0000	0.0000	0.0000
cyjq_02_cyhq	default	0.0891	0.0891	0.0891	0.0891
cyjq_02_qsny	default	0.1127	0.1127	0.1127	0.1127
cyjq_02_zsny	default	0.5973	0.5973	0.5973	0.5973
cyjq_02_hm	default	0.0379	0.0379	0.1524	0.1524
cyjq_02_cy	default	0.1524	0.1524	0.0379	0.0379
cyjq_02_qzsny	default	0.0001	0.0000	0.0000	0.0000
ct_01_zzscy	default	0.0000	0.0000	0.0000	0.0000
qh_01_wq	default	0.0000	0.0000	0.0000	0.0000
qh_01_ss	default	0.0000	0.0000	0.0000	0.0000
ft_01_c8	default	0.0000	0.0000	0.0000	0.0000
yjq_01_bty	default	0.0736	0.0000	0.0000	0.0000
cz_01_p4	default	0.0000	0.0000	0.0000	0.0000
yjq_01_hlgq	default	0.0097	0.0000	0.0000	0.0000
cz_01_p5	default	0.0000	0.0000	0.0000	0.0000
dcc01_yj	default	0.0500	0.0000	0.0000	0.0000
cz_01_p3	default	0.0000	0.0000	0.0000	0.0000
lt01_zty	default	0.0556	0.0000	0.0000	0.0000
cz_01_p6	default	0.0000	0.0000	0.0000	0.0000
cz_01_p7	default	0.0000	0.0000	0.0000	0.0000
jz_01_jysny	default	0.0000	0.0000	0.0000	0.0000
jz_01_gq	default	0.0000	0.0000	0.0000	0.0000
jz_01_qgq	default	0.0000	0.0000	0.0000	0.0000
jz_01_pal	default	0.0000	0.0000	0.0000	0.0000
jz_01_fal	default	0.0000	0.0000	0.0000	0.0000
jz_01_jzyhq	default	0.0000	0.0000	0.0000	0.0000
cyjq_02_jzcy	default	0.0137	0.0137	0.0137	0.0137
cyjq_02_ljcy	default	0.0005	0.0005	0.0005	0.0005
cyjq_02_dcccy	default	0.0020	0.0020	0.0020	0.0020
cyjq_02_hlgq	default	0.0105	0.0105	0.0105	0.0105
qh_01_qt	default	0.0000	0.0000	0.0000	0.0000
qf_01_p2	default	0.0000	0.0000	0.0000	0.0000
lyjq_01_hcsny	qinhuangdao	0.1651	0.1699	0.0000	0.0000
lyjq_01_jqwy	qinhuangdao	0.3243	0.3247	0.0000	0.0000
cyjq_01_hlyhq	qinhuangdao	0.0691	0.0630	0.0691	0.0630
cyjq_01_main_rlybcp	qinhuangdao	0.0000	0.0000	0.0000	0.0000
cjy_01_chang1	qinhuangdao	0.0234	0.0000	0.0234	0.0000
cjy_01_chang2	qinhuangdao	0.0626	0.0000	0.0626	0.0000
cjy_01_chang3	qinhuangdao	0.0668	0.0000	0.0668	0.0000
cjy_01_jian1	qinhuangdao	0.1079	0.0000	0.1079	0.0000
cjy_01_jian2	qinhuangdao	0.1210	0.0000	0.1210	0.0000
cjy_01_jian3	qinhuangdao	0.0446	0.0000	0.0446	0.0000
cjy_01_jian4	qinhuangdao	0.1185	0.0000	0.1185	0.0000
cjy_01_jyzy	qinhuangdao	0.3608	0.0000	0.3608	0.0000
cjy_01_zlsny	qinhuangdao	0.0636	0.0000	0.0636	0.0000
cyjq_01_aux_h2	qinhuangdao	0.0236	0.0000	0.0000	0.0000
cyjq_01_hangmei	qinhuangdao	0.0578	0.0073	0.2178	0.2180
cyjq_01_hldfq	qinhuangdao	0.0110	0.0109	0.0110	0.0109
cyjq_01_hlgq	qinhuangdao	0.0141	0.0144	0.0141	0.0144
cyjq_01_main_zlcy	qinhuangdao	0.9764	0.0000	0.0000	0.0000
cyjq_01_qsny	qinhuangdao	0.0908	0.0891	0.0908	0.0891
cjy_01_250rly	qinhuangdao	0.0323	0.0000	0.0323	0.0000
lyjq_01_hangmei	qinhuangdao	0.0854	0.0912	0.0000	0.0000
qf_01_p3	default	0.0000	0.0000	0.0000	0.0000
lyjq_01_aux_hlyhq	bozhong_25_1	0.0252	0.0000	0.0000	0.0000
lyjq_01_aux_qsny	bozhong_25_1	0.0337	0.0000	0.0000	0.0000
lyjq_01_aux_tdq	bozhong_25_1	0.0006	0.0000	0.0000	0.0000
lyjq_01_main_dcc	bozhong_25_1	0.0280	0.0000	0.0000	0.0000
lyjq_01_main_dny	bozhong_25_1	0.0304	0.0000	0.0000	0.0000
lyjq_01_c5	qinhuangdao	0.0523	0.0449	0.0000	0.0000
lyjq_01_gyyw	qinhuangdao	0.0156	0.0141	0.0000	0.0000
lyjq_01_rlydmb	qinhuangdao	0.1455	0.1444	0.0000	0.0000
lyjq_01_rlydmx	qinhuangdao	0.1451	0.1513	0.0000	0.0000
lyjq_01_tldqf	qinhuangdao	0.0178	0.0172	0.0000	0.0000
lyjq_01_tlgq	qinhuangdao	0.0032	0.0038	0.0000	0.0000
lyjq_01_yhq	qinhuangdao	0.0457	0.0386	0.0000	0.0000
lyjq_01_main_dny	qinhuangdao	0.0337	0.0000	0.0000	0.0000
lyjq_01_main_zlyy	qinhuangdao	0.8271	0.0000	0.0000	0.0000
cyjq_01_rlydmx	qinhuangdao	0.3260	0.3890	0.1306	0.1419
cyjq_01_zsny	qinhuangdao	0.4396	0.4211	0.4396	0.4211
lyjq_01_aux_c5	qinhuangdao	0.0245	0.0000	0.0000	0.0000
lyjq_01_aux_h2	qinhuangdao	0.0089	0.0000	0.0000	0.0000
lyjq_01_aux_hldfq	qinhuangdao	0.0043	0.0000	0.0000	0.0000
lyjq_01_aux_hlgq	qinhuangdao	0.0095	0.0000	0.0000	0.0000
lyjq_01_aux_hlyhq	qinhuangdao	0.0247	0.0000	0.0000	0.0000
lyjq_01_aux_qsny	qinhuangdao	0.0341	0.0000	0.0000	0.0000
lyjq_01_aux_tdq	qinhuangdao	0.0006	0.0000	0.0000	0.0000
lyjq_01_main_dcc	qinhuangdao	0.0325	0.0000	0.0000	0.0000
\.


--
-- PostgreSQL database dump complete
--

\unrestrict yLyGhRZjbIU5rYUhUhV1dgoU8YDHIvS8KZrNdYrYhMdauJ0ldeiOae9YnYORukF


-- solve_db.devices_units
--
-- PostgreSQL database dump
--

\restrict nnqMeeoO78KkwDFYnTZdKZkEauayfqYuX2lT8sw42iWuZIYqeaiP77na7bQvX4Y

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: devices_units; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.devices_units (device_id, name, type, max_capacity, safety_stock_thrd, low_safety_thrd, current_capacity, refinery_unit_load_pct, device_id_2, backend_device_id, note, enabled) FROM stdin;
cdy_02	Ⅲ常减压	normal	\N	17142.857	10285.714	0.000	100.00	\N	4	\N	f
cjy_01	2#常减压	start	0.000	17142.580	10285.548	0.000	100.00	\N	1	提升500	t
ct_01	1#抽提	normal	\N	1571.429	942.857	0.000	100.00	\N	18	\N	f
ct_02	2#抽提	normal	\N	1142.857	685.714	0.000	100.00	\N	19	\N	f
ct_03	3#抽提	normal	\N	2285.714	1371.428	0.000	100.00	\N	20	\N	f
ct_04	4#抽提	normal	\N	1285.714	771.428	0.000	100.00	\N	21	\N	f
cyjq_01	1#加裂	normal	0.000	4286.510	2571.906	0.000	100.00	\N	2	提升2000	t
cyjq_02	2#加裂	normal	\N	4286.510	2571.906	0.000	100.00	\N	6	\N	f
cz_01	1#重整	normal	\N	4285.714	2571.428	0.000	100.00	\N	16	\N	f
cz_02	2#重整	normal	\N	4285.714	2571.428	0.000	100.00	\N	17	\N	f
dcc_01	1#DCC	normal	\N	6285.710	0.000	0.000	100.00	\N	8	\N	f
dcc_02	2#DCC	normal	\N	9142.857	5485.714	0.000	100.00	\N	9	\N	f
dxe_01	1-丁烯	normal	\N	171.429	102.857	0.000	100.00	\N	30	\N	f
fgh_01	芳构化	normal	\N	1428.571	857.143	0.000	100.00	\N	22	\N	f
ft_01	芳烃	normal	\N	4571.429	2742.857	0.000	100.00	\N	24	\N	f
psa_01	1#PSA	normal	\N	228.571	137.143	0.000	100.00	\N	35	\N	f
psa_02	2#PSA	normal	\N	285.714	171.428	0.000	100.00	\N	36	\N	f
qf_01	气分	normal	\N	2857.143	1714.286	0.000	100.00	\N	28	\N	f
qh_01	歧化	normal	\N	9714.286	5828.572	0.000	100.00	\N	23	\N	f
qths_01	轻烃回收	normal	\N	4571.429	2742.857	0.000	100.00	\N	33	\N	f
snyjy_01	1#石脑油加氢	normal	\N	1714.286	1028.572	0.000	100.00	\N	10	\N	f
snyjy_02	2#石脑油加氢	normal	\N	2857.143	1714.286	0.000	100.00	\N	11	\N	f
xtfl_01	烯烃分离	normal	\N	714.286	428.572	0.000	100.00	\N	34	\N	f
yb_01	乙苯	normal	\N	857.143	514.286	0.000	100.00	\N	25	\N	f
yjq_01	1#预加氢	normal	\N	3714.286	2228.572	0.000	100.00	\N	14	\N	f
yjq_02	2#预加氢	normal	\N	2857.143	1714.286	0.000	100.00	\N	15	\N	f
zq_01	制氢	normal	\N	171.429	102.857	0.000	100.00	\N	37	\N	f
hmjy_01	航煤加氢	normal	\N	1714.286	1028.572	0.000	100.00	\N	13	\N	f
jbx_01	1#聚丙烯	normal	\N	857.143	514.286	0.000	100.00	\N	31	\N	f
jbx_02	2#聚丙烯	normal	\N	1285.714	771.428	0.000	100.00	\N	32	\N	f
jz_01	精制	normal	\N	3657.143	2194.286	0.000	100.00	\N	27	\N	f
ljcyjy_01	裂解柴油加氢	normal	\N	2000.000	1200.000	0.000	100.00	\N	12	\N	f
lt_01	溶脱	normal	\N	4571.430	0.000	0.000	100.00	\N	5	\N	f
lyjq_01	1#蜡加	normal	0.000	6000.000	3600.000	0.000	100.00	\N	3	6000提升到8000	t
lyjq_02	2#蜡加	normal	\N	6285.714	3771.428	0.000	100.00	\N	7	\N	f
mtbe_01	MTBE	normal	\N	428.571	257.143	0.000	100.00	\N	29	\N	f
byx_01	苯乙烯	normal	\N	800.000	480.000	0.000	100.00	\N	26	\N	f
\.


--
-- PostgreSQL database dump complete
--

\unrestrict nnqMeeoO78KkwDFYnTZdKZkEauayfqYuX2lT8sw42iWuZIYqeaiP77na7bQvX4Y


-- solve_db.devices_tanks
--
-- PostgreSQL database dump
--

\restrict HXBehzVPy50N2ZlSmlmA1uEuwwdow64f7LDi1ZeGzz2gNV2cvVB3xXhh7JL3NlH

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: devices_tanks; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.devices_tanks (device_id, name, max_capacity, safety_stock_thrd, low_safety_thrd, current_capacity, refinery_unit_load_pct, tank_category, note, enabled, material_id) FROM stdin;
h2_guan_01	氢气管道	\N	100000.000	0.000	0.000	100.00	intermediate	\N	f	\N
atapu_tank_01	ATAPU	\N	173254.559	17638.639	0.000	100.00	crude	\N	f	\N
ben_tank_01	苯	\N	12767.549	1961.155	0.000	100.00	intermediate	\N	f	\N
bhlpg_tank_01	饱和LPG	\N	9569.482	757.113	0.000	100.00	intermediate	\N	f	\N
bx_tank_01	丙烯	\N	7273.564	597.610	0.000	100.00	product	\N	f	\N
byx_tank_01	苯乙烯	\N	19801.475	1080.242	0.000	100.00	product	\N	f	\N
byxjy_tank_01	苯乙烯焦油	\N	2941.903	175.202	0.000	100.00	intermediate	\N	f	\N
bz_tank_01	BZ	\N	171751.803	20453.450	0.000	100.00	crude	\N	f	\N
c10cfx_tank_01	C10粗芳烃	\N	5729.215	844.042	0.000	100.00	product	\N	f	\N
c5_tank_01	戊烷（碳五）	0.000	4599.236	199.894	1603.766	100.00	product	8	t	\N
c6c8fx_tank_01	C6-C8芳烃	\N	8641.354	1248.656	0.000	100.00	intermediate	\N	f	\N
cjydbzh_tank_01	预加氢进料	0.000	35374.314	5092.145	14033.897	100.00	product	26	t	\N
cycy_tank_01	车用柴油	\N	82239.592	9128.285	0.000	100.00	product	\N	f	\N
cyqy_tank_01	车用汽油	\N	38952.673	4445.031	0.000	100.00	product	\N	f	\N
cyzy_tank_01	常压渣油	\N	8735.933	428.741	0.000	100.00	intermediate	\N	f	\N
dccyl_tank_01	DCC原料	\N	24065.581	1128.656	10000.000	100.00	intermediate	\N	f	\N
dxb1_tank_01	1-丁烯	\N	4232.147	111.220	0.000	100.00	product	\N	f	\N
esposepia_tank_01	ESPO/SEPIA	\N	83692.587	9127.631	0.000	100.00	crude	\N	f	\N
gyc9fx_tank_01	工业用碳九芳烃	\N	17821.959	2630.457	0.000	100.00	intermediate	\N	f	\N
gyrly_tank_01	工业燃料油加氢原料	0.000	66580.436	8085.942	27451.035	100.00	intermediate	21	t	7
hc_tank_01	HC原料	0.000	51882.977	7164.844	28851.428	100.00	intermediate	22	t	8
hm_tank_01	航煤	0.000	26502.900	4069.775	17548.516	100.00	product	2	t	\N
hxxpx_tank_01	石油混二甲苯（PX装置用）	\N	17074.544	2617.088	0.000	100.00	intermediate	\N	f	\N
jb_tank_01	甲苯	\N	8383.614	1287.353	0.000	100.00	product	\N	f	\N
jc_tank_01	甲醇	\N	7657.183	1177.815	0.000	100.00	product	\N	f	\N
jqwy_tank_01	加氢尾油	0.000	20000.337	816.934	13908.037	100.00	intermediate	4	f	33
jzsny_tank_01	精制石脑油	\N	3877.375	564.046	0.000	100.00	intermediate	\N	f	\N
ldlh_tank_01	LD/LH	\N	43477.147	5303.445	0.000	100.00	intermediate	\N	f	\N
ljsny_tank_01	裂解石脑油	\N	4673.417	776.406	0.000	100.00	intermediate	\N	f	\N
mtbe_tank_01	MTBE	\N	11302.381	1698.569	0.000	100.00	product	\N	f	\N
pxcp_tank_01	对二甲苯	\N	61601.764	9557.733	0.000	100.00	product	\N	f	\N
qfyhq_tank_01	气分液化气	\N	1179.490	103.762	0.000	100.00	intermediate	\N	f	\N
qhdnp_tank_01	QHD/NP	\N	266038.246	29371.660	0.000	100.00	crude	\N	f	\N
qwy_tank_01	轻污油	\N	6770.278	423.215	0.000	100.00	intermediate	\N	f	\N
rdyl_tank_01	溶脱原料	\N	27636.158	867.270	10000.000	100.00	intermediate	\N	f	\N
rly250_tank_01	250#燃料油	0.000	71384.134	1989.838	38187.317	100.00	product	25	t	\N
rlydmb_tank_01	燃料油DMB	0.000	14611.870	592.407	4496.065	100.00	product	5	t	\N
rlyfd1_tank_01	燃料油F-D1	\N	14181.599	546.703	0.000	100.00	product	\N	f	\N
sepiatupi_tank_01	SEPIA/TUPI	\N	127743.676	14918.041	0.000	100.00	crude	\N	f	\N
snyhgl_tank_01	石脑油（互供料）	\N	14062.389	1991.140	0.000	100.00	intermediate	\N	f	\N
snyqyl_tank_01	石脑油（汽油料）	\N	6872.734	1048.136	0.000	100.00	intermediate	\N	f	\N
snyyxl_tank_01	石脑油（乙烯料）	\N	19927.103	3043.813	0.000	100.00	intermediate	\N	f	\N
thy_tank_01	烃化液	\N	4208.648	648.658	0.000	100.00	intermediate	\N	f	\N
tqy_tank_01	脱氢液	\N	6605.000	800.018	0.000	100.00	intermediate	\N	f	\N
wtcwxt_tank_01	戊烷（碳五烯烃）	\N	1525.354	66.119	0.000	100.00	product	\N	f	\N
wtfpj_tank_01	戊烷发泡剂	\N	1426.092	64.265	0.000	100.00	product	\N	f	\N
wy_tank_01	污油	\N	33349.411	4477.244	0.000	100.00	intermediate	\N	f	\N
yb_tank_01	乙苯	\N	9720.409	1336.237	0.000	100.00	intermediate	\N	f	\N
yhsyqgy_tank_01	液化石油气（工业用）	\N	7101.488	317.604	0.000	100.00	product	\N	f	\N
yhsyqhc4_tank_01	液化石油气（混合C4）	\N	1425.004	81.404	0.000	100.00	intermediate	\N	f	\N
zwy_tank_01	重污油	\N	9140.833	626.298	0.000	100.00	product	\N	f	\N
zyrly_tank_01	自用燃料油	\N	865.928	52.882	0.000	100.00	product	\N	f	\N
zzscy_tank_01	重整生成油	\N	16877.673	2261.470	0.000	100.00	intermediate	\N	f	\N
\.


--
-- PostgreSQL database dump complete
--

\unrestrict HXBehzVPy50N2ZlSmlmA1uEuwwdow64f7LDi1ZeGzz2gNV2cvVB3xXhh7JL3NlH


-- solve_db.energy
--
-- PostgreSQL database dump
--

\restrict CiPkkQXePKnh5fwRdQqemO9HBXoeLhM82aayRLtp5Navnt6eqg6UPGQRURb0Dvs

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: energy; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.energy (id, device_id, consumption_per_ton, price_per_unit, energy_type) FROM stdin;
energy_id_001	cyjq_01	0.02330000	8532.3400	氢气
energy_id_002	cyjq_01	0.00836321	2628.3186	燃料气\n（吨）
energy_id_003	cyjq_01	0.00000000	0.0000	解吸气\n（吨）
energy_id_004	cyjq_01	0.00000000	0.0000	催化烧焦\n（吨）
energy_id_005	cyjq_01	0.00000000	256.8807	11.5MPa蒸汽\n（吨）
energy_id_006	cyjq_01	0.16376338	229.3578	3.5MPa蒸汽\n（吨）
energy_id_007	cyjq_01	-0.09349283	201.8349	1.0MPa蒸汽\n（吨）
energy_id_008	cyjq_01	0.00000000	4.1900	市政新鲜水\n（吨）
energy_id_009	cyjq_01	0.00091179	2.2700	大工业水\n（吨）
energy_id_010	cyjq_01	0.05903482	12.5872	二级除盐水\n（吨）
energy_id_011	cyjq_01	2.57760794	0.4103	0.8MPa氮气\n(Nm3)
energy_id_012	cyjq_01	27.58040808	0.5752	电\n(kwh）
energy_id_013	cyjq_01	0.06521956	32.8378	除氧水(吨)
energy_id_014	cyjq_01	11.95770134	0.1400	循环水(吨)
energy_id_015	cyjq_01	-4.59138081	2.0183	低温热水(吨)
energy_id_016	cyjq_01	0.00000000	2.0000	冷媒水(吨)
energy_id_017	cyjq_01	0.00000000	15.0000	透平凝结水(吨)
energy_id_018	cyjq_01	-0.00752196	10.0000	加热设备凝结水(吨)
energy_id_019	cyjq_01	5.54231043	0.0832	净化风(Nm3)
energy_id_020	cyjq_01	0.00000000	0.0832	非净化风(Nm3)
energy_id_021	cyjq_01	0.00000000	0.4103	1.0MPa氮气(Nm3)
energy_id_022	cyjq_01	0.00000000	0.5500	3.0MPa氮气(Nm3)
energy_id_023	cyjq_01	0.00000000	0.5500	6.0MPa氮气(Nm3)
energy_id_024	cyjq_01	0.00000000	0.4103	0.35MPa蒸汽(吨)
energy_id_025	cyjq_01	0.00000000	0.4103	0.25MPa蒸汽(吨)
energy_id_026	cyjq_01	1.00000000	0.0151	缓蚀阻垢剂
energy_id_027	cyjq_01	1.00000000	0.0959	阻垢剂
energy_id_028	cyjq_01	1.00000000	0.0298	缓蚀剂
energy_id_029	cyjq_01	0.00000000	0.0000	航煤抗氧化剂
energy_id_030	cyjq_01	1.00000000	8.6450	折旧及摊销
energy_id_031	cyjq_01	1.00000000	6.9520	人工成本
energy_id_032	cyjq_01	1.00000000	3.6409	修理
energy_id_033	cyjq_01	1.00000000	8.4296	公共摊销
energy_id_034	lyjq_01	0.01010000	8532.3400	新氢
energy_id_035	lyjq_01	0.03530000	3425.1000	DCC柴油
energy_id_036	lyjq_01	0.02800000	2903.9900	加氢C5馏分
energy_id_037	lyjq_01	0.00080000	2276.5600	塔顶气
energy_id_038	lyjq_01	0.03760000	3522.4100	低氮油
energy_id_039	lyjq_01	0.00795173	2628.3186	燃料气\n（吨）
energy_id_040	lyjq_01	0.00000000	0.0000	解吸气\n（吨）
energy_id_041	lyjq_01	0.00000000	0.0000	催化烧焦\n（吨）
energy_id_042	lyjq_01	0.00000000	256.8807	11.5MPa蒸汽\n（吨）
energy_id_043	lyjq_01	0.00000000	229.3578	3.5MPa蒸汽\n（吨）
energy_id_044	lyjq_01	0.09627270	201.8349	1.0MPa蒸汽\n（吨）
energy_id_045	lyjq_01	0.00000000	4.1900	市政新鲜水\n（吨）
energy_id_046	lyjq_01	0.00024476	2.2700	大工业水\n（吨）
energy_id_047	lyjq_01	0.09091816	12.5872	二级除盐水\n（吨）
energy_id_048	lyjq_01	0.12317885	0.4103	0.8MPa氮气\n(Nm3)
energy_id_049	lyjq_01	42.67002611	0.5752	电\n(kwh）
energy_id_050	lyjq_01	0.20387161	32.8378	除氧水(吨)
energy_id_051	lyjq_01	13.81347753	0.1400	循环水(吨)
energy_id_052	lyjq_01	-4.39091081	2.0183	低温热水(吨)
energy_id_053	lyjq_01	1.04485709	2.0000	冷媒水(吨)
energy_id_054	lyjq_01	-0.00472500	15.0000	透平凝结水(吨)
energy_id_055	lyjq_01	-0.02542975	10.0000	加热设备凝结水(吨)
energy_id_056	lyjq_01	2.23909050	0.0832	净化风(Nm3)
energy_id_057	lyjq_01	0.00000000	0.0832	非净化风(Nm3)
energy_id_058	lyjq_01	0.00000000	0.4103	1.0MPa氮气(Nm3)
energy_id_059	lyjq_01	0.00000000	0.5500	3.0MPa氮气(Nm3)
energy_id_060	lyjq_01	0.00000000	0.5500	6.0MPa氮气(Nm3)
energy_id_061	lyjq_01	0.00000000	0.4103	0.35MPa蒸汽(吨)
energy_id_062	lyjq_01	0.00000000	0.4103	0.25MPa蒸汽(吨)
energy_id_063	lyjq_01	1.00000000	0.0129	缓释阻垢剂
energy_id_064	lyjq_01	1.00000000	0.0000	阻聚剂JM-2A
energy_id_065	lyjq_01	1.00000000	5.9986	阻聚剂JM-2B
energy_id_066	lyjq_01	1.00000000	0.0255	缓蚀剂
energy_id_067	lyjq_01	1.00000000	0.0005	磷酸三钠
energy_id_068	lyjq_01	1.00000000	0.0968	航煤抗氧化剂
energy_id_069	lyjq_01	1.00000000	25.0333	折旧及摊销
energy_id_070	lyjq_01	1.00000000	8.3180	人工成本
energy_id_071	lyjq_01	1.00000000	4.3565	修理
energy_id_072	lyjq_01	1.00000000	10.0864	公共摊销
\.


--
-- PostgreSQL database dump complete
--

\unrestrict CiPkkQXePKnh5fwRdQqemO9HBXoeLhM82aayRLtp5Navnt6eqg6UPGQRURb0Dvs


-- solve_db.material_flows
--
-- PostgreSQL database dump
--

\restrict HaNbXNgi9XerI8cVEMsCMGEHPL4hmApZFLngJQrMDD5UToinqJAq4aAuW9v6tgs

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: material_flows; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.material_flows (flow_id, source_type, source_device_id, source_product_id, source_name, tank_id, target_device_id, flow_type, special_var, priority, is_unique_target, split_ratio, target_product_id) FROM stdin;
cjy_01_004	device	cjy_01	cjy_01_jyzy	\N	rdyl_tank_01	\N	source_to_tank	\N	10	t	0.7000	\N
mf_028	device	cyjq_02	cyjq_02_hldfq	\N	\N	lyjq_01	direct	\N	0	f	1.0000	lyjq_01_aux_hldfq
mf_100	device	cyjq_01	cyjq_01_zsny	\N	\N	yjq_01	direct	\N	10	f	1.0000	yjq_01_jqzs
mf_102	device	\N	\N	\N	rlyfd1_tank_01	cyjq_01	tank_to_target	\N	0	f	1.0000	cyjq_01_main_rlybcp
mf_103	device	snyjy_01	snyjy_01_tdq	\N	\N	lyjq_01	direct	\N	0	f	1.0000	lyjq_01_aux_tdq
mf_105	device	\N	\N	\N	gyrly_tank_01	lyjq_01	tank_to_target	\N	0	f	0.0000	lyjq_01_main_dny
mf_107	device	dcc_01	dcc01_c5xt	\N	wtcwxt_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_109	device	byx_01	byx_01_bjb	\N	\N	ct_02	direct	\N	0	f	1.0000	ct_02_bjb
mf_110	device	\N	\N	\N	wtcwxt_tank_01	fgh_01	tank_to_target	\N	0	f	1.0000	fgh_01_c5xt
mf_112	device	cz_01	cz_01_p7	\N	\N	fgh_01	direct	\N	0	f	1.0000	fgh_01_twgq
mf_114	device	dxe_01	dxe_01_zc4	\N	\N	fgh_01	direct	\N	0	f	1.0000	fgh_01_zc4
mf_115	device	cyjq_02	cyjq_02_qsny	\N	\N	fgh_01	direct	\N	0	f	1.0000	fgh_01_qsny
mf_119	device	ct_01	ct_01_ctc9lf	\N	gyc9fx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_124	device	ct_01	ct_01_jb	\N	jb_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_150	device	yb_01	yb_01_fbxgq	\N	\N	dcc_01	direct	\N	0	f	1.0000	dcc01_fbxgq
mf_154	device	\N	\N	\N	rlyfd1_tank_01	lyjq_01	tank_to_target	\N	0	f	1.0000	lyjq_01_main_dcc
mf_39	device	\N	\N	\N	h2_guan_01	cyjq_01	tank_to_target	\N	0	f	1.0000	cyjq_01_aux_h2
mf_46	device	dxe_01	dxe_01_1dc	\N	dxb1_tank_01	\N	final	\N	10	f	1.0000	\N
mf_48	device	\N	\N	\N	yhsyqgy_tank_01	dxe_01	tank_to_target	\N	10	f	1.0000	dxe_01_yhsyg
mf_54	device	\N	\N	\N	qfyhq_tank_01	mtbe_01	tank_to_target	\N	10	f	1.0000	mtbe_01_hhc4
mf_56	device	mtbe_01	mtbe_01_mtbe	\N	mtbe_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_60	device	qh_01	qh_01_ben	\N	ben_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_63	device	\N	\N	\N	gyc9fx_tank_01	qh_01	tank_to_target	\N	10	f	1.0000	qh_01_c9fx
mf_66	device	byx_01	byx_01_byx	\N	byx_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_68	device	\N	\N	\N	yb_tank_01	byx_01	tank_to_target	\N	10	f	1.0000	byx_01_yb
mf_72	device	yjq_01	yjq_01_jzsny	\N	jzsny_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_74	device	\N	\N	\N	jzsny_tank_01	cz_01	tank_to_target	\N	10	f	1.0000	cz_01_p3
mf_82	device	\N	\N	\N	byxjy_tank_01	dcc_01	tank_to_target	\N	0	f	1.0000	dcc01_byxjy
mf_84	device	ct_01	ct_01_c9fx	\N	gyc9fx_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_86	device	\N	\N	\N	c6c8fx_tank_01	ct_02	tank_to_target	\N	10	f	1.0000	ct_02_c6c8
mf_88	device	\N	\N	\N	ljsny_tank_01	snyjy_01	tank_to_target	\N	10	f	1.0000	snyjy_01_lpny
mf_90	external	\N	\N	华泰	\N	snyjy_01	input	\N	0	f	1.0000	snyjy_01_cb
mf_91	device	snyjy_01	snyjy_01_c6c8	\N	c6c8fx_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_92	device	\N	\N	\N	jc_tank_01	mtbe_01	tank_to_target	\N	0	f	1.0000	mtbe_01_jc
mf_94	device	dcc_01	dcc01_gq	\N	\N	jz_01	direct	\N	0	f	1.0000	jz_01_gq
mf_98	device	dcc_01	dcc01_ljsny	\N	ljsny_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_99	device	dcc_01	dcc01_yhq	\N	\N	jz_01	direct	\N	10	f	1.0000	jz_01_yhq
cjy_01_005	device	cjy_01	cjy_01_jyzy	\N	dccyl_tank_01	\N	source_to_tank	\N	10	f	0.3000	\N
mf_104	device	snyjy_01	snyjy_01_jc5lf	\N	\N	lyjq_01	direct	\N	0	f	1.0000	lyjq_01_aux_c5
mf_106	device	lyjq_01	lyjq_01_tlgq	\N	\N	fgh_01	direct	\N	0	f	1.0000	fgh_01_tlgq
mf_108	device	cyjq_02	cyjq_02_zsny	\N	\N	yjq_01	direct	\N	0	f	1.0000	yjq_01_jqzs
mf_111	device	\N	\N	\N	c5_tank_01	fgh_01	tank_to_target	\N	0	f	1.0000	fgh_01_c5
mf_113	device	\N	\N	\N	bhlpg_tank_01	fgh_01	tank_to_target	\N	0	f	1.0000	fgh_01_yhq
mf_116	device	mtbe_01	mtbe_01_mhc4b	\N	\N	fgh_01	direct	\N	0	f	1.0000	fgh_01_mhts
mf_120	device	ct_01	ct_01_hf	\N	\N	qh_01	direct	\N	0	f	1.0000	qh_01_hf
mf_125	device	ct_01	ct_01_ben	\N	ben_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_127	device	cz_01	cz_01_p10	\N	c5_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_130	device	yjq_01	yjq_01_hlgq	\N	\N	lyjq_01	direct	\N	0	f	1.0000	lyjq_01_aux_hlgq
mf_131	device	ct_02	ct_02_cyy	\N	\N	yjq_01	direct	\N	0	f	1.0000	yjq_01_cyy
mf_133	device	ct_02	ct_02_ben	\N	ben_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_135	device	fgh_01	fgh_01_ben	\N	ben_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_138	device	ft_01	ft_01_c9fx_out	\N	gyc9fx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_140	device	ft_01	ft_01_fb	\N	\N	qh_01	direct	\N	0	f	1.0000	qh_01_fb
mf_142	device	\N	\N	\N	gyc9fx_tank_01	ft_01	tank_to_target	\N	0	f	1.0000	ft_01_c9fx
mf_145	device	jz_01	jz_01_sny	\N	cjydbzh_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_146	device	\N	\N	\N	jb_tank_01	qh_01	tank_to_target	\N	0	f	1.0000	qh_01_jb
mf_149	device	cz_01	cz_01_p8	\N	\N	ct_01	direct	\N	0	f	1.0000	ct_01_h2
mf_151	device	yb_01	yb_01_bb	\N	gyc9fx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_155	device	ct_01	ct_01_cf	\N	\N	qh_01	direct	\N	10	f	1.0000	qh_01_hf
mf_156	device	ct_02	ct_02_cf	\N	\N	qh_01	direct	\N	10	f	1.0000	qh_01_hf
mf_40	device	\N	\N	\N	h2_guan_01	lyjq_01	tank_to_target	\N	0	f	1.0000	lyjq_01_aux_h2
mf_47	device	dxe_01	dxe_01_mtbe	\N	mtbe_tank_01	\N	final	\N	0	f	1.0000	\N
mf_49	device	\N	\N	\N	h2_guan_01	dxe_01	tank_to_target	\N	0	f	1.0000	dxe_01_h2
mf_51	device	\N	\N	\N	zzscy_tank_01	ct_01	tank_to_target	\N	10	f	1.0000	ct_01_zzscy
mf_55	device	mtbe_01	mtbe_01_yhsyg	\N	yhsyqgy_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_61	device	qh_01	qh_01_c8	\N	\N	ft_01	direct	\N	10	f	1.0000	ft_01_c8
mf_64	device	ft_01	ft_01_px	\N	pxcp_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_67	device	byx_01	byx_01_byxjy	\N	byxjy_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_70	device	\N	\N	\N	cjydbzh_tank_01	yjq_01	tank_to_target	\N	10	f	1.0000	yjq_01_sny
mf_73	device	yjq_01	yjq_01_bty	\N	\N	cz_01	direct	\N	0	f	1.0000	cz_01_p2
mf_75	device	lyjq_01	lyjq_01_hcsny	\N	\N	cz_01	direct	\N	10	f	1.0000	cz_01_p1
mf_79	device	cz_01	cz_01_p9	\N	zzscy_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_83	device	\N	\N	\N	qfyhq_tank_01	dcc_01	tank_to_target	\N	0	f	1.0000	dcc01_hhc4
mf_85	device	ct_02	ct_02_hexc	\N	hxxpx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_117	device	ct_01	ct_01_cyy	\N	\N	fgh_01	direct	\N	0	f	1.0000	fgh_01_cyy
mf_121	device	ct_01	ct_01_hexc	\N	hxxpx_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_126	device	fgh_01	fgh_01_cb	\N	\N	ct_01	direct	\N	0	f	1.0000	ct_01_cb
mf_128	device	fgh_01	fgh_01_c8	\N	\N	ct_01	direct	\N	0	f	1.0000	ct_01_c8fx
mf_132	device	qh_01	qh_01_qt	\N	\N	yjq_01	direct	\N	0	f	1.0000	yjq_01_qt
mf_134	device	ct_02	ct_02_jb	\N	jb_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_136	device	fgh_01	fgh_01_jb	\N	jb_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_139	device	ft_01	ft_01_zfc10	\N	c10cfx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_143	device	qh_01	qh_01_fywgq	\N	\N	ft_01	direct	\N	0	f	1.0000	ft_01_fywgq
mf_147	device	cz_01	cz_01_p8	\N	\N	qh_01	direct	\N	0	f	1.0000	qh_01_h2
mf_152	device	dcc_01	dcc01_rlybcp	\N	rlyfd1_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_57	device	yb_01	yb_01_yb	\N	yb_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_59	device	jz_01	jz_01_jhgq	\N	\N	yb_01	direct	\N	0	f	1.0000	yb_01_jhgq
mf_62	device	qh_01	qh_01_bdb	\N	\N	ct_01	direct	\N	0	f	1.0000	ct_01_bdb
mf_65	device	\N	\N	\N	hxxpx_tank_01	ft_01	tank_to_target	\N	10	f	1.0000	ft_01_hexc
mf_80	device	cz_01	cz_01_p5	\N	bhlpg_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_89	device	\N	\N	\N	h2_guan_01	snyjy_01	tank_to_target	\N	0	f	1.0000	snyjy_01_xq
mf_93	device	\N	\N	\N	jc_tank_01	dxe_01	tank_to_target	\N	0	f	1.0000	dxe_01_jc
mf_95	device	qf_01	qf_01_p2	\N	qfyhq_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_96	device	qf_01	qf_01_p3	\N	bx_tank_01	\N	source_to_tank	\N	10	f	1.0000	\N
mf_97	device	jz_01	jz_01_jzyhq	\N	\N	qf_01	direct	\N	10	f	1.0000	qf_01_p1
cjy_01_cjy_01_chang1_gyrly_tank_01	device	cjy_01	cjy_01_chang1	\N	gyrly_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
cjy_01_cjy_01_chang2_gyrly_tank_01	device	cjy_01	cjy_01_chang2	\N	gyrly_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
cjy_01_cjy_01_chang3_gyrly_tank_01	device	cjy_01	cjy_01_chang3	\N	gyrly_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
cjy_01_cjy_01_jian1_gyrly_tank_01	device	cjy_01	cjy_01_jian1	\N	gyrly_tank_01	\N	source_to_tank	jian1_to_diesel	10	f	1.0000	\N
cjy_01_cjy_01_jian1_hc_tank_01	device	cjy_01	cjy_01_jian1	\N	hc_tank_01	\N	source_to_tank	jian1_to_wax	10	f	1.0000	\N
cjy_01_cjy_01_jian2_hc_tank_01	device	cjy_01	cjy_01_jian2	\N	hc_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
cjy_01_cjy_01_jian3_hc_tank_01	device	cjy_01	cjy_01_jian3	\N	hc_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
cjy_01_cjy_01_jian4_hc_tank_01	device	cjy_01	cjy_01_jian4	\N	hc_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
ddc_001	tank	\N	\N	\N	dccyl_tank_01	dcc_01	tank_to_target	\N	10	f	1.0000	dcc01_jyzy
ddc_002	tank	\N	\N	\N	jqwy_tank_01	dcc_01	tank_to_target	\N	10	f	1.0000	dcc01_jqwy
gyrly_tank_01_cyjq_01	tank	\N	\N	\N	gyrly_tank_01	cyjq_01	tank_to_target	\N	10	t	1.0000	cyjq_01_main_zlcy
gyrly_tank_01_cyjq_02	tank	\N	\N	\N	gyrly_tank_01	cyjq_02	tank_to_target	\N	1	t	1.0000	cyjq_02_zlcy
hc_tank_01_lyjq_01	tank	\N	\N	\N	hc_tank_01	lyjq_01	tank_to_target	\N	10	t	1.0000	lyjq_01_main_zlyy
lt_001	tank	\N	\N	\N	rdyl_tank_01	lt_01	tank_to_target	\N	10	f	1.0000	lt01_jyzy
lt_002	device	lt_01	lt01_qty	\N	\N	dcc_01	direct	\N	10	f	1.0000	dcc01_qty
mf_002	device	cjy_01	cjy_01_zlsny	\N	cjydbzh_tank_01	\N	final	\N	10	t	1.0000	\N
mf_003	device	cjy_01	cjy_01_250rly	\N	rly250_tank_01	\N	final	\N	1	t	1.0000	\N
mf_005	device	cyjq_01	cyjq_01_hangmei	\N	hm_tank_01	\N	final	\N	10	t	1.0000	\N
mf_007	device	cyjq_01	cyjq_01_rlydmx	\N	cycy_tank_01	\N	final	\N	10	t	1.0000	\N
mf_008	device	cyjq_01	cyjq_01_hlgq	\N	\N	lyjq_01	direct	\N	1	t	1.0000	lyjq_01_aux_hlgq
mf_009	device	cyjq_01	cyjq_01_hldfq	\N	\N	lyjq_01	direct	\N	1	t	1.0000	lyjq_01_aux_hldfq
mf_010	device	cyjq_01	cyjq_01_hlyhq	\N	\N	lyjq_01	direct	\N	1	t	1.0000	lyjq_01_aux_hlyhq
mf_011	device	cyjq_01	cyjq_01_qsny	\N	\N	lyjq_01	direct	\N	1	t	1.0000	lyjq_01_aux_qsny
mf_013	device	lyjq_01	lyjq_01_rlydmb	\N	rlydmb_tank_01	\N	final	\N	10	t	1.0000	\N
mf_014	device	lyjq_01	lyjq_01_hangmei	\N	hm_tank_01	\N	final	\N	10	t	1.0000	\N
mf_015	device	lyjq_01	lyjq_01_rlydmx	\N	cycy_tank_01	\N	final	\N	10	t	1.0000	\N
mf_016	device	lyjq_01	lyjq_01_jqwy	\N	jqwy_tank_01	\N	source_to_tank	\N	10	t	1.0000	\N
mf_017	device	lyjq_01	lyjq_01_c5	\N	c5_tank_01	\N	final	\N	1	t	1.0000	\N
mf_018	device	lyjq_01	lyjq_01_yhq	\N	bhlpg_tank_01	\N	final	\N	1	t	1.0000	\N
mf_026	device	cyjq_02	cyjq_02_hm	\N	hm_tank_01	\N	final	\N	0	f	1.0000	\N
mf_027	device	cyjq_02	cyjq_02_hlgq	\N	\N	lyjq_01	direct	\N	0	f	1.0000	lyjq_01_aux_hlgq
mf_118	device	fgh_01	fgh_01_c4ff	\N	\N	cz_01	direct	\N	0	f	1.0000	cz_01_p4
mf_122	device	ct_01	ct_01_c6c8fx	\N	c6c8fx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_42	device	\N	\N	\N	hc_tank_01	dcc_01	tank_to_target	\N	0	f	0.0000	dcc01_zlly
mf_58	device	\N	\N	\N	ben_tank_01	yb_01	tank_to_target	\N	10	f	1.0000	yb_01_ben
mf_030	device	cyjq_02	cyjq_02_qsny	\N	\N	lyjq_01	direct	\N	0	f	1.0000	lyjq_01_aux_qsny
mf_123	device	ct_01	ct_01_wq	\N	\N	cz_01	direct	\N	0	f	1.0000	cz_01_p6
mf_129	device	snyjy_01	snyjy_01_c9fxlf	\N	gyc9fx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_137	device	ct_01	ct_01_c10ctfx	\N	c10cfx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_141	device	ft_01	ft_01_hexc_out	\N	hxxpx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_144	device	qh_01	qh_01_c9	\N	gyc9fx_tank_01	\N	source_to_tank	\N	0	f	1.0000	\N
mf_148	device	cz_01	cz_01_p8	\N	\N	yjq_01	direct	\N	0	f	1.0000	yjq_01_h2
mf_153	device	\N	\N	\N	rlyfd1_tank_01	cyjq_02	tank_to_target	\N	0	f	1.0000	cyjq_02_dcccy
mf_157	device	cz_01	cz_01_p8	\N	\N	ft_01	direct	\N	0	f	1.0000	ft_01_zzhq
mf_43	device	\N	\N	\N	h2_guan_01	cyjq_02	tank_to_target	\N	0	f	0.0000	cyjq_02_h2
\.


--
-- PostgreSQL database dump complete
--

\unrestrict HaNbXNgi9XerI8cVEMsCMGEHPL4hmApZFLngJQrMDD5UToinqJAq4aAuW9v6tgs


-- solve_db.side_lines
--
-- PostgreSQL database dump
--

\restrict BMNjUrXyHtfvTmYWp45GcFRtaIzHX4k0Ve7Y5zkQz21ovJyAlZ5Vr4X4gDPj0XZ

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: side_lines; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.side_lines (side_line_id, name, source_device_id, material_type, is_final, material_id, note) FROM stdin;
byx_01_yb	乙苯	byx_01	main_feed	f	98	\N
byx_01_bjb	苯/甲苯	byx_01	product	f	97	\N
byx_01_byx	苯乙烯	byx_01	product	t	99	\N
byx_01_byxjy	苯乙烯焦油	byx_01	product	f	100	\N
byx_01_tqwq	脱氢尾气	byx_01	product	f	60	\N
cjy_01_250rly	减五线	cjy_01	product	f	17	\N
cjy_01_chang1	常一线	cjy_01	product	f	7	\N
cjy_01_chang2	常二线	cjy_01	product	f	7	\N
cjy_01_chang3	常三线	cjy_01	product	f	7	\N
cjy_01_jian1	减一线	cjy_01	product	f	7	\N
cjy_01_jian2	减二线	cjy_01	product	f	8	\N
cjy_01_jian3	减三线	cjy_01	product	f	8	\N
cjy_01_jian4	减四线	cjy_01	product	f	8	\N
cjy_01_jyzy	减压渣油	cjy_01	product	f	18	\N
cjy_01_zlsny	直馏石脑油	cjy_01	product	f	6	\N
ct_01_bdb	拔顶苯	ct_01	auxiliary	f	102	\N
ct_01_ben	苯	ct_01	product	t	84	\N
ct_01_c10ctfx	工业用C10粗芳烃	ct_01	product	f	91	\N
ct_01_c6c8fx	C6-C8芳烃	ct_01	product	f	89	\N
ct_01_c8fx	C8+芳烃	ct_01	main_feed	f	94	\N
ct_01_c9fx	C9芳烃	ct_01	product	f	90	\N
ct_01_cb	粗苯	ct_01	auxiliary	f	95	\N
ct_01_cf	抽芳	ct_01	product	f	87	\N
ct_01_ctc9lf	抽提C9芳烃馏份	ct_01	product	f	90	\N
ct_01_cyy	抽余油	ct_01	product	f	88	\N
ct_01_h2	氢气	ct_01	auxiliary	f	19	\N
ct_01_hexc	混二甲苯	ct_01	product	f	92	\N
ct_01_hf	混芳	ct_01	product	f	86	\N
ct_01_jb	甲苯	ct_01	product	f	85	\N
ct_01_rq	燃料气	ct_01	product	f	42	\N
ct_01_ss	损失	ct_01	product	f	109	\N
ct_01_wq	尾氢	ct_01	product	f	50	\N
ct_01_zzscy	重整生成油	ct_01	main_feed	f	80	\N
ct_02_ben	苯	ct_02	product	t	84	\N
ct_02_bjb	苯/甲苯	ct_02	auxiliary	f	97	\N
ct_02_c6c8	C6-C8	ct_02	main_feed	f	89	\N
ct_02_cf	抽芳	ct_02	product	f	87	\N
ct_02_cyy	抽余油	ct_02	product	f	88	\N
ct_02_hexc	混二甲苯	ct_02	product	f	92	\N
ct_02_jb	甲苯	ct_02	product	f	85	\N
ct_02_ss	损失	ct_02	product	f	109	\N
ct_02_wy	污油	ct_02	product	f	46	\N
cyjq_01_aux_h2	氢气	cyjq_01	auxiliary	f	19	\N
cyjq_01_hangmei	航煤	cyjq_01	product	t	25	\N
cyjq_01_hldfq	含硫低分气	cyjq_01	product	f	21	\N
cyjq_01_hlgq	含硫干气	cyjq_01	product	f	22	\N
cyjq_01_hlyhq	含硫液化气	cyjq_01	product	f	23	\N
cyjq_01_main_rlybcp	燃料油半成品	cyjq_01	main_feed	f	20	\N
cyjq_01_main_zlcy	直馏柴油	cyjq_01	main_feed	f	7	\N
cyjq_01_qsny	轻石脑油	cyjq_01	product	f	26	\N
cyjq_01_rlydmx	燃料油DMX	cyjq_01	product	t	24	\N
cyjq_01_zsny	重石脑油HC	cyjq_01	product	t	27	\N
cyjq_02_cy	柴油	cyjq_02	product	f	24	\N
cyjq_02_cyhq	粗液化气	cyjq_02	product	f	23	\N
cyjq_02_dcccy	DCC柴油	cyjq_02	main_feed	f	28	\N
cyjq_02_h2	氢气	cyjq_02	auxiliary	f	19	\N
cyjq_02_hldfq	含硫低分气	cyjq_02	product	f	21	\N
cyjq_02_hlgq	含硫干气	cyjq_02	product	f	22	\N
cyjq_02_hm	航煤	cyjq_02	product	f	25	\N
cyjq_02_jzcy	精制柴油	cyjq_02	main_feed	f	24	\N
cyjq_02_ljcy	蜡加柴油	cyjq_02	main_feed	f	36	\N
cyjq_02_qsny	轻石脑油	cyjq_02	product	f	26	\N
cyjq_02_qzsny	轻质燃料油	cyjq_02	product	f	20	\N
cyjq_02_sny	石脑油	cyjq_02	auxiliary	f	6	\N
cyjq_02_zlcy	直馏柴油	cyjq_02	main_feed	f	7	\N
cyjq_02_zsny	重石脑油	cyjq_02	product	f	27	\N
cz_01_p1	HC石脑油	cz_01	main_feed	f	32	\N
cz_01_p10	重整碳五	cz_01	product	f	37	\N
cz_01_p2	拔头油	cz_01	main_feed	f	81	\N
cz_01_p3	精制石脑油	cz_01	main_feed	f	79	\N
cz_01_p4	碳四分馏	cz_01	auxiliary	f	76	\N
cz_01_p5	重整饱和LPG	cz_01	product	f	72	\N
cz_01_p6	尾氢	cz_01	auxiliary	f	50	\N
cz_01_p7	重整干气	cz_01	product	f	58	\N
cz_01_p8	氢气	cz_01	product	f	19	\N
cz_01_p9	重整生成油	cz_01	product	f	80	\N
dcc01_byxjy	苯乙烯焦油	dcc_01	auxiliary	f	100	\N
dcc01_c5xt	碳五烯烃	dcc_01	product	f	71	\N
dcc01_fbxgq	富丙烯干气	dcc_01	auxiliary	f	54	\N
dcc01_gq	干气	dcc_01	product	f	48	\N
dcc01_hhc4	混合C4馏份	dcc_01	auxiliary	f	66	\N
dcc01_jbxwq	聚丙烯尾气	dcc_01	auxiliary	f	57	\N
dcc01_jqwy	加氢尾油	dcc_01	main_feed	f	33	\N
dcc01_jyzy	减渣	dcc_01	main_feed	f	18	\N
dcc01_ljsny	裂解石脑油	dcc_01	product	f	77	\N
dcc01_qty	轻脱油	dcc_01	main_feed	f	83	\N
dcc01_rlybcp	燃料油半成品	dcc_01	product	f	20	\N
dcc01_sj	烧焦	dcc_01	product	f	108	\N
dcc01_yhq	液化气	dcc_01	product	f	35	\N
dcc01_yj	油浆	dcc_01	product	f	43	\N
dcc01_zlly	直馏蜡油	dcc_01	main_feed	f	8	\N
dxe_01_1dc	1-丁烯	dxe_01	product	t	70	\N
dxe_01_bqgq	不凝气	dxe_01	product	f	49	\N
dxe_01_h2	氢气	dxe_01	auxiliary	f	19	\N
dxe_01_jc	甲醇	dxe_01	auxiliary	f	106	\N
dxe_01_mtbe	MTBE	dxe_01	product	f	107	\N
dxe_01_ss	损失	dxe_01	product	f	109	\N
dxe_01_ybwan	异丁烷	dxe_01	product	f	68	\N
dxe_01_yhsyg	液化石油气（工业用）	dxe_01	main_feed	f	35	\N
dxe_01_zc4	重碳四	dxe_01	product	f	69	\N
fgh_01_ben	苯	fgh_01	product	f	84	\N
fgh_01_bwan	丙烷	fgh_01	product	f	74	\N
fgh_01_c4ff	碳四分馏	fgh_01	product	f	76	\N
fgh_01_c5	碳五	fgh_01	main_feed	f	37	\N
fgh_01_c5xt	碳五烯烃	fgh_01	main_feed	f	71	\N
fgh_01_c5xt_out	碳五烯烃	fgh_01	product	f	71	\N
fgh_01_c8	C8+	fgh_01	product	f	94	\N
fgh_01_cb	粗苯	fgh_01	product	f	95	\N
fgh_01_cyy	抽余油	fgh_01	main_feed	f	88	\N
fgh_01_fqgq	富氢干气	fgh_01	product	f	51	\N
fgh_01_jb	甲苯	fgh_01	product	f	85	\N
fgh_01_jsq	解析气	fgh_01	product	f	52	\N
fgh_01_mhts	醚后碳四	fgh_01	main_feed	f	67	\N
fgh_01_qsny	轻石脑油	fgh_01	main_feed	f	26	\N
fgh_01_ss	损失	fgh_01	product	f	109	\N
fgh_01_tlgq	脱硫干气	fgh_01	auxiliary	f	39	\N
fgh_01_twgq	脱戊烷干气	fgh_01	auxiliary	f	112	\N
fgh_01_yhq	液化气	fgh_01	main_feed	f	35	\N
fgh_01_zc4	重碳四	fgh_01	main_feed	f	69	\N
ft_01_c8	C8+	ft_01	main_feed	f	94	\N
ft_01_c9fx	C9芳烃	ft_01	main_feed	f	90	\N
ft_01_c9fx_out	C9芳烃	ft_01	product	f	90	\N
ft_01_fb	富苯	ft_01	product	f	96	\N
ft_01_fywgq	富乙烷气	ft_01	auxiliary	f	55	\N
ft_01_fywgq_out	富乙烷气	ft_01	product	f	55	\N
ft_01_hexc	混二甲苯	ft_01	main_feed	f	92	\N
ft_01_hexc_out	混二甲苯	ft_01	product	f	92	\N
ft_01_px	PX	ft_01	product	t	93	\N
ft_01_ss	损失	ft_01	product	f	109	\N
ft_01_v402dq	V402顶气	ft_01	product	f	61	\N
ft_01_zfc10	重芳烃(C10)	ft_01	product	f	91	\N
ft_01_zzhq	重整氢	ft_01	auxiliary	f	19	\N
jz_01_fal	富胺液	jz_01	product	f	113	\N
jz_01_gq	干气	jz_01	auxiliary	f	48	\N
jz_01_jhgq	净化干气	jz_01	product	f	62	\N
jz_01_jhqgq	净化气柜气	jz_01	product	f	63	\N
jz_01_jysny	加氢石脑油	jz_01	main_feed	f	78	\N
jz_01_jzyhq	精制液化气	jz_01	product	t	73	\N
jz_01_pal	贫胺液	jz_01	auxiliary	f	114	\N
jz_01_qgq	气柜气	jz_01	auxiliary	f	64	\N
jz_01_sny	石脑油	jz_01	product	f	82	\N
jz_01_ss	损失	jz_01	product	f	109	\N
jz_01_yhq	液化气	jz_01	main_feed	f	35	\N
lt01_	脱油沥青	lt_01	product	f	110	\N
lt01_jyzy	减压渣油	lt_01	main_feed	f	18	\N
lt01_qty	轻脱油	lt_01	product	f	83	\N
lt01_zty	重脱油	lt_01	product	f	111	\N
lyjq_01_aux_c5	加氢C5馏分	lyjq_01	auxiliary	f	30	\N
lyjq_01_aux_h2	新氢	lyjq_01	auxiliary	f	19	\N
lyjq_01_aux_hldfq	含硫低分气	lyjq_01	auxiliary	f	21	\N
lyjq_01_aux_hlgq	含硫干气	lyjq_01	auxiliary	f	22	\N
lyjq_01_aux_hlyhq	含硫液化气	lyjq_01	auxiliary	f	23	\N
lyjq_01_aux_qsny	轻石脑油	lyjq_01	auxiliary	f	26	\N
lyjq_01_aux_tdq	塔顶气	lyjq_01	auxiliary	f	31	\N
lyjq_01_c5	碳五	lyjq_01	product	t	37	\N
lyjq_01_gyyw	工业己烷	lyjq_01	product	t	34	\N
lyjq_01_hangmei	航煤	lyjq_01	product	t	25	\N
lyjq_01_hcsny	HC石脑油	lyjq_01	product	t	32	\N
lyjq_01_jqwy	加氢尾油	lyjq_01	product	t	33	\N
lyjq_01_main_dcc	DCC柴油	lyjq_01	main_feed	f	28	\N
lyjq_01_main_dny	低氮油	lyjq_01	main_feed	f	29	\N
lyjq_01_main_zlyy	直馏蜡油	lyjq_01	main_feed	f	8	\N
lyjq_01_rlydmb	燃料油DMB	lyjq_01	product	t	36	\N
lyjq_01_rlydmx	燃料油DMX	lyjq_01	product	t	24	\N
lyjq_01_tldqf	脱硫低分气	lyjq_01	product	t	38	\N
lyjq_01_tlgq	脱硫干气	lyjq_01	product	t	39	\N
lyjq_01_yhq	液化气	lyjq_01	product	t	35	\N
mtbe_01_glzf	高硫组分	mtbe_01	auxiliary	f	65	\N
mtbe_01_hhc4	混合C4	mtbe_01	main_feed	f	66	\N
mtbe_01_jc	甲醇	mtbe_01	auxiliary	f	106	\N
mtbe_01_mhc4b	醚后C4	mtbe_01	product	f	67	\N
mtbe_01_mtbe	MTBE	mtbe_01	product	t	107	\N
mtbe_01_sh	损耗	mtbe_01	product	f	109	\N
mtbe_01_ss	损失	mtbe_01	product	f	109	\N
mtbe_01_yhsyg	液化石油气（工业用）	mtbe_01	product	f	35	\N
qf_01_p1	精制液化气	qf_01	main_feed	f	73	\N
qf_01_p2	混合C4	qf_01	product	f	66	\N
qf_01_p3	丙烯	qf_01	product	f	115	\N
qh_01_bdb	拔顶苯	qh_01	product	f	102	\N
qh_01_ben	苯	qh_01	product	t	84	\N
qh_01_c8	C8+	qh_01	product	f	94	\N
qh_01_c9	C9	qh_01	product	f	90	\N
qh_01_c9fx	C9芳烃	qh_01	main_feed	f	90	\N
qh_01_c9j	C9+	qh_01	main_feed	f	90	\N
qh_01_fb	富苯	qh_01	main_feed	f	96	\N
qh_01_fywgq	富乙烷气	qh_01	product	f	55	\N
qh_01_h2	氢气	qh_01	auxiliary	f	19	\N
qh_01_hf	混芳	qh_01	main_feed	f	86	\N
qh_01_jb	甲苯	qh_01	main_feed	f	85	\N
qh_01_qt	轻烃	qh_01	product	f	75	\N
qh_01_ss	损失	qh_01	product	f	109	\N
qh_01_wq	尾氢	qh_01	product	f	50	\N
snyjy_01_c6c8	C6-C8	snyjy_01	product	f	89	\N
snyjy_01_c9fxlf	C9芳烃馏份	snyjy_01	product	f	90	\N
snyjy_01_cb	粗苯	snyjy_01	auxiliary	f	95	\N
snyjy_01_jc5lf	加氢C5馏分	snyjy_01	product	f	30	\N
snyjy_01_lpny	裂解石脑油	snyjy_01	main_feed	f	77	\N
snyjy_01_ss	损失	snyjy_01	product	f	109	\N
snyjy_01_tdq	塔顶气	snyjy_01	product	f	31	\N
snyjy_01_wy	污油	snyjy_01	product	f	46	\N
snyjy_01_xq	新氢	snyjy_01	auxiliary	f	19	\N
yb_01_bb	丙苯	yb_01	product	f	105	\N
yb_01_ben	苯	yb_01	main_feed	f	84	\N
yb_01_bqgq	不凝气	yb_01	product	f	49	\N
yb_01_dccjsq	DCC解析气	yb_01	product	f	53	\N
yb_01_fbxgq	富丙烯干气	yb_01	product	f	54	\N
yb_01_gfw	高沸物	yb_01	product	f	104	\N
yb_01_hhy	烃化液	yb_01	auxiliary	f	103	\N
yb_01_hhy_out	烃化液	yb_01	product	f	103	\N
yb_01_jhgq	净化干气	yb_01	main_feed	f	62	\N
yb_01_ss	损失	yb_01	product	f	109	\N
yb_01_wjq	烷基化尾气	yb_01	product	f	59	\N
yb_01_wy	污油	yb_01	product	f	46	\N
yb_01_yb	乙苯	yb_01	product	t	98	\N
yjq_01_bty	拔头油	yjq_01	product	f	81	\N
yjq_01_cyy	抽余油	yjq_01	main_feed	f	88	\N
yjq_01_h2	氢气	yjq_01	auxiliary	f	19	\N
yjq_01_hlgq	含硫干气	yjq_01	product	f	22	\N
yjq_01_jqzs	加氢重石	yjq_01	main_feed	f	27	\N
yjq_01_jzsny	精制石脑油	yjq_01	product	f	79	\N
yjq_01_qt	轻烃	yjq_01	auxiliary	f	75	\N
yjq_01_sny	石脑油	yjq_01	main_feed	f	82	\N
\.


--
-- PostgreSQL database dump complete
--

\unrestrict BMNjUrXyHtfvTmYWp45GcFRtaIzHX4k0Ve7Y5zkQz21ovJyAlZ5Vr4X4gDPj0XZ


-- solve_db.tank_monthly_initial
--
-- PostgreSQL database dump
--

\restrict zOIeg8AlLWgI3auQC8lejxGqSzK5L0uabrhRrADF7Ma3FJQbMKCsHOflItlo0l4

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: tank_monthly_initial; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.tank_monthly_initial (tank_id, year_month, initial_capacity) FROM stdin;
cjydbzh_tank_01	2026-07	14033.897
zsny_tank_01	2026-07	27451.035
gyrlyjy_tank_01	2026-07	0.000
hcyl_tank_01	2026-07	0.000
jqwyou_tank_01	2026-07	0.000
yjqyl_tank_01	2026-07	0.000
jyzy_tank_01	2026-07	27357.504
jx_tank_01	2026-07	0.000
zwy_tank_01	2026-07	0.000
zyrly_tank_01	2026-07	0.000
ben_tank_01	2026-07	0.000
bhlpg_tank_01	2026-07	0.000
byxjy_tank_01	2026-07	0.000
c6c8fx_tank_01	2026-07	0.000
cyzy_tank_01	2026-07	0.000
dccyl_tank_01	2026-07	0.000
gyc9fx_tank_01	2026-07	0.000
gyrly_tank_01	2026-07	27451.035
h2_guan_01	2026-07	0.000
hc_tank_01	2026-07	28851.428
hxxpx_tank_01	2026-07	0.000
jqwy_tank_01	2026-07	13908.037
jzsny_tank_01	2026-07	0.000
ldlh_tank_01	2026-07	0.000
ljsny_tank_01	2026-07	0.000
qfyhq_tank_01	2026-07	0.000
qwy_tank_01	2026-07	0.000
rdyl_tank_01	2026-07	0.000
snyhgl_tank_01	2026-07	0.000
snyqyl_tank_01	2026-07	0.000
snyyxl_tank_01	2026-07	0.000
thy_tank_01	2026-07	0.000
tqy_tank_01	2026-07	0.000
wy_tank_01	2026-07	0.000
yb_tank_01	2026-07	0.000
yhsyqhc4_tank_01	2026-07	0.000
zzscy_tank_01	2026-07	0.000
\.


--
-- PostgreSQL database dump complete
--

\unrestrict zOIeg8AlLWgI3auQC8lejxGqSzK5L0uabrhRrADF7Ma3FJQbMKCsHOflItlo0l4


-- solve_db.production_plans_input
--
-- PostgreSQL database dump
--

\restrict 5gsMf6N5c3lLLBFFah52bI4r7fhNPi2Rpz8bf5funoNvWqRh7M0DKhnO3hBLV3D

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: production_plans_input; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.production_plans_input (planned_month, crude_type_id, crude_type_name, arrival_plan, monthly_processing_capacity, current_stock, max_level_stock, min_level_stock, cost) FROM stdin;
2026-04	qinhuangdao	秦皇岛	{"2026-04-02": 27000, "2026-04-13": 27000}	50000.0	30000.0	95000.0	5000.0	2000.0
2026-04	bozhong_25_1	渤中25-1	{"2026-04-09": 56000, "2026-04-14": 56000, "2026-04-23": 56000}	130000.0	50000.0	95000.0	5000.0	1000.0
2026-04	caofeidian	曹妃甸	{"2026-04-17": 56000, "2026-04-27": 55000}	112000.0	30000.0	95000.0	5000.0	1000.0
\.


--
-- PostgreSQL database dump complete
--

\unrestrict 5gsMf6N5c3lLLBFFah52bI4r7fhNPi2Rpz8bf5funoNvWqRh7M0DKhnO3hBLV3D


-- public.crude_types
--
-- PostgreSQL database dump
--

\restrict v7lKzXFMXkZ6EmWx8WqRu34ca0HMh65VhCSjU5up17DL5Xs8BLNye2HYi0uOXMk

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: crude_types; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.crude_types (crude_type_id, crude_name, crude_code, aliases, is_active, is_default, sort_order, note) FROM stdin;
default	默认/通用	DEF	{}	t	t	0	\N
atapu	阿塔普	ATP	{Atapu}	t	f	1	\N
bozhong_25_1	渤中25-1	BZ5	{渤中25-1混}	t	f	2	\N
caofeidian	曹妃甸	CFD	{}	t	f	3	\N
panyu	番禺	PAY	{}	t	f	4	\N
jinzhou_9_3	锦州9-3	JZ9	{锦州9-3原油}	t	f	5	\N
liuhua	流花	LIH	{}	t	f	6	\N
luda_10_1	旅大10-1	LD0	{旅大}	t	f	7	\N
nanpu	南堡	NAB	{南堡35-2}	t	f	8	\N
qinhuangdao	秦皇岛	QHD	{秦皇岛32-6,秦皇岛32-6CN}	t	f	9	\N
xinxi_jiang	新西江	XJN	{}	t	f	10	\N
\.


--
-- PostgreSQL database dump complete
--

\unrestrict v7lKzXFMXkZ6EmWx8WqRu34ca0HMh65VhCSjU5up17DL5Xs8BLNye2HYi0uOXMk


-- solve_db.production_plan_details
--
-- PostgreSQL database dump
--

\restrict jPzOdht6jVseZgzA9ENEFR8Fns33AtpxbIThwah6I0tJLPY8S06781EfCah7P93

-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

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
-- Data for Name: production_plan_details; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.production_plan_details (id, plan_id, plan_date, day_of_month, daily_input, blend_detail, crude_stock_status, device_load_rate, hours) FROM stdin;
DETAIL-PLAN-202601-1-0	PLAN-202601	2026-01-01	1	17040.0	{"TUPI": 0.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 16920.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 0.0}	{"TUPI": 8076.566000000001, "HZ/QHD": 10229.173, "污油": 32655.11, "caofeidian": 49246.015, "bozhong_25_1": 148.91500000000087, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 62417.013000000006}	96.5845	24.0
DETAIL-PLAN-202601-2-0	PLAN-202601	2026-01-02	2	17040.0	{"TUPI": 0.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 14520.0, "jinzhou_9_3": 0.0, "qinhuangdao": 2400.0, "bozhong_25_1": 0.0}	{"TUPI": 8076.566000000001, "HZ/QHD": 10109.173, "污油": 32655.11, "caofeidian": 32326.015, "bozhong_25_1": 148.91500000000087, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 62417.013000000006}	96.5845	24.0
DETAIL-PLAN-202601-3-0	PLAN-202601	2026-01-03	3	17040.0	{"TUPI": 0.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 12120.0, "jinzhou_9_3": 0.0, "qinhuangdao": 4800.0, "bozhong_25_1": 0.0}	{"TUPI": 8076.566000000001, "HZ/QHD": 9989.173, "污油": 32655.11, "caofeidian": 17806.015, "bozhong_25_1": 56148.915, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 60017.013000000006}	96.5845	24.0
DETAIL-PLAN-202601-4-0	PLAN-202601	2026-01-04	4	16560.0	{"TUPI": 1200.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 15240.0, "bozhong_25_1": 0.0}	{"TUPI": 8076.566000000001, "HZ/QHD": 9869.173, "污油": 32655.11, "caofeidian": 49686.015, "bozhong_25_1": 64939.915, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 55217.013000000006}	93.8638	24.0
DETAIL-PLAN-202601-5-0	PLAN-202601	2026-01-05	5	5360.0	{"TUPI": 400.0, "HZ/QHD": 40.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0, "bozhong_25_1": 4920.0}	{"TUPI": 6876.566000000001, "HZ/QHD": 9749.173, "污油": 32655.11, "caofeidian": 49686.015, "bozhong_25_1": 64939.915, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 39977.013000000006}	92.5574	8.0
DETAIL-PLAN-202601-5-1	PLAN-202601	2026-01-05	5	11040.0	{"TUPI": 800.0, "HZ/QHD": 80.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 10160.0, "bozhong_25_1": 0}	{"TUPI": 6876.566000000001, "HZ/QHD": 9749.173, "污油": 32655.11, "caofeidian": 49686.015, "bozhong_25_1": 64939.915, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 39977.013000000006}	92.5574	16.0
DETAIL-PLAN-202601-6-0	PLAN-202601	2026-01-06	6	16080.0	{"TUPI": 1200.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 14760.0}	{"TUPI": 5676.566000000001, "HZ/QHD": 9629.173, "污油": 32655.11, "caofeidian": 49686.015, "bozhong_25_1": 60019.915, "jinzhou_25_1": 12673.428, "qinhuangdao/nanpu_35_2": 29817.013000000006}	92.5574	24.0
DETAIL-PLAN-202601-7-0	PLAN-202601	2026-01-07	7	16080.0	{"TUPI": 0, "污油": 0, "qinhuangdao": 0, "bozhong_25_1": 16080.0}	{"TUPI": 8712.0, "污油": 53898.0, "bozhong_25_1": 46911.0, "qinhuangdao/nanpu_35_2": 93377.0}	92.5574	24.0
DETAIL-PLAN-202601-8-0	PLAN-202601	2026-01-08	8	16080.0	{"TUPI": 0, "污油": 0, "qinhuangdao": 0, "bozhong_25_1": 16080.0}	{"TUPI": 8712.0, "污油": 53898.0, "bozhong_25_1": 30831.0, "qinhuangdao/nanpu_35_2": 93377.0}	92.5574	24.0
DETAIL-PLAN-202601-9-0	PLAN-202601	2026-01-09	9	15120.0	{"TUPI": 1050.0, "污油": 0, "qinhuangdao": 0, "bozhong_25_1": 14070.0}	{"TUPI": 8712.0, "污油": 53898.0, "bozhong_25_1": 14751.0, "qinhuangdao/nanpu_35_2": 93377.0}	95.1032	21.0
DETAIL-PLAN-202601-9-1	PLAN-202601	2026-01-09	9	2100.0	{"TUPI": 150.0, "污油": 0, "qinhuangdao": 1950.0, "bozhong_25_1": 0}	{"TUPI": 8712.0, "污油": 53898.0, "bozhong_25_1": 14751.0, "qinhuangdao/nanpu_35_2": 93377.0}	95.1032	3.0
DETAIL-PLAN-202601-10-0	PLAN-202601	2026-01-10	10	16800.0	{"TUPI": 1200.0, "污油": 0, "qinhuangdao": 15600.0, "bozhong_25_1": 0}	{"TUPI": 7512.0, "污油": 53898.0, "bozhong_25_1": 681.0, "qinhuangdao/nanpu_35_2": 91427.0}	95.1032	24.0
DETAIL-PLAN-202601-11-0	PLAN-202601	2026-01-11	11	16800.0	{"TUPI": 1200.0, "污油": 0, "qinhuangdao": 15600.0, "bozhong_25_1": 0}	{"TUPI": 6312.0, "污油": 53898.0, "bozhong_25_1": 681.0, "qinhuangdao/nanpu_35_2": 75827.0}	95.1032	24.0
DETAIL-PLAN-202601-12-0	PLAN-202601	2026-01-12	12	16800.0	{"TUPI": 1200.0, "污油": 0, "qinhuangdao": 15600.0, "bozhong_25_1": 0}	{"TUPI": 5112.0, "污油": 53898.0, "bozhong_25_1": 56681.0, "qinhuangdao/nanpu_35_2": 118227.0}	95.1032	24.0
DETAIL-PLAN-202601-13-0	PLAN-202601	2026-01-13	13	16800.0	{"TUPI": 1200.0, "污油": 0, "qinhuangdao": 15600.0, "bozhong_25_1": 0}	{"TUPI": 3912.0, "污油": 53898.0, "bozhong_25_1": 56681.0, "qinhuangdao/nanpu_35_2": 102627.0}	95.1032	24.0
DETAIL-PLAN-202601-14-0	PLAN-202601	2026-01-14	14	16740.0	{"TUPI": 1200.0, "HZ/QHD": 60.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 15480.0, "bozhong_25_1": 0.0}	{"TUPI": 7890.299000000001, "HZ/QHD": 8540.288, "污油": 32660.15, "bozhong_25_1": 36869.058, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 84771.093}	95.1032	24.0
DETAIL-PLAN-202601-15-0	PLAN-202601	2026-01-15	15	8040.0	{"TUPI": 600.0, "HZ/QHD": 60.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0, "bozhong_25_1": 7380.0}	{"TUPI": 6690.299000000001, "HZ/QHD": 8480.288, "污油": 32660.15, "bozhong_25_1": 55973.058, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 69291.093}	91.1431	12.0
DETAIL-PLAN-202601-15-1	PLAN-202601	2026-01-15	15	8340.0	{"TUPI": 600.0, "HZ/QHD": 60.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 7680.0, "bozhong_25_1": 0}	{"TUPI": 6690.299000000001, "HZ/QHD": 8480.288, "污油": 32660.15, "bozhong_25_1": 55973.058, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 69291.093}	91.1431	12.0
DETAIL-PLAN-202601-16-0	PLAN-202601	2026-01-16	16	16080.0	{"TUPI": 480.0, "HZ/QHD": 120.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 15480.0}	{"TUPI": 5490.299000000001, "HZ/QHD": 8360.288, "污油": 32660.15, "bozhong_25_1": 48593.058, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 61611.09299999999}	91.1431	24.0
DETAIL-PLAN-202601-17-0	PLAN-202601	2026-01-17	17	16080.0	{"TUPI": 480.0, "HZ/QHD": 120.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 15480.0}	{"TUPI": 5010.299000000001, "HZ/QHD": 8240.288, "污油": 32660.15, "bozhong_25_1": 33113.058, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 61611.09299999999}	91.1431	24.0
DETAIL-PLAN-202601-18-0	PLAN-202601	2026-01-18	18	16080.0	{"TUPI": 480.0, "HZ/QHD": 120.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 15480.0}	{"TUPI": 4530.299000000001, "HZ/QHD": 8120.2880000000005, "污油": 32660.15, "bozhong_25_1": 17633.057999999997, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 117611.093}	91.1431	24.0
DETAIL-PLAN-202601-19-0	PLAN-202601	2026-01-19	19	2010.0	{"TUPI": 60.0, "HZ/QHD": 15.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0, "bozhong_25_1": 1935.0}	{"TUPI": 4050.299000000001, "HZ/QHD": 8000.2880000000005, "污油": 32660.15, "bozhong_25_1": 2153.0579999999973, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 117611.093}	92.0755	3.0
DETAIL-PLAN-202601-19-1	PLAN-202601	2026-01-19	19	14595.0	{"TUPI": 420.0, "HZ/QHD": 105.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 14070.0, "bozhong_25_1": 0}	{"TUPI": 4050.299000000001, "HZ/QHD": 8000.2880000000005, "污油": 32660.15, "bozhong_25_1": 2153.0579999999973, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 117611.093}	92.0755	21.0
DETAIL-PLAN-202601-20-0	PLAN-202601	2026-01-20	20	16680.0	{"TUPI": 1200.0, "HZ/QHD": 120.0, "污油": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 15360.0, "bozhong_25_1": 0}	{"TUPI": 3570.299000000001, "HZ/QHD": 7880.2880000000005, "污油": 32660.15, "bozhong_25_1": 218.05799999999726, "jinzhou_25_1": 12750.611, "qinhuangdao/nanpu_35_2": 103541.093}	92.0755	24.0
DETAIL-PLAN-202601-21-0	PLAN-202601	2026-01-21	21	16000.0	{"TUPI": 160.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 15720.0, "bozhong_25_1": 0}	{"TUPI": 2961.0, "HZ/QHD": 7292.0, "污油": 32670.0, "bozhong_25_1": 527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 94182.0}	92.0755	24.0
DETAIL-PLAN-202601-22-0	PLAN-202601	2026-01-22	22	16320.0	{"TUPI": 0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 16200.0, "bozhong_25_1": 0}	{"TUPI": 35801.0, "HZ/QHD": 7172.0, "污油": 32670.0, "bozhong_25_1": 527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 78462.0}	92.0755	24.0
DETAIL-PLAN-202601-23-0	PLAN-202601	2026-01-23	23	16320.0	{"JZ": 2400.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 13800.0, "bozhong_25_1": 0}	{"JZ": 35801.0, "HZ/QHD": 7052.0, "污油": 32670.0, "bozhong_25_1": 527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 62262.0}	92.0755	24.0
DETAIL-PLAN-202601-24-0	PLAN-202601	2026-01-24	24	16320.0	{"JZ": 4800.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 11400.0, "bozhong_25_1": 0}	{"JZ": 33401.0, "HZ/QHD": 6932.0, "污油": 32670.0, "bozhong_25_1": 527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 48462.0}	92.0755	24.0
DETAIL-PLAN-202601-25-0	PLAN-202601	2026-01-25	25	16320.0	{"JZ": 6000.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 10200.0, "bozhong_25_1": 0}	{"JZ": 28601.0, "HZ/QHD": 6812.0, "污油": 32670.0, "bozhong_25_1": 527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 37062.0}	92.0755	24.0
DETAIL-PLAN-202601-26-0	PLAN-202601	2026-01-26	26	16320.0	{"JZ": 6000.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 10200.0, "bozhong_25_1": 0}	{"JZ": 22601.0, "HZ/QHD": 6692.0, "污油": 32670.0, "bozhong_25_1": 56527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 84862.0}	92.0755	24.0
DETAIL-PLAN-202601-27-0	PLAN-202601	2026-01-27	27	5440.0	{"JZ": 2000.0, "HZ/QHD": 40.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 0, "bozhong_25_1": 3400.0}	{"JZ": 16601.0, "HZ/QHD": 6572.0, "污油": 32670.0, "bozhong_25_1": 56527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 74662.0}	91.3106	8.0
DETAIL-PLAN-202601-27-1	PLAN-202601	2026-01-27	27	9880.0	{"JZ": 3000.0, "HZ/QHD": 80.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 6800.0, "bozhong_25_1": 0}	{"JZ": 16601.0, "HZ/QHD": 6572.0, "污油": 32670.0, "bozhong_25_1": 56527.0, "jinzhou_25_1": 12778.0, "qinhuangdao/nanpu_35_2": 74662.0}	91.3106	16.0
DETAIL-PLAN-202601-28-0	PLAN-202601	2026-01-28	28	16080.0	{"JZ": 1920.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 0, "bozhong_25_1": 14040.0}	{"JZ": 32758.0, "HZ/QHD": 5709.0, "污油": 32660.0, "bozhong_25_1": 33428.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 69149.0}	91.3106	24.0
DETAIL-PLAN-202601-29-0	PLAN-202601	2026-01-29	29	16080.0	{"JZ": 1920.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 0, "bozhong_25_1": 14040.0}	{"JZ": 30838.0, "HZ/QHD": 5589.0, "污油": 32660.0, "bozhong_25_1": 19388.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 125149.0}	91.3106	24.0
DETAIL-PLAN-202601-30-0	PLAN-202601	2026-01-30	30	6030.0	{"JZ": 720.0, "HZ/QHD": 45.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 0, "bozhong_25_1": 5265.0}	{"JZ": 28918.0, "HZ/QHD": 5469.0, "污油": 32660.0, "bozhong_25_1": 5348.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 125149.0}	93.8638	9.0
DETAIL-PLAN-202601-30-1	PLAN-202601	2026-01-30	30	10350.0	{"JZ": 1200.0, "HZ/QHD": 75.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 9075.0, "bozhong_25_1": 0}	{"JZ": 28918.0, "HZ/QHD": 5469.0, "污油": 32660.0, "bozhong_25_1": 5348.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 125149.0}	93.8638	15.0
DETAIL-PLAN-202601-31-0	PLAN-202601	2026-01-31	31	16560.0	{"JZ": 4800.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 11640.0, "bozhong_25_1": 0}	{"JZ": 26998.0, "HZ/QHD": 5349.0, "污油": 32660.0, "bozhong_25_1": 83.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 116074.0}	93.8638	24.0
DETAIL-PLAN-202602-1-0	PLAN-202602	2026-02-01	1	16560.0	{"JZ": 4800.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 11640.0, "bozhong_25_1": 0}	{"JZ": 22198.0, "HZ/QHD": 5229.0, "污油": 32660.0, "bozhong_25_1": 83.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 104434.0}	89.0626	24.0
DETAIL-PLAN-202602-2-0	PLAN-202602	2026-02-02	2	16560.0	{"JZ": 4800.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 11640.0, "bozhong_25_1": 0}	{"JZ": 17398.0, "HZ/QHD": 5109.0, "污油": 32660.0, "bozhong_25_1": 83.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 92794.0}	89.0626	24.0
DETAIL-PLAN-202602-3-0	PLAN-202602	2026-02-03	3	16560.0	{"JZ": 4800.0, "HZ/QHD": 120.0, "污油": 0, "jinzhou_9_3": 0, "qinhuangdao": 11640.0, "bozhong_25_1": 0}	{"JZ": 12598.0, "HZ/QHD": 4989.0, "污油": 32660.0, "bozhong_25_1": 83.0, "jinzhou_25_1": 12839.0, "qinhuangdao/nanpu_35_2": 81154.0}	89.0626	24.0
DETAIL-PLAN-202602-4-0	PLAN-202602	2026-02-04	4	8200.0	{"JZ": 3250.0, "HZ/QHD": 55.0, "污油": 0.0, "caofeidian": 4895.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0, "bozhong_25_1": 0.0}	{"JZ": 4271.16, "HZ/QHD": 3908.094, "污油": 32654.722999999998, "caofeidian": 65150.739, "bozhong_25_1": 1186.5480000000007, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 11142.809000000001}	96.0437	11.0
DETAIL-PLAN-202602-4-1	PLAN-202602	2026-02-04	4	5970.0	{"JZ": 250.0, "HZ/QHD": 65.0, "污油": 0.0, "caofeidian": 0, "jinzhou_9_3": 0.0, "qinhuangdao": 5655.0, "bozhong_25_1": 0.0}	{"JZ": 4271.16, "HZ/QHD": 3908.094, "污油": 32654.722999999998, "caofeidian": 65150.739, "bozhong_25_1": 1186.5480000000007, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 11142.809000000001}	96.0437	13.0
DETAIL-PLAN-202602-5-0	PLAN-202602	2026-02-05	5	16800.0	{"HZ/QHD": 120.0, "JZ25-1": 0.0, "污油": 0.0, "caofeidian": 16680.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 0.0}	{"HZ/QHD": 3788.094, "JZ25-1": 20771.16, "污油": 32654.722999999998, "caofeidian": 60255.739, "bozhong_25_1": 1186.5480000000007, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 61487.809}	96.0437	24.0
DETAIL-PLAN-202602-6-0	PLAN-202602	2026-02-06	6	16800.0	{"ATAPU": 2400.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 14280.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 0.0}	{"ATAPU": 20771.16, "HZ/QHD": 3668.094, "污油": 32654.722999999998, "caofeidian": 43575.739, "bozhong_25_1": 57186.548, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 61487.809}	96.0437	24.0
DETAIL-PLAN-202602-7-0	PLAN-202602	2026-02-07	7	16800.0	{"ATAPU": 2400.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 14280.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 0.0}	{"ATAPU": 18371.16, "HZ/QHD": 3548.094, "污油": 32654.722999999998, "caofeidian": 29295.739, "bozhong_25_1": 57186.548, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 61487.809}	96.0437	24.0
DETAIL-PLAN-202602-8-0	PLAN-202602	2026-02-08	8	16080.0	{"ATAPU": 2400.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 13560.0}	{"ATAPU": 15971.16, "HZ/QHD": 3428.094, "污油": 32654.722999999998, "caofeidian": 14015.739000000001, "bozhong_25_1": 57186.548, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 62487.809}	90.0913	24.0
DETAIL-PLAN-202602-9-0	PLAN-202602	2026-02-09	9	16080.0	{"ATAPU": 2400.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 13560.0}	{"ATAPU": 13571.16, "HZ/QHD": 3308.094, "污油": 32654.722999999998, "caofeidian": 14015.739000000001, "bozhong_25_1": 43626.548, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 62487.809}	90.0913	24.0
DETAIL-PLAN-202602-10-0	PLAN-202602	2026-02-10	10	16080.0	{"ATAPU": 2400.0, "HZ/QHD": 120.0, "污油": 0.0, "caofeidian": 0.0, "jinzhou_9_3": 0.0, "qinhuangdao": 0.0, "bozhong_25_1": 13560.0}	{"ATAPU": 11171.16, "HZ/QHD": 3188.094, "污油": 32654.722999999998, "caofeidian": 14015.739000000001, "bozhong_25_1": 30066.548000000003, "jinzhou_25_1": 12833.794000000002, "qinhuangdao/nanpu_35_2": 62487.809}	90.0913	24.0
DETAIL-PLAN-202602-11-0	PLAN-202602	2026-02-11	11	15360.0	{"ATAPU": 3600.0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 11520.0}	{"ATAPU": 6715.0, "HZ/QHD": 2249.0, "污油": 32662.0, "caofeidian": 77012.0, "bozhong_25_1": 12395.0, "jinzhou_25_1": 12838.0}	90.0913	24.0
DETAIL-PLAN-202602-12-0	PLAN-202602	2026-02-12	12	640.0	{"ATAPU": 150.0, "HZ/QHD": 10.0, "污油": 0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 480.0}	{"ATAPU": 3115.0, "HZ/QHD": 2009.0, "污油": 32662.0, "caofeidian": 77012.0, "bozhong_25_1": 875.0, "jinzhou_25_1": 12838.0}	94.6852	1.0
DETAIL-PLAN-202602-12-1	PLAN-202602	2026-02-12	12	15870.0	{"ATAPU": 3450.0, "HZ/QHD": 230.0, "污油": 0, "caofeidian": 12190.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 3115.0, "HZ/QHD": 2009.0, "污油": 32662.0, "caofeidian": 77012.0, "bozhong_25_1": 875.0, "jinzhou_25_1": 12838.0}	94.6852	23.0
DETAIL-PLAN-202602-13-0	PLAN-202602	2026-02-13	13	16800.0	{"ATAPU": 0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 16560.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": -485.0, "HZ/QHD": 1769.0, "污油": 32662.0, "caofeidian": 120822.0, "bozhong_25_1": 395.0, "jinzhou_25_1": 12838.0}	94.6852	24.0
DETAIL-PLAN-202602-14-0	PLAN-202602	2026-02-14	14	16800.0	{"ATAPU": 0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 16560.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 16715.0, "HZ/QHD": 1529.0, "污油": 32662.0, "caofeidian": 104262.0, "bozhong_25_1": 395.0, "jinzhou_25_1": 12838.0}	94.6852	24.0
DETAIL-PLAN-202602-15-0	PLAN-202602	2026-02-15	15	16800.0	{"ATAPU": 1200.0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 15360.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 16715.0, "HZ/QHD": 1289.0, "污油": 32662.0, "caofeidian": 87702.0, "bozhong_25_1": 395.0, "jinzhou_25_1": 12838.0}	94.6852	24.0
DETAIL-PLAN-202602-16-0	PLAN-202602	2026-02-16	16	16800.0	{"ATAPU": 2400.0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 14160.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 15515.0, "HZ/QHD": 1049.0, "污油": 32662.0, "caofeidian": 72342.0, "bozhong_25_1": 395.0, "jinzhou_25_1": 12838.0}	94.6852	24.0
DETAIL-PLAN-202602-17-0	PLAN-202602	2026-02-17	17	16560.0	{"ATAPU": 2400.0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 13920.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 13115.0, "HZ/QHD": 809.0, "污油": 32662.0, "caofeidian": 108182.0, "bozhong_25_1": 56395.0, "jinzhou_25_1": 12838.0}	94.6852	24.0
DETAIL-PLAN-202602-18-0	PLAN-202602	2026-02-18	18	4960.0	{"ATAPU": 800.0, "HZ/QHD": 80.0, "污油": 0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 4080.0}	{"ATAPU": 10715.0, "HZ/QHD": 569.0, "污油": 32662.0, "caofeidian": 94262.0, "bozhong_25_1": 56395.0, "jinzhou_25_1": 12838.0}	90.5580	8.0
DETAIL-PLAN-202602-18-1	PLAN-202602	2026-02-18	18	11040.0	{"ATAPU": 1600.0, "HZ/QHD": 160.0, "污油": 0, "caofeidian": 9280.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 10715.0, "HZ/QHD": 569.0, "污油": 32662.0, "caofeidian": 94262.0, "bozhong_25_1": 56395.0, "jinzhou_25_1": 12838.0}	90.5580	16.0
DETAIL-PLAN-202602-19-0	PLAN-202602	2026-02-19	19	16080.0	{"ATAPU": 1200.0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 14640.0}	{"ATAPU": 8315.0, "HZ/QHD": 329.0, "污油": 32662.0, "caofeidian": 84982.0, "bozhong_25_1": 52315.0, "jinzhou_25_1": 12838.0}	90.5580	24.0
DETAIL-PLAN-202602-20-0	PLAN-202602	2026-02-20	20	16080.0	{"ATAPU": 1200.0, "HZ/QHD": 240.0, "污油": 0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 14640.0}	{"ATAPU": 7115.0, "HZ/QHD": 89.0, "污油": 32662.0, "caofeidian": 84982.0, "bozhong_25_1": 37675.0, "jinzhou_25_1": 12838.0}	90.5580	24.0
DETAIL-PLAN-202602-21-0	PLAN-202602	2026-02-21	21	16080.0	{"ATAPU": 1200.0, "HZ/QHD": 0, "污油": 240.0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 14640.0}	{"ATAPU": 5915.0, "HZ/QHD": -151.0, "污油": 32662.0, "caofeidian": 84982.0, "bozhong_25_1": 23035.0, "jinzhou_25_1": 12838.0}	90.5580	24.0
DETAIL-PLAN-202602-22-0	PLAN-202602	2026-02-22	22	8710.0	{"ATAPU": 650.0, "HZ/QHD": 0, "污油": 130.0, "caofeidian": 0, "jinzhou_9_3": 0, "bozhong_25_1": 7930.0}	{"ATAPU": 4715.0, "HZ/QHD": -151.0, "污油": 32422.0, "caofeidian": 140982.0, "bozhong_25_1": 8395.0, "jinzhou_25_1": 12838.0}	81.4652	13.0
DETAIL-PLAN-202602-22-1	PLAN-202602	2026-02-22	22	7700.0	{"ATAPU": 550.0, "HZ/QHD": 0, "污油": 110.0, "caofeidian": 7040.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 4715.0, "HZ/QHD": -151.0, "污油": 32422.0, "caofeidian": 140982.0, "bozhong_25_1": 8395.0, "jinzhou_25_1": 12838.0}	81.4652	11.0
DETAIL-PLAN-202602-23-0	PLAN-202602	2026-02-23	23	13260.0	{"ATAPU": 2400.0, "HZ/QHD": 0, "污油": 240.0, "caofeidian": 10620.0, "jinzhou_9_3": 0, "bozhong_25_1": 0}	{"ATAPU": 3515.0, "HZ/QHD": -151.0, "污油": 32182.0, "caofeidian": 133942.0, "bozhong_25_1": 465.0, "jinzhou_25_1": 12838.0}	81.4652	24.0
DETAIL-PLAN-202602-24-0	PLAN-202602	2026-02-24	24	16800.0	{"SEPIA": 0, "污油": 0, "luda_10_1": 11280.0, "caofeidian": 5520.0, "bozhong_25_1": 0}	{"LH/LD": 57692.0, "SEPIA": 6184.0, "污油": 44129.0, "caofeidian": 66585.0, "bozhong_25_1": 529.0}	95.4962	24.0
DETAIL-PLAN-202602-25-0	PLAN-202602	2026-02-25	25	16800.0	{"SEPIA": 1200.0, "污油": 0, "luda_10_1": 10800.0, "caofeidian": 4800.0, "bozhong_25_1": 0}	{"LH/LD": 46412.0, "SEPIA": 7807.0, "污油": 44129.0, "caofeidian": 61065.0, "bozhong_25_1": 529.0}	95.4962	24.0
DETAIL-PLAN-202602-26-0	PLAN-202602	2026-02-26	26	16800.0	{"SEPIA": 2400.0, "污油": 0, "luda_10_1": 10080.0, "caofeidian": 4320.0, "bozhong_25_1": 0}	{"LH/LD": 35612.0, "SEPIA": 6607.0, "污油": 44129.0, "caofeidian": 56265.0, "bozhong_25_1": 529.0}	95.4962	24.0
DETAIL-PLAN-202602-27-0	PLAN-202602	2026-02-27	27	16920.0	{"SEPIA": 2400.0, "污油": 120.0, "luda_10_1": 10080.0, "caofeidian": 4320.0, "bozhong_25_1": 0}	{"LH/LD": 25532.0, "SEPIA": 4207.0, "污油": 44129.0, "caofeidian": 51945.0, "bozhong_25_1": 529.0}	95.4962	24.0
DETAIL-PLAN-202602-28-0	PLAN-202602	2026-02-28	28	16920.0	{"SEPIA": 2400.0, "污油": 120.0, "luda_10_1": 10080.0, "caofeidian": 4320.0, "bozhong_25_1": 0}	{"LH/LD": 15452.0, "SEPIA": 1807.0, "污油": 44009.0, "caofeidian": 47625.0, "bozhong_25_1": 56529.0}	95.4962	24.0
DETAIL-PLAN-202603-6-0	PLAN-202603	2026-03-06	6	16893.444569477319467654400	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 16893.44456947732, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 11463.0, "SEPIA/TUPI": 21243.0, "caofeidian": 93747.0, "bozhong_25_1": 36495.0, "qinhuangdao/nanpu_35_2": 72323.0}	98.1694	24.0
DETAIL-PLAN-202603-21-0	PLAN-202603	2026-03-21	21	16658.813394901245586159200	{"污油": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 16424.182220325172, "bozhong_25_1": 0}	{"污油": 12930.0, "caofeidian": 96139.0, "bozhong_25_1": 1681.0, "qinhuangdao/nanpu_35_2": 63743.0}	96.0293	24.0
DETAIL-PLAN-202603-13-0	PLAN-202603	2026-03-13	13	16658.813394901245586159200	{"污油": 0, "caofeidian": 187.7049396608591, "qinhuangdao": 16471.108455240388, "bozhong_25_1": 0}	{"污油": 11500.0, "caofeidian": 94428.0, "bozhong_25_1": 36152.0, "qinhuangdao/nanpu_35_2": 69206.0}	96.5845	24.0
DETAIL-PLAN-202603-14-0	PLAN-202603	2026-03-14	14	16658.813394901245586159200	{"污油": 0, "caofeidian": 187.7049396608591, "qinhuangdao": 16471.108455240388, "bozhong_25_1": 0}	{"污油": 11500.0, "caofeidian": 94236.0, "bozhong_25_1": 68152.0, "qinhuangdao/nanpu_35_2": 52358.0}	96.5845	24.0
DETAIL-PLAN-202603-15-0	PLAN-202603	2026-03-15	15	16658.813394901245586159200	{"污油": 0, "caofeidian": 187.7049396608591, "qinhuangdao": 16471.108455240388, "bozhong_25_1": 0}	{"污油": 11500.0, "caofeidian": 94044.0, "bozhong_25_1": 68152.0, "qinhuangdao/nanpu_35_2": 35510.0}	96.5845	24.0
DETAIL-PLAN-202603-16-0	PLAN-202603	2026-03-16	16	7860.144348298475030089200	{"污油": 0, "caofeidian": 93.85246983042956, "qinhuangdao": 0, "bozhong_25_1": 7766.291878468045}	{"污油": 11500.0, "caofeidian": 93852.0, "bozhong_25_1": 68152.0, "qinhuangdao/nanpu_35_2": 18662.0}	91.1431	12.0
DETAIL-PLAN-202603-16-1	PLAN-202603	2026-03-16	16	8329.406697450622793079600	{"污油": 0, "caofeidian": 93.85246983042956, "qinhuangdao": 8235.554227620194, "bozhong_25_1": 0}	{"污油": 11500.0, "caofeidian": 93852.0, "bozhong_25_1": 68152.0, "qinhuangdao/nanpu_35_2": 18662.0}	91.1431	12.0
DETAIL-PLAN-202603-17-0	PLAN-202603	2026-03-17	17	15720.288696596950060178400	{"污油": 0, "caofeidian": 187.7049396608591, "qinhuangdao": 0, "bozhong_25_1": 15532.58375693609}	{"污油": 11500.0, "caofeidian": 93660.0, "bozhong_25_1": 60208.0, "qinhuangdao/nanpu_35_2": 66238.0}	91.1431	24.0
DETAIL-PLAN-202603-18-0	PLAN-202603	2026-03-18	18	15720.288696596950060178400	{"污油": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 0, "bozhong_25_1": 15485.657522020876}	{"污油": 12930.0, "caofeidian": 72159.0, "bozhong_25_1": 45241.0, "qinhuangdao/nanpu_35_2": 67943.0}	91.1431	24.0
DETAIL-PLAN-202603-19-0	PLAN-202603	2026-03-19	19	15720.288696596950060178400	{"污油": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 0, "bozhong_25_1": 15485.657522020876}	{"污油": 12930.0, "caofeidian": 96619.0, "bozhong_25_1": 29401.0, "qinhuangdao/nanpu_35_2": 67943.0}	91.1431	24.0
DETAIL-PLAN-202603-20-0	PLAN-202603	2026-03-20	20	11790.216522447712545133800	{"污油": 0, "caofeidian": 175.97338093205542, "qinhuangdao": 0, "bozhong_25_1": 11614.243141515657}	{"污油": 12930.0, "caofeidian": 96379.0, "bozhong_25_1": 13561.0, "qinhuangdao/nanpu_35_2": 67943.0}	96.0293	18.0
DETAIL-PLAN-202603-20-1	PLAN-202603	2026-03-20	20	4164.703348725311396539800	{"污油": 0, "caofeidian": 58.65779364401847, "qinhuangdao": 4106.045555081293, "bozhong_25_1": 0}	{"污油": 12930.0, "caofeidian": 96379.0, "bozhong_25_1": 13561.0, "qinhuangdao/nanpu_35_2": 67943.0}	96.0293	6.0
DETAIL-PLAN-202603-22-0	PLAN-202603	2026-03-22	22	16658.813394901245586159200	{"污油": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 16424.182220325172, "bozhong_25_1": 0}	{"污油": 12930.0, "caofeidian": 95899.0, "bozhong_25_1": 1681.0, "qinhuangdao/nanpu_35_2": 46943.0}	96.0293	24.0
DETAIL-PLAN-202603-23-0	PLAN-202603	2026-03-23	23	16658.813394901245586159200	{"污油": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 16424.182220325172, "bozhong_25_1": 0}	{"污油": 12930.0, "caofeidian": 95659.0, "bozhong_25_1": 1681.0, "qinhuangdao/nanpu_35_2": 30143.0}	96.0293	24.0
DETAIL-PLAN-202603-24-0	PLAN-202603	2026-03-24	24	13491.292538124248185974000	{"污油": 0, "caofeidian": 0, "qinhuangdao": 13491.292538124248, "bozhong_25_1": 0}	{"污油": 12867.0, "caofeidian": 97222.0, "bozhong_25_1": 2321.0, "qinhuangdao/nanpu_35_2": 14202.0}	95.9662	20.0
DETAIL-PLAN-202603-24-1	PLAN-202603	2026-03-24	24	3089.310465251639439686800	{"污油": 0, "caofeidian": 3089.3104652516395, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 12867.0, "caofeidian": 97222.0, "bozhong_25_1": 2321.0, "qinhuangdao/nanpu_35_2": 14202.0}	95.9662	4.0
DETAIL-PLAN-202603-25-0	PLAN-202603	2026-03-25	25	16189.551045749097823168800	{"污油": 0, "caofeidian": 16189.551045749098, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 12867.0, "caofeidian": 94062.0, "bozhong_25_1": 2321.0, "qinhuangdao/nanpu_35_2": 402.0}	95.9662	24.0
DETAIL-PLAN-202603-26-0	PLAN-202603	2026-03-26	26	16189.551045749097823168800	{"污油": 0, "caofeidian": 16189.551045749098, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 12867.0, "caofeidian": 77502.0, "bozhong_25_1": 58321.0, "qinhuangdao/nanpu_35_2": 402.0}	95.9662	24.0
DETAIL-PLAN-202603-27-0	PLAN-202603	2026-03-27	27	16658.813394901245586159200	{"污油": 0, "caofeidian": 16658.813394901244, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 12867.0, "caofeidian": 60942.0, "bozhong_25_1": 58321.0, "qinhuangdao/nanpu_35_2": 60402.0}	95.9662	24.0
DETAIL-PLAN-202603-28-0	PLAN-202603	2026-03-28	28	7625.513173722401148594000	{"污油": 0, "caofeidian": 0, "qinhuangdao": 0, "bozhong_25_1": 7625.513173722401}	{"污油": 12867.0, "caofeidian": 43902.0, "bozhong_25_1": 58321.0, "qinhuangdao/nanpu_35_2": 60402.0}	90.7545	12.0
DETAIL-PLAN-202603-28-1	PLAN-202603	2026-03-28	28	8564.037872026696674574800	{"污油": 0, "caofeidian": 8564.037872026696, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 12867.0, "caofeidian": 43902.0, "bozhong_25_1": 58321.0, "qinhuangdao/nanpu_35_2": 60402.0}	90.7545	12.0
DETAIL-PLAN-202603-29-0	PLAN-202603	2026-03-29	29	15720.288696596950060178400	{"污油": 0, "caofeidian": 469.26234915214775, "qinhuangdao": 0, "bozhong_25_1": 15251.026347444802}	{"污油": 12867.0, "caofeidian": 93142.0, "bozhong_25_1": 50521.0, "qinhuangdao/nanpu_35_2": 60402.0}	90.7545	24.0
DETAIL-PLAN-202603-1-0	PLAN-202603	2026-03-01	1	16776.128982189282526906800	{"SEPIA": 0, "污油": 117.31558728803694, "luda_10_1": 0, "caofeidian": 16658.813394901244, "bozhong_25_1": 0}	{"LH/LD": 63372.0, "SEPIA": -593.0, "污油": 43889.0, "caofeidian": 43305.0, "bozhong_25_1": 56529.0}	97.2647	24.0
DETAIL-PLAN-202603-2-0	PLAN-202603	2026-03-02	2	7918.802141942493500463000	{"SEPIA": 0, "污油": 58.65779364401847, "luda_10_1": 0, "caofeidian": 0, "bozhong_25_1": 7860.144348298475}	{"LH/LD": 63372.0, "SEPIA": -593.0, "污油": 43769.0, "caofeidian": 26265.0, "bozhong_25_1": 56529.0}	91.4523	12.0
DETAIL-PLAN-202603-7-0	PLAN-202603	2026-03-07	7	16893.444569477319467654400	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 16893.44456947732, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 11463.0, "SEPIA/TUPI": 243.0, "caofeidian": 76467.0, "bozhong_25_1": 57495.0, "qinhuangdao/nanpu_35_2": 72323.0}	98.1694	24.0
DETAIL-PLAN-202603-8-0	PLAN-202603	2026-03-08	8	16893.444569477319467654400	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 16893.44456947732, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 11463.0, "SEPIA/TUPI": 243.0, "caofeidian": 59187.0, "bozhong_25_1": 57495.0, "qinhuangdao/nanpu_35_2": 72323.0}	98.1694	24.0
DETAIL-PLAN-202603-9-0	PLAN-202603	2026-03-09	9	7097.593030926234915229800	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 0, "qinhuangdao": 0, "bozhong_25_1": 7097.593030926235}	{"污油": 11463.0, "SEPIA/TUPI": 35243.0, "caofeidian": 41907.0, "bozhong_25_1": 57495.0, "qinhuangdao/nanpu_35_2": 72323.0}	90.9513	11.0
DETAIL-PLAN-202603-9-1	PLAN-202603	2026-03-09	9	9258.155096814248573998100	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 9258.15509681425, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 11463.0, "SEPIA/TUPI": 35243.0, "caofeidian": 41907.0, "bozhong_25_1": 57495.0, "qinhuangdao/nanpu_35_2": 72323.0}	90.9513	13.0
DETAIL-PLAN-202603-10-0	PLAN-202603	2026-03-10	10	15720.288696596950060178400	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 0, "bozhong_25_1": 15485.657522020876}	{"污油": 11463.0, "SEPIA/TUPI": 35243.0, "caofeidian": 32437.0, "bozhong_25_1": 50235.0, "qinhuangdao/nanpu_35_2": 72323.0}	90.9513	24.0
DETAIL-PLAN-202603-11-0	PLAN-202603	2026-03-11	11	15720.288696596950060178400	{"污油": 0, "caofeidian": 187.7049396608591, "qinhuangdao": 0, "bozhong_25_1": 15532.58375693609}	{"污油": 11500.0, "caofeidian": 36812.0, "bozhong_25_1": 63618.0, "qinhuangdao/nanpu_35_2": 72716.0}	90.9513	24.0
DETAIL-PLAN-202603-12-0	PLAN-202603	2026-03-12	12	12445.228551472585464307900	{"污油": 0, "caofeidian": 148.59974389818012, "qinhuangdao": 0, "bozhong_25_1": 12296.628807574405}	{"污油": 11500.0, "caofeidian": 94620.0, "bozhong_25_1": 48730.0, "qinhuangdao/nanpu_35_2": 72716.0}	96.5845	19.0
DETAIL-PLAN-202603-12-1	PLAN-202603	2026-03-12	12	3470.586123937759497116500	{"污油": 0, "caofeidian": 39.10519576267898, "qinhuangdao": 3431.4809281750804, "bozhong_25_1": 0}	{"污油": 11500.0, "caofeidian": 94620.0, "bozhong_25_1": 48730.0, "qinhuangdao/nanpu_35_2": 72716.0}	96.5845	5.0
DETAIL-PLAN-202603-2-1	PLAN-202603	2026-03-02	2	8388.064491094641263453400	{"SEPIA": 0, "污油": 58.65779364401847, "luda_10_1": 0, "caofeidian": 8329.406697450622, "bozhong_25_1": 0}	{"LH/LD": 63372.0, "SEPIA": -593.0, "污油": 43769.0, "caofeidian": 26265.0, "bozhong_25_1": 56529.0}	91.4523	12.0
DETAIL-PLAN-202603-3-0	PLAN-202603	2026-03-03	3	15720.288696596950060178400	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 117.31558728803694, "qinhuangdao": 0, "bozhong_25_1": 15602.973109308914}	{"污油": 11463.0, "SEPIA/TUPI": 1243.0, "caofeidian": 107127.0, "bozhong_25_1": 36405.0, "qinhuangdao/nanpu_35_2": 72323.0}	91.4523	24.0
DETAIL-PLAN-202603-4-0	PLAN-202603	2026-03-04	4	15837.604283884987000926000	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 234.63117457607387, "qinhuangdao": 0, "bozhong_25_1": 15602.973109308914}	{"污油": 11463.0, "SEPIA/TUPI": 1243.0, "caofeidian": 107007.0, "bozhong_25_1": 20445.0, "qinhuangdao/nanpu_35_2": 72323.0}	91.4523	24.0
DETAIL-PLAN-202603-5-0	PLAN-202603	2026-03-05	5	3900.743277327228279857700	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 0, "qinhuangdao": 0, "bozhong_25_1": 3900.7432773272285}	{"污油": 11463.0, "SEPIA/TUPI": 21243.0, "caofeidian": 106767.0, "bozhong_25_1": 4485.0, "qinhuangdao/nanpu_35_2": 72323.0}	98.1694	6.0
DETAIL-PLAN-202603-5-1	PLAN-202603	2026-03-05	5	12728.741220752008071114600	{"污油": 0, "SEPIA/TUPI": 0, "caofeidian": 12728.741220752008, "qinhuangdao": 0, "bozhong_25_1": 0}	{"污油": 11463.0, "SEPIA/TUPI": 21243.0, "caofeidian": 106767.0, "bozhong_25_1": 4485.0, "qinhuangdao/nanpu_35_2": 72323.0}	98.1694	18.0
DETAIL-PLAN-202603-30-0	PLAN-202603	2026-03-30	30	15720.288696596950060178400	{"污油": 0, "caofeidian": 469.26234915214775, "qinhuangdao": 0, "bozhong_25_1": 15251.026347444802}	{"污油": 12867.0, "caofeidian": 92662.0, "bozhong_25_1": 69921.0, "qinhuangdao/nanpu_35_2": 70402.0}	90.7545	24.0
DETAIL-PLAN-202603-31-0	PLAN-202603	2026-03-31	31	15720.288696596950060178400	{"污油": 0, "caofeidian": 469.26234915214775, "qinhuangdao": 0, "bozhong_25_1": 15251.026347444802}	{"污油": 12867.0, "caofeidian": 92182.0, "bozhong_25_1": 54321.0, "qinhuangdao/nanpu_35_2": 70402.0}	90.7545	24.0
\.


--
-- solve_db.scheduling_tasks
--
-- Data for Name: scheduling_tasks; Type: TABLE DATA; Schema: solve_db; Owner: -
--

COPY solve_db.scheduling_tasks (plan_id, planned_month, status, locked, created_at, updated_at, generated_at) FROM stdin;
PLAN-202604	2026-04	generated	f	2026-06-14 06:32:22.613109+08	2026-06-14 07:30:09.78787+08	2026-06-14 06:32:22.613109+08
\.


--
-- PostgreSQL database dump complete
--

\unrestrict jPzOdht6jVseZgzA9ENEFR8Fns33AtpxbIThwah6I0tJLPY8S06781EfCah7P93


-- ── FK 约束（所有数据 COPY 之后创建，避免加载顺序违约）─────────────────
ALTER TABLE ONLY solve_db.device_yields
    ADD CONSTRAINT device_yields_fk_side_line FOREIGN KEY (side_line_id) REFERENCES solve_db.side_lines(side_line_id) ON DELETE CASCADE;

