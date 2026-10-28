package com.example.banking.fakes;

import com.example.banking.dao.LoginAuditDAO;
import com.example.banking.model.LoginOutcome;
import java.util.ArrayList;
import java.util.List;

/** Test double: remembers every audit entry so tests can check what was recorded. */
public class InMemoryLoginAuditDAO implements LoginAuditDAO {

    public record Entry(Long customerId, String username, LoginOutcome outcome) { }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void record(Long customerId, String usernameAttempted, LoginOutcome outcome) {
        entries.add(new Entry(customerId, usernameAttempted, outcome));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public Entry last() {
        return entries.get(entries.size() - 1);
    }
}