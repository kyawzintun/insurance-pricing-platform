package com.insurance.platform.auth.controller;

import com.insurance.platform.auth.dto.CustomerPage;
import com.insurance.platform.auth.dto.CustomerResponse;
import com.insurance.platform.auth.dto.CustomerStatusCommand;
import com.insurance.platform.auth.service.CustomerAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/customers")
public class CustomerAdminController {
    private final CustomerAdminService service;

    public CustomerAdminController(CustomerAdminService service) {
        this.service = service;
    }

    @GetMapping
    public CustomerPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/disable")
    public CustomerResponse disable(@PathVariable UUID id, @RequestBody(required = false) CustomerStatusCommand command) {
        return service.setEnabled(id, false);
    }

    @PatchMapping("/{id}/enable")
    public CustomerResponse enable(@PathVariable UUID id, @RequestBody(required = false) CustomerStatusCommand command) {
        return service.setEnabled(id, true);
    }
}
