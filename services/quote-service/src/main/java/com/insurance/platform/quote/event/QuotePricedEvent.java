package com.insurance.platform.quote.event;

/** Event factory result; its envelope is serialized into the transactional outbox. */
public record QuotePricedEvent(EventEnvelope<QuotePricedEventData> envelope) {
    public static final String TYPE = "QuotePriced";
    public static final int VERSION = 1;
    public static final String AGGREGATE_TYPE = "Quote";
    public static final String PRODUCER = "quote-service";
}
