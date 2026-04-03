#!/bin/bash
# Wait for Trino to be ready and then execute initialization SQL

echo "Waiting for Trino to be ready..."
sleep 30

echo "Executing TPCH data ingestion..."
trino --file /etc/trino/init-tpch.sql

echo "TPCH data ingestion completed"
