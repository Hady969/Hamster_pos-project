package com.vidopos.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidopos.order.api.dto.CreateOrderRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class RequestHashUtil {

    private RequestHashUtil() {
    }

    public static String hash(ObjectMapper objectMapper, CreateOrderRequest request) {
        CanonicalRequest canonical = new CanonicalRequest(
                request.customerEmail().trim().toLowerCase(),
                request.items().stream()
                        .sorted(Comparator
                                .comparing((CreateOrderRequest.Item i) -> i.productId().toString())
                                .thenComparingInt(CreateOrderRequest.Item::quantity))
                        .map(i -> new CanonicalItem(i.productId().toString(), i.quantity()))
                        .toList()
        );
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash order request", e);
        }
    }

    private record CanonicalRequest(String customerEmail, List<CanonicalItem> items) {
    }

    private record CanonicalItem(String productId, int quantity) {
    }
}
