package com.inventory.streams;

import com.inventory.avro.EventType;
import com.inventory.avro.InventoryAggregate;
import com.inventory.avro.InventoryEvent;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InventoryAggregationTopology {
    
    private static final Logger logger = LoggerFactory.getLogger(InventoryAggregationTopology.class);
    
    private static final String INPUT_TOPIC = "inventory-events";
    private static final String OUTPUT_TOPIC = "inventory-aggregates";
    private static final String DLQ_TOPIC = "inventory-events-dlq";
    private static final String STATE_STORE_NAME = "inventory-state-store";
    
    private final String schemaRegistryUrl;
    
    public InventoryAggregationTopology(String schemaRegistryUrl) {
        this.schemaRegistryUrl = schemaRegistryUrl;
    }
    
    public Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();
        
        // Configure Avro Serdes
        Map<String, String> serdeConfig = Collections.singletonMap(
            "schema.registry.url", schemaRegistryUrl
        );
        
        SpecificAvroSerde<InventoryEvent> eventSerde = new SpecificAvroSerde<>();
        eventSerde.configure(serdeConfig, false);
        
        SpecificAvroSerde<InventoryAggregate> aggregateSerde = new SpecificAvroSerde<>();
        aggregateSerde.configure(serdeConfig, false);
        
        // Create state store for windowed aggregation
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE_NAME),
                Serdes.String(),
                aggregateSerde
            )
        );
        
        // Main stream processing
        KStream<String, InventoryEvent> events = builder
            .stream(INPUT_TOPIC, Consumed.with(Serdes.String(), eventSerde))
            .filter((key, event) -> validateEvent(key, event));
        
        // Branch for valid and invalid events
        Map<String, KStream<String, InventoryEvent>> branches = events
            .split(Named.as("branch-"))
            .branch((key, event) -> isValidForProcessing(event), Branched.as("valid"))
            .defaultBranch(Branched.as("invalid"));
        
        // Send invalid events to DLQ
        branches.get("branch-invalid")
            .peek((key, event) -> logger.warn("Sending event to DLQ: {}", event))
            .to(DLQ_TOPIC, Produced.with(Serdes.String(), eventSerde));
        
        // Process valid events
        KTable<String, InventoryAggregate> aggregates = branches.get("branch-valid")
            .groupByKey(Grouped.with(Serdes.String(), eventSerde))
            .aggregate(
                () -> createInitialAggregate(),
                (key, event, aggregate) -> updateAggregate(key, event, aggregate),
                Materialized.<String, InventoryAggregate>as(STATE_STORE_NAME)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(aggregateSerde)
            );
        
        // Suppress intermediate updates (emit only after window closes)
        aggregates
            .toStream()
            .peek((key, aggregate) -> logger.info("Updated aggregate for SKU {}: total={}, available={}",
                key, aggregate.getTotalQuantity(), aggregate.getAvailableQuantity()))
            .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), aggregateSerde));
        
        return builder.build();
    }
    
    private boolean validateEvent(String key, InventoryEvent event) {
        if (event == null) {
            logger.error("Null event received for key: {}", key);
            return false;
        }
        if (event.getSku() == null || event.getSku().isEmpty()) {
            logger.error("Event missing SKU: {}", event);
            return false;
        }
        if (event.getEventType() == null) {
            logger.error("Event missing type: {}", event);
            return false;
        }
        return true;
    }
    
    private boolean isValidForProcessing(InventoryEvent event) {
        return event.getQuantity() != null && event.getQuantity() >= 0;
    }
    
    private InventoryAggregate createInitialAggregate() {
        return InventoryAggregate.newBuilder()
            .setSku("")
            .setProductName("")
            .setTotalQuantity(0L)
            .setWarehouseQuantity(0L)
            .setStoreQuantity(0L)
            .setEcommerceQuantity(0L)
            .setReservedQuantity(0L)
            .setAvailableQuantity(0L)
            .setLocationBreakdown(new HashMap<>())
            .setLastUpdated(System.currentTimeMillis())
            .setVersion(0L)
            .setLowStockThreshold(10L)
            .setIsLowStock(false)
            .setMetadata(null)
            .build();
    }
    
    private InventoryAggregate updateAggregate(String key, InventoryEvent event, InventoryAggregate aggregate) {
        try {
            // Initialize if first event
            if (aggregate.getSku().isEmpty()) {
                aggregate = InventoryAggregate.newBuilder(aggregate)
                    .setSku(event.getSku())
                    .setProductName(event.getProductName() != null ? event.getProductName().toString() : "")
                    .build();
            }
            
            long quantityDelta = event.getQuantity();
            EventType eventType = event.getEventType();
            String location = event.getLocationId() != null ? event.getLocationId().toString() : "unknown";
            
            // Update location breakdown
            Map<CharSequence, Long> locationBreakdown = new HashMap<>(aggregate.getLocationBreakdown());
            long currentLocationQty = locationBreakdown.getOrDefault(location, 0L);
            
            // Apply quantity changes based on event type
            switch (eventType) {
                case STOCK_IN:
                    aggregate = InventoryAggregate.newBuilder(aggregate)
                        .setTotalQuantity(aggregate.getTotalQuantity() + quantityDelta)
                        .build();
                    locationBreakdown.put(location, currentLocationQty + quantityDelta);
                    break;
                    
                case STOCK_OUT:
                case SALE:
                    aggregate = InventoryAggregate.newBuilder(aggregate)
                        .setTotalQuantity(Math.max(0, aggregate.getTotalQuantity() - quantityDelta))
                        .build();
                    locationBreakdown.put(location, Math.max(0, currentLocationQty - quantityDelta));
                    break;
                    
                case RESERVATION:
                    aggregate = InventoryAggregate.newBuilder(aggregate)
                        .setReservedQuantity(aggregate.getReservedQuantity() + quantityDelta)
                        .build();
                    break;
                    
                case RESERVATION_CANCELLED:
                    aggregate = InventoryAggregate.newBuilder(aggregate)
                        .setReservedQuantity(Math.max(0, aggregate.getReservedQuantity() - quantityDelta))
                        .build();
                    break;
                    
                case ADJUSTMENT:
                    // For adjustments, set absolute quantity
                    long currentQty = locationBreakdown.getOrDefault(location, 0L);
                    long delta = quantityDelta - currentQty;
                    aggregate = InventoryAggregate.newBuilder(aggregate)
                        .setTotalQuantity(aggregate.getTotalQuantity() + delta)
                        .build();
                    locationBreakdown.put(location, quantityDelta);
                    break;
            }
            
            // Update location-specific quantities
            aggregate = updateLocationQuantities(aggregate, event, locationBreakdown);
            
            // Calculate available quantity
            long availableQty = Math.max(0, aggregate.getTotalQuantity() - aggregate.getReservedQuantity());
            
            // Check low stock
            boolean isLowStock = availableQty < aggregate.getLowStockThreshold();
            
            // Build final aggregate
            return InventoryAggregate.newBuilder(aggregate)
                .setLocationBreakdown(locationBreakdown)
                .setAvailableQuantity(availableQty)
                .setIsLowStock(isLowStock)
                .setLastUpdated(System.currentTimeMillis())
                .setVersion(aggregate.getVersion() + 1)
                .build();
                
        } catch (Exception e) {
            logger.error("Error updating aggregate for SKU {}: {}", key, e.getMessage(), e);
            return aggregate;
        }
    }
    
    private InventoryAggregate updateLocationQuantities(
            InventoryAggregate aggregate, 
            InventoryEvent event, 
            Map<CharSequence, Long> locationBreakdown) {
        
        long warehouseQty = 0;
        long storeQty = 0;
        long ecommerceQty = 0;
        
        for (Map.Entry<CharSequence, Long> entry : locationBreakdown.entrySet()) {
            String locId = entry.getKey().toString();
            long qty = entry.getValue();
            
            if (locId.startsWith("WH-")) {
                warehouseQty += qty;
            } else if (locId.startsWith("ST-")) {
                storeQty += qty;
            } else if (locId.startsWith("EC-")) {
                ecommerceQty += qty;
            }
        }
        
        return InventoryAggregate.newBuilder(aggregate)
            .setWarehouseQuantity(warehouseQty)
            .setStoreQuantity(storeQty)
            .setEcommerceQuantity(ecommerceQty)
            .build();
    }
}
