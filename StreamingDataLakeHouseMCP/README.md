# DataLakehouse MCP Server

Spring AI MCP server that exposes Trino/Iceberg data layer tools to AI agents via MCP (Model Context Protocol). Used for Scenario One (data layer only) in the demo.

## Tools

| Tool | Description |
|------|-------------|
| `trino_catalogs` | List all Trino catalogs |
| `trino_schemas` | List schemas in a catalog |
| `trino_iceberg_tables` | List Iceberg tables in a catalog/schema |
| `get_iceberg_table_schema` | Get column names and types for a table |
| `execute_trino_query` | Execute a SELECT query (read-only) |
| `iceberg_time_travel_query` | Query historical data by timestamp or snapshot ID |
| `list_iceberg_snapshots` | List all snapshots for a table |

Default catalog: `semantic_demo`, default schema: `ice_db`.

## Prerequisites

- Java 21
- Maven 3.9+
- Trino running on port 8080 (`docker compose up -d` from repo root)

## Build

```bash
cd DataLakeHouseMCP
mvn clean package -q
```

## Run

The server runs in stdio mode — it is launched by the MCP client (Kiro, Claude Desktop, Copilot), not manually. Configure it in your client's `mcp.json`:

```json
{
  "lakehouse-data": {
    "command": "java",
    "args": ["-jar", "/absolute/path/to/DataLakeHouseMCP/target/datalakehouse-mcp-0.1.0.jar"]
  }
}
```

## Configuration

`src/main/resources/application.properties`:

```properties
trino.host=localhost
trino.port=8080
trino.user=admin
trino.catalog=semantic_demo
trino.schema=ice_db
```

## Testing with MCP Inspector

```bash
npx @modelcontextprotocol/inspector \
  java -jar target/datalakehouse-mcp-0.1.0.jar
```

Opens at `http://localhost:6274`. Sample calls:
- `trino_catalogs()` → `semantic_demo`, `system`, `tpch`
- `trino_iceberg_tables("semantic_demo", "ice_db")` → 8 TPC-H tables
- `execute_trino_query("SELECT COUNT(*) FROM customer", "semantic_demo", "ice_db")` → 1500
- `list_iceberg_snapshots("semantic_demo", "ice_db", "orders")` → snapshot history
