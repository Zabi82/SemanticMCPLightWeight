-- TPCH Data Ingestion Script
-- This script ingests TPCH benchmark data from tpch.tiny catalog into Iceberg tables
-- Executed automatically during Trino container startup

-- Create schema for Iceberg tables
CREATE SCHEMA IF NOT EXISTS semantic_demo.ice_db;

-- Ingest TPCH tables as Iceberg tables with Parquet format
CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.customer 
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.customer;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.orders
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.orders;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.lineitem
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.lineitem;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.part
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.part;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.supplier
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.supplier;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.partsupp
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.partsupp;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.nation
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.nation;

CREATE TABLE IF NOT EXISTS semantic_demo.ice_db.region
WITH (format = 'PARQUET')
AS SELECT * FROM tpch.tiny.region;

-- Create streaming_db schema for Kafka-landed streaming data
-- orders and lineitem are created by the Kafka Connect Iceberg sink connector
-- Reference tables are copied here so all joins stay within streaming_db
CREATE SCHEMA IF NOT EXISTS semantic_demo.streaming_db;

CREATE TABLE IF NOT EXISTS semantic_demo.streaming_db.customer
WITH (format = 'PARQUET')
AS SELECT * FROM semantic_demo.ice_db.customer;

CREATE TABLE IF NOT EXISTS semantic_demo.streaming_db.supplier
WITH (format = 'PARQUET')
AS SELECT * FROM semantic_demo.ice_db.supplier;

CREATE TABLE IF NOT EXISTS semantic_demo.streaming_db.nation
WITH (format = 'PARQUET')
AS SELECT * FROM semantic_demo.ice_db.nation;

CREATE TABLE IF NOT EXISTS semantic_demo.streaming_db.region
WITH (format = 'PARQUET')
AS SELECT * FROM semantic_demo.ice_db.region;

CREATE TABLE IF NOT EXISTS semantic_demo.streaming_db.part
WITH (format = 'PARQUET')
AS SELECT * FROM semantic_demo.ice_db.part;

CREATE TABLE IF NOT EXISTS semantic_demo.streaming_db.partsupp
WITH (format = 'PARQUET')
AS SELECT * FROM semantic_demo.ice_db.partsupp;
