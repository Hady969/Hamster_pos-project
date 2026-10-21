package com.vidopos.order.client;

import com.vidopos.order.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestTemplate restTemplate;
    private final InventoryProperties properties;

    public InventoryClient(RestTemplate inventoryRestTemplate, InventoryProperties properties) {
        this.restTemplate = inventoryRestTemplate;
        this.properties = properties;
    }

    public InventoryDtos.ReservationResponse reserve(InventoryDtos.ReserveRequest request) {
        return executeWithRetry(() -> {
            ResponseEntity<InventoryDtos.ReservationResponse> response = restTemplate.postForEntity(
                    "/api/reservations",
                    request,
                    InventoryDtos.ReservationResponse.class
            );
            return response.getBody();
        }, "reserve orderId=" + request.orderId());
    }

    public Optional<InventoryDtos.ReservationResponse> findReservation(UUID orderId) {
        try {
            InventoryDtos.ReservationResponse body = executeWithRetry(() -> {
                ResponseEntity<InventoryDtos.ReservationResponse> response = restTemplate.getForEntity(
                        "/api/reservations/by-order/{orderId}",
                        InventoryDtos.ReservationResponse.class,
                        orderId
                );
                return response.getBody();
            }, "findReservation orderId=" + orderId);
            return Optional.ofNullable(body);
        } catch (InventoryClientException ex) {
            if (ex.getHttpStatus() != null && ex.getHttpStatus() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private <T> T executeWithRetry(ClientCall<T> call, String operation) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        InventoryClientException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.execute();
            } catch (HttpStatusCodeException ex) {
                HttpStatusCode status = ex.getStatusCode();
                boolean retryable = status.is5xxServerError();
                last = new InventoryClientException(
                        "Inventory " + operation + " failed with HTTP " + status.value(),
                        retryable,
                        status.value(),
                        ex
                );
                if (!retryable || attempt == attempts) {
                    throw last;
                }
            } catch (ResourceAccessException ex) {
                last = new InventoryClientException(
                        "Inventory " + operation + " timed out or unreachable",
                        true,
                        null,
                        ex
                );
                if (attempt == attempts) {
                    throw last;
                }
            }

            log.warn("Retrying inventory call ({}) attempt {}/{}", operation, attempt, attempts);
            sleep(properties.getRetryBackoff().toMillis() * attempt);
        }

        throw last != null ? last : new InventoryClientException("Inventory call failed", true, null, null);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InventoryClientException("Interrupted during retry backoff", false, null, ie);
        }
    }

    @FunctionalInterface
    private interface ClientCall<T> {
        T execute();
    }
}
