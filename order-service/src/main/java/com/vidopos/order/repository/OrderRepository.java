package com.vidopos.order.repository;

import com.vidopos.order.domain.Order;
import com.vidopos.order.domain.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select o from Order o
            where o.status = :status and o.updatedAt < :updatedBefore
            order by o.updatedAt asc
            """)
    List<Order> findPendingOlderThan(
            @Param("status") OrderStatus status,
            @Param("updatedBefore") Instant updatedBefore,
            Pageable pageable
    );
}
