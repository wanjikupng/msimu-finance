package com.tracom.lipamdogo.controller;

import com.tracom.lipamdogo.dto.ProductRequest;
import com.tracom.lipamdogo.model.Product;
import com.tracom.lipamdogo.repository.ProductRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Catalogue of device models. {@code /devices} is the legacy path kept for
     * the existing frontend and tests; {@code /products} is canonical going
     * forward so that {@code /devices} can eventually mean physical assets.
     */
    @GetMapping({"/devices", "/products"})
    public List<Product> getDevices() {
        return productRepository.findAll();
    }

    @PostMapping({"/devices", "/products"})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Product> createDevice(@RequestBody ProductRequest request) {
        Product product = new Product(
                request.name(),
                request.description(),
                request.fullPrice(),
                request.downPayment(),
                request.dailyAmount(),
                request.paymentPlanName(),
                request.paymentMode()
        );

        Product saved = productRepository.save(product);
        return ResponseEntity.ok(saved);
    }
}
