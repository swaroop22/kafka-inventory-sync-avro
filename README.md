# kafka-inventory-sync-avro

Production-grade Kafka inventory synchronization system using Apache Avro and Schema Registry for type-safe, schema-evolved event streaming across warehouses, stores, and e-commerce platforms.

## Overview

This project implements a real-time inventory management solution leveraging:
- **Apache Kafka** for distributed event streaming
- **Apache Avro** for efficient, schema-based serialization
- **Confluent Schema Registry** for schema evolution and compatibility
- **Kafka Streams** for stateful stream processing and aggregation
- **Docker Compose** for local development environment

## Architecture

### Event Flow
```
Inventory Sources → Kafka (inventory-events) → Kafka Streams → Kafka (inventory-aggregates)
                                                      ↓
                                                     DLQ
```

### Key Components

1. **InventoryEvent Schema** (`src/main/resources/avro/InventoryEvent.avsc`)
   - Captures inventory changes (STOCK_IN, STOCK_OUT, SALE, RESERVATION, ADJUSTMENT)
   - Includes metadata: SKU, quantity, location, timestamps, user tracking
   - Supports optional fields for backward/forward compatibility

2. **InventoryAggregate Schema** (`src/main/resources/avro/InventoryAggregate.avsc`)
   - Maintains aggregated inventory state
   - Tracks quantities by location type (warehouse, store, e-commerce)
   - Provides real-time availability and low-stock indicators

3. **Kafka Streams Topology** (`InventoryAggregationTopology.java`)
   - Event validation and filtering
   - Dead Letter Queue (DLQ) for invalid events
   - Stateful aggregation with persistent state stores
   - Location-based quantity tracking
   - Low stock detection

## Features

### Type Safety with Avro
- Compile-time type checking with generated Java classes
- Automatic schema validation
- Reduced serialization overhead compared to JSON
- Binary format for efficient network transmission

### Schema Evolution
- Backward compatible schema changes
- Forward compatible with default values
- Centralized schema management via Schema Registry
- Version control for schemas

### Stream Processing
- Exactly-once processing semantics (EOS)
- Event-time processing with watermarks
- Stateful aggregations with RocksDB backend
- Automatic state recovery and rebalancing

### Operational Features
- Dead Letter Queue for failed events
- Comprehensive logging and metrics
- Health checks and monitoring endpoints
- Graceful shutdown and state preservation

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose
- 8GB RAM minimum

## Quick Start

### 1. Start Infrastructure

```bash
# Start Kafka, Zookeeper, Schema Registry, and Kafka UI
docker-compose up -d

# Wait for services to be ready (~30 seconds)
docker-compose logs -f kafka-init
```

### 2. Build the Application

```bash
# Generate Avro classes and compile
mvn clean install

# Build Docker image
mvn clean package
docker build -t inventory-streams-app .
```

### 3. Run the Streams Application

```bash
# Option 1: Run with Docker Compose
docker-compose up inventory-streams-app

# Option 2: Run locally
mvn exec:java -Dexec.mainClass="com.inventory.streams.InventoryStreamsApplication"
```

### 4. Access Services

- **Kafka UI**: http://localhost:8080
- **Schema Registry**: http://localhost:8081
- **Kafka Broker**: localhost:9092

## Project Structure

```
kafka-inventory-sync-avro/
├── src/main/
│   ├── java/com/inventory/
│   │   └── streams/
│   │       └── InventoryAggregationTopology.java
│   └── resources/
│       ├── avro/
│       │   ├── InventoryEvent.avsc          # Event schema
│       │   └── InventoryAggregate.avsc      # Aggregate schema
│       └── application.yml                  # Configuration
├── docker-compose.yml                       # Local development stack
└── pom.xml                                 # Maven dependencies
```

## Configuration

Key configuration options in `application.yml`:

```yaml
kafka:
  bootstrap-servers: localhost:9092
  schema-registry:
    url: http://localhost:8081
  streams:
    processing-guarantee: exactly_once_v2
    num-stream-threads: 3
```

