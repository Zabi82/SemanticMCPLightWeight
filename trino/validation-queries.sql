-- Validation queries for demo cross-checking
-- Run via: docker exec -it trino trino --catalog semantic_demo --schema ice_db
-- Or paste into Trino UI at http://localhost:8080
-- Expected values match semantic layer metric output as of TPC-H scale factor 0.01

-- ─────────────────────────────────────────────
-- Q1: Order Completion Rate
-- Expected: ~48.69%  (agent: order_completion_rate)
-- Business rule: only orderstatus = 'F' (Filled) counts as completed
-- ─────────────────────────────────────────────
SELECT
  COUNT(*)                                                                          AS total_orders,       -- 15000
  SUM(CASE WHEN orderstatus = 'F'           THEN 1 ELSE 0 END)                     AS completed_orders,   -- 7304
  SUM(CASE WHEN orderstatus IN ('O', 'P')   THEN 1 ELSE 0 END)                     AS pending_orders,     -- 7696
  ROUND(CAST(SUM(CASE WHEN orderstatus = 'F' THEN 1 ELSE 0 END) AS DOUBLE)
        / COUNT(*) * 100, 2)                                                        AS completion_rate_pct -- 48.69
FROM orders;


-- ─────────────────────────────────────────────
-- Q2: Pending Order Value
-- Expected: ~$1.09B  (agent: pending_order_value)
-- Business rule: both 'O' (Open) and 'P' (Pending) are not yet fulfilled
-- ─────────────────────────────────────────────
SELECT
  ROUND(SUM(CASE WHEN orderstatus IN ('O','P') THEN totalprice ELSE 0 END) / 1e9, 3) AS pending_value_billions,   -- ~1.092
  ROUND(SUM(CASE WHEN orderstatus = 'F'        THEN totalprice ELSE 0 END) / 1e9, 3) AS completed_value_billions  -- ~1.036
FROM orders;


-- ─────────────────────────────────────────────
-- Q3: High Priority Order Rate
-- Expected: ~40.57%  (agent: high_priority_order_rate)
-- Business rule: only '1-URGENT' and '2-HIGH' count as high priority
-- ─────────────────────────────────────────────
SELECT
  SUM(CASE WHEN orderpriority IN ('1-URGENT','2-HIGH') THEN 1 ELSE 0 END)          AS high_priority_orders,    -- 6085
  COUNT(*)                                                                          AS total_orders,            -- 15000
  ROUND(CAST(SUM(CASE WHEN orderpriority IN ('1-URGENT','2-HIGH') THEN 1 ELSE 0 END) AS DOUBLE)
        / COUNT(*) * 100, 2)                                                        AS high_priority_rate_pct  -- 40.57
FROM orders;


-- ─────────────────────────────────────────────
-- Q4: Supplier On-Time Delivery Rate
-- Expected: ~49.39%  (agent: supplier_on_time_rate)
-- Business rule: shipdate <= commitdate means on-time
-- ─────────────────────────────────────────────
SELECT
  SUM(CASE WHEN shipdate <= commitdate THEN 1 ELSE 0 END)                          AS on_time_deliveries,  -- 29720
  COUNT(*)                                                                          AS total_deliveries,    -- 60175
  ROUND(CAST(SUM(CASE WHEN shipdate <= commitdate THEN 1 ELSE 0 END) AS DOUBLE)
        / COUNT(*) * 100, 2)                                                        AS on_time_rate_pct     -- 49.39
FROM lineitem;


-- ─────────────────────────────────────────────
-- Q5: Revenue by Region
-- Expected (agent: revenue_by_region):
--   MIDDLE EAST ~$451M, AFRICA ~$428M, AMERICA ~$398M, ASIA ~$397M, EUROPE ~$371M
-- Note: revenue = extendedprice * (1 - discount)  i.e. after discount
-- ─────────────────────────────────────────────
SELECT
  r.name                                                          AS region,
  ROUND(SUM(l.extendedprice * (1 - l.discount)) / 1e6, 2)       AS revenue_millions
FROM lineitem l
JOIN orders   o ON l.orderkey  = o.orderkey
JOIN customer c ON o.custkey   = c.custkey
JOIN nation   n ON c.nationkey = n.nationkey
JOIN region   r ON n.regionkey = r.regionkey
GROUP BY r.name
ORDER BY revenue_millions DESC;


-- ─────────────────────────────────────────────
-- Bonus: Priority breakdown (shows why business rule matters)
-- ─────────────────────────────────────────────
SELECT
  orderpriority,
  COUNT(*) AS order_count
FROM orders
GROUP BY orderpriority
ORDER BY orderpriority;
