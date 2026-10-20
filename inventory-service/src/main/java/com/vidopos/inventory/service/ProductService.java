package com.vidopos.inventory.service;

import com.vidopos.inventory.api.dto.CreateProductRequest;
import com.vidopos.inventory.api.dto.ProductResponse;
import com.vidopos.inventory.domain.Product;
import com.vidopos.inventory.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product product = new Product(
                UUID.randomUUID(),
                request.name().trim(),
                request.price(),
                request.availableQuantity()
        );
        productRepository.save(product);
        log.info("Created product id={} name={} qty={}", product.getId(), product.getName(), product.getAvailableQty());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return ProductResponse.from(product);
    }
}
