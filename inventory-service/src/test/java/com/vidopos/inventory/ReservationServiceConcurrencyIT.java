package com.vidopos.inventory;

import com.vidopos.inventory.api.dto.CreateProductRequest;
import com.vidopos.inventory.api.dto.ProductResponse;
import com.vidopos.inventory.api.dto.ReserveStockRequest;
import com.vidopos.inventory.service.InsufficientStockException;
import com.vidopos.inventory.service.ProductService;
import com.vidopos.inventory.service.ReservationService;
import com.vidopos.inventory.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ReservationServiceConcurrencyIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestcontainers.register(registry);
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ReservationService reservationService;

    @Test
    void concurrentOrdersForLastUnit_onlyOneSucceeds() throws Exception {
        ProductResponse product = productService.create(
                new CreateProductRequest("Limited", new BigDecimal("10.00"), 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        Callable<Void> attempt = () -> {
            try {
                reservationService.reserve(new ReserveStockRequest(
                        UUID.randomUUID(),
                        List.of(new ReserveStockRequest.Item(product.id(), 1))
                ));
                successes.incrementAndGet();
            } catch (InsufficientStockException ex) {
                conflicts.incrementAndGet();
            }
            return null;
        };

        List<Future<Void>> futures = pool.invokeAll(List.of(attempt, attempt));
        for (Future<Void> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(productService.get(product.id()).availableQuantity()).isZero();
    }

    @Test
    void insufficientStockOnOneItem_rejectsWithoutPartialReservation() {
        ProductResponse a = productService.create(new CreateProductRequest("A", new BigDecimal("5.00"), 5));
        ProductResponse b = productService.create(new CreateProductRequest("B", new BigDecimal("7.00"), 0));
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservationService.reserve(new ReserveStockRequest(
                orderId,
                List.of(
                        new ReserveStockRequest.Item(a.id(), 2),
                        new ReserveStockRequest.Item(b.id(), 1)
                )
        ))).isInstanceOf(InsufficientStockException.class);

        assertThat(productService.get(a.id()).availableQuantity()).isEqualTo(5);
        assertThatThrownBy(() -> reservationService.getByOrderId(orderId))
                .hasMessageContaining(orderId.toString());
    }

    @Test
    void duplicateReserveForSameOrder_doesNotDoubleReserve() {
        ProductResponse product = productService.create(
                new CreateProductRequest("Widget", new BigDecimal("3.50"), 2));
        UUID orderId = UUID.randomUUID();
        ReserveStockRequest request = new ReserveStockRequest(
                orderId,
                List.of(new ReserveStockRequest.Item(product.id(), 1))
        );

        var first = reservationService.reserve(request);
        var second = reservationService.reserve(request);

        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(productService.get(product.id()).availableQuantity()).isEqualTo(1);
    }

    @Test
    void lostResponseRetry_isIdempotent() throws ExecutionException, InterruptedException {
        // Same as duplicate reserve: simulates client retry after timeout.
        duplicateReserveForSameOrder_doesNotDoubleReserve();
    }
}
