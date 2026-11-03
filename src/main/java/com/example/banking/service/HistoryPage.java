package com.example.banking.service;

import com.example.banking.model.BankTransaction;
import java.util.List;

/** One page of transaction history plus what the menu needs to show "Page 2 of 5". */
public record HistoryPage(List<BankTransaction> items, int page, int pageSize, int totalItems) {

    public int totalPages() {
        return Math.max(1, (totalItems + pageSize - 1) / pageSize);   // rounds up
    }

    public boolean hasNext() {
        return page + 1 < totalPages();
    }

    public boolean hasPrevious() {
        return page > 0;
    }
}