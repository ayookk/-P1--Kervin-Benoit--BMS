package com.example.banking.model;

import java.time.LocalDateTime;

public class Customer {

    private Long customerId;
    private String fullName;
    private String email;
    private String phone;
    private String passwordHash;
    private CustomerStatus status;
    private LocalDateTime createdAt;

    // For registering a NEW customer (DB generates id and createdAt)
    public Customer(String fullName, String email, String phone, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.status = CustomerStatus.ACTIVE;
    }

    // For loading an EXISTING customer from the database
    public Customer(Long customerId, String fullName, String email, String phone,
                    String passwordHash, CustomerStatus status, LocalDateTime createdAt) {
        this.customerId = customerId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getPasswordHash() { return passwordHash; }

    public CustomerStatus getStatus() { return status; }
    public void setStatus(CustomerStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        // passwordHash deliberately left out so it never ends up in logs
        return "Customer{id=" + customerId + ", name='" + fullName + "', email='" + email
                + "', status=" + status + "}";
    }
}