package com.example.oraclebench.model;

/**
 * A row of {@code bench_customers}. {@code id} and {@code createdAt} are
 * database-generated and may be null on the way in.
 */
public record Customer(Long id, String name, String email, String createdAt) {
}
