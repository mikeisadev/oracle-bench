package com.example.oraclebench.web;

import com.example.oraclebench.service.CustomerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/crud/customer")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** JSON body for create/update; both fields optional (create generates fictitious data). */
    public record CustomerRequest(String name, String email) {
    }

    /** Inserts a fictitious customer. If name/email are supplied in the body they are used verbatim. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) CustomerRequest body) {
        String name = (body != null && body.name() != null) ? body.name() : randomName();
        String email = (body != null && body.email() != null) ? body.email() : randomEmail();
        try {
            return ResponseEntity.ok(toResponse(customerService.create(name, email)));
        } catch (SQLException e) {
            return error(e);
        }
    }

    @GetMapping
    public ResponseEntity<?> read(@RequestParam String email) {
        try {
            return ResponseEntity.ok(toResponse(customerService.readByEmail(email)));
        } catch (SQLException e) {
            return error(e);
        }
    }

    /** Updates the name of the customer(s) matching {@code email}. */
    @PutMapping
    public ResponseEntity<?> update(@RequestParam(required = false) String email,
                                    @RequestBody(required = false) CustomerRequest body) {
        String targetEmail = email != null ? email : (body != null ? body.email() : null);
        String newName = body != null ? body.name() : null;
        if (targetEmail == null || newName == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "email and name are required"));
        }
        try {
            return ResponseEntity.ok(toResponse(customerService.updateNameByEmail(targetEmail, newName)));
        } catch (SQLException e) {
            return error(e);
        }
    }

    @DeleteMapping
    public ResponseEntity<?> delete(@RequestParam String email) {
        try {
            return ResponseEntity.ok(toResponse(customerService.deleteByEmail(email)));
        } catch (SQLException e) {
            return error(e);
        }
    }

    private Map<String, Object> toResponse(CustomerService.CrudResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation", r.operation());
        out.put("affected", r.affected());
        out.put("elapsedMs", r.elapsedMs());
        out.put("customers", r.customers());
        return out;
    }

    private ResponseEntity<?> error(SQLException e) {
        return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
    }

    private String randomName() {
        String[] first = {"Mario", "Luca", "Giulia", "Anna", "Marco", "Sara", "Paolo", "Elena"};
        String[] last = {"Rossi", "Bianchi", "Verdi", "Russo", "Ferrari", "Esposito", "Romano", "Colombo"};
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return first[rnd.nextInt(first.length)] + " " + last[rnd.nextInt(last.length)];
    }

    private String randomEmail() {
        return "cliente" + ThreadLocalRandom.current().nextInt(100_000, 999_999) + "@example.com";
    }
}
