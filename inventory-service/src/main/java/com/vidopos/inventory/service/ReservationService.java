package com.vidopos.inventory.service;

import com.vidopos.inventory.api.dto.ReservationResponse;
import com.vidopos.inventory.api.dto.ReserveStockRequest;
import com.vidopos.inventory.domain.Product;
import com.vidopos.inventory.domain.Reservation;
import com.vidopos.inventory.domain.ReservationItem;
import com.vidopos.inventory.domain.ReservationStatus;
import com.vidopos.inventory.repository.ProductRepository;
import com.vidopos.inventory.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;

    public ReservationService(ProductRepository productRepository, ReservationRepository reservationRepository) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * Atomically reserves stock for all items or none.
     * Idempotent on orderId: repeating the same reservation returns the existing one
     * without decreasing stock again.
     */
    @Transactional
    public ReservationResponse reserve(ReserveStockRequest request) {
        Optional<Reservation> existing = reservationRepository.findByOrderId(request.orderId());
        if (existing.isPresent()) {
            Reservation reservation = existing.get();
            log.info("Idempotent reservation hit orderId={} status={}", request.orderId(), reservation.getStatus());
            if (reservation.getStatus() == ReservationStatus.RELEASED) {
                throw new IllegalStateException("Reservation for order " + request.orderId() + " was already released");
            }
            return ReservationResponse.from(reservation);
        }

        Map<UUID, Integer> requestedByProduct = aggregateQuantities(request);
        List<UUID> productIds = requestedByProduct.keySet().stream().sorted().toList();

        // Lock products in stable id order to avoid deadlocks under concurrency.
        List<Product> products = productRepository.findAllByIdForUpdate(productIds);
        if (products.size() != productIds.size()) {
            Set<UUID> found = products.stream().map(Product::getId).collect(Collectors.toSet());
            UUID missing = productIds.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new ProductNotFoundException(missing);
        }

        // Re-check after locks so a concurrent identical orderId does not double-reserve.
        Optional<Reservation> raced = reservationRepository.findByOrderId(request.orderId());
        if (raced.isPresent()) {
            log.info("Idempotent reservation race resolved orderId={}", request.orderId());
            return ReservationResponse.from(raced.get());
        }

        Map<UUID, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        for (Map.Entry<UUID, Integer> entry : requestedByProduct.entrySet()) {
            Product product = productMap.get(entry.getKey());
            if (product.getAvailableQty() < entry.getValue()) {
                throw new InsufficientStockException(product.getId());
            }
        }

        Reservation reservation = new Reservation(UUID.randomUUID(), request.orderId());
        for (Map.Entry<UUID, Integer> entry : requestedByProduct.entrySet()) {
            Product product = productMap.get(entry.getKey());
            int qty = entry.getValue();
            product.decreaseStock(qty);
            reservation.addItem(new ReservationItem(
                    UUID.randomUUID(),
                    product.getId(),
                    qty,
                    product.getPrice()
            ));
        }

        productRepository.saveAll(products);
        reservationRepository.save(reservation);
        log.info("Reserved stock orderId={} reservationId={} items={}",
                request.orderId(), reservation.getId(), requestedByProduct.size());
        return ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationResponse getByOrderId(UUID orderId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ReservationNotFoundException(orderId));
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse release(UUID orderId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ReservationNotFoundException(orderId));

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            log.info("Idempotent release hit orderId={}", orderId);
            return ReservationResponse.from(reservation);
        }

        List<UUID> productIds = reservation.getItems().stream()
                .map(ReservationItem::getProductId)
                .sorted()
                .toList();
        List<Product> products = productRepository.findAllByIdForUpdate(productIds);
        Map<UUID, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        for (ReservationItem item : reservation.getItems()) {
            productMap.get(item.getProductId()).increaseStock(item.getQuantity());
        }

        reservation.release();
        productRepository.saveAll(products);
        reservationRepository.save(reservation);
        log.info("Released reservation orderId={}", orderId);
        return ReservationResponse.from(reservation);
    }

    private static Map<UUID, Integer> aggregateQuantities(ReserveStockRequest request) {
        Map<UUID, Integer> aggregated = new LinkedHashMap<>();
        for (ReserveStockRequest.Item item : request.items()) {
            aggregated.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return aggregated;
    }
}
