package com.taca.cart.integrations;

import com.taca.cart.application.InventoryStock;

import java.util.Map;
import java.util.Set;

public interface InventoryAvailabilityClient {
    Map<String, InventoryStock> fetchAvailability(Set<String> skuIds);
}