## Producing Events

### Using Kafka Console Producer with Avro

```bash
# Produce an event
kafka-avro-console-producer \
  --broker-list localhost:9092 \
  --topic inventory-events \
  --property value.schema='$(cat src/main/resources/avro/InventoryEvent.avsc)' \
  --property schema.registry.url=http://localhost:8081

# Input:
{"eventId":"evt-001","sku":"LAPTOP-15","eventType":"STOCK_IN","quantity":100,"locationId":"WH-NY","productName":"15-inch Laptop","timestamp":1704067200000,"userId":"admin","reason":null,"metadata":null}
```

### Sample Events

```json
// Stock In Event
{
  "eventId": "evt-001",
  "sku": "LAPTOP-15",
  "eventType": "STOCK_IN",
  "quantity": 100,
  "locationId": "WH-NY",
  "productName": "15-inch Laptop",
  "timestamp": 1704067200000,
  "userId": "admin"
}

// Sale Event
{
  "eventId": "evt-002",
  "sku": "LAPTOP-15",
  "eventType": "SALE",
  "quantity": 1,
  "locationId": "ST-NYC-001",
  "timestamp": 1704067260000,
  "userId": "cashier-001"
}

// Reservation Event
{
  "eventId": "evt-003",
  "sku": "LAPTOP-15",
  "eventType": "RESERVATION",
  "quantity": 5,
  "locationId": "EC-ONLINE",
  "timestamp": 1704067320000,
  "userId": "system"
}
```

## Monitoring

### View Topics via Kafka UI
1. Navigate to http://localhost:8080
2. Select "inventory-cluster"
3. View topics: `inventory-events`, `inventory-aggregates`, `inventory-events-dlq`

### Check Schema Registry
```bash
# List schemas
curl http://localhost:8081/subjects

# Get schema version
curl http://localhost:8081/subjects/inventory-events-value/versions/1
```

### View Aggregates
```bash
# Consume aggregates
kafka-avro-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic inventory-aggregates \
  --from-beginning \
  --property schema.registry.url=http://localhost:8081
```

## Testing

```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify

# Run with test coverage
mvn clean test jacoco:report
```

## Schema Evolution Examples

### Adding a New Optional Field

```json
// InventoryEvent.avsc v2
{
  "name": "supplierName",
  "type": ["null", "string"],
  "default": null,
  "doc": "Supplier name for stock-in events"
}
```

### Backward Compatibility
- New consumers can read old messages (missing fields use defaults)
- Old consumers can read new messages (ignore unknown fields)

## Production Considerations

### Scaling
- Increase `num-stream-threads` for parallel processing
- Add more partitions to topics
- Deploy multiple instances for high availability

### Performance Tuning
```yaml
streams:
  cache-max-bytes-buffering: 10485760  # 10 MB
  commit-interval-ms: 30000
  producer:
    batch-size: 32768
    linger-ms: 20
```

### Monitoring Metrics
- Stream processing lag
- Throughput (records/sec)
- State store size
- DLQ message rate
- Schema registry latency

## Troubleshooting

### Schema Registration Errors
```bash
# Check schema compatibility
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{"schema":"{...}"}' \
  http://localhost:8081/compatibility/subjects/inventory-events-value/versions/latest
```

### Reset Stream Application
```bash
# Reset state and offsets
kafka-streams-application-reset \
  --application-id inventory-aggregation-app \
  --bootstrap-servers localhost:9092
```

### View DLQ Messages
```bash
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic inventory-events-dlq \
  --from-beginning
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure schemas are backward compatible
5. Submit a pull request

## License

MIT License - see LICENSE file for details

## References

- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Apache Avro Specification](https://avro.apache.org/docs/current/spec.html)
- [Confluent Schema Registry](https://docs.confluent.io/platform/current/schema-registry/index.html)
- [Kafka Streams Documentation](https://kafka.apache.org/documentation/streams/)
