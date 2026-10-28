package com.moneytorch.source;

/** One observed price; ts is epoch millis UTC. */
public record PricePoint(String itemId, long ts, double price) {
}
