package com.vidopos.inventory.api;

import com.vidopos.inventory.api.dto.ReservationResponse;
import com.vidopos.inventory.api.dto.ReserveStockRequest;
import com.vidopos.inventory.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@Valid @RequestBody ReserveStockRequest request) {
        return reservationService.reserve(request);
    }

    @GetMapping("/by-order/{orderId}")
    public ReservationResponse getByOrder(@PathVariable UUID orderId) {
        return reservationService.getByOrderId(orderId);
    }

    @PostMapping("/by-order/{orderId}/release")
    public ReservationResponse release(@PathVariable UUID orderId) {
        return reservationService.release(orderId);
    }
}
