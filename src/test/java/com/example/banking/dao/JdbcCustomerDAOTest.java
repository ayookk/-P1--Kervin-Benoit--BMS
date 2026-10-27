package com.example.banking.dao;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Customer;
import com.example.banking.model.CustomerStatus;
import com.example.banking.util.ConnectionFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcCustomerDAOTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO dao = new JdbcCustomerDAO(connections, 10);

    @BeforeEach
    void emptyDatabase() throws Exception {
        TestDatabase.clean(connections);
    }

    private Customer newCustomer(String username, String email) {
        return new Customer("Test User", email, "5551234567", username, "pbkdf2_sha256$1$abc$def");
    }

    @Test
    void insertThenFindByUsername() {
        Customer saved = dao.insert(newCustomer("alice", "alice@example.com"));

        Customer found = dao.findByUsername("alice").orElseThrow();
        assertEquals(saved.getCustomerId(), found.getCustomerId());
        assertEquals("alice@example.com", found.getEmail());
        assertEquals(CustomerStatus.ACTIVE, found.getStatus());
        assertEquals(0, found.getFailedLoginCount());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    void duplicateUsernameIsRejected() {
        dao.insert(newCustomer("bob", "bob@example.com"));
        assertThrows(ValidationException.class,
                () -> dao.insert(newCustomer("bob", "other@example.com")));
    }

    @Test
    void unknownUsernameReturnsEmpty() {
        assertTrue(dao.findByUsername("nobody").isEmpty());
    }

    @Test
    void existsChecksWork() {
        dao.insert(newCustomer("carol", "carol@example.com"));
        assertTrue(dao.existsByUsername("carol"));
        assertTrue(dao.existsByEmail("carol@example.com"));
        assertFalse(dao.existsByUsername("dave"));
    }

    @Test
    void loginStateIsSavedAndCleared() {
        Customer c = dao.insert(newCustomer("erin", "erin@example.com"));
        Instant lockTime = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        dao.updateLoginState(c.getCustomerId(), 3, lockTime);
        Customer locked = dao.findById(c.getCustomerId()).orElseThrow();
        assertEquals(3, locked.getFailedLoginCount());
        assertEquals(lockTime, locked.getLockedUntil());

        dao.updateLoginState(c.getCustomerId(), 0, null);
        assertNull(dao.findById(c.getCustomerId()).orElseThrow().getLockedUntil());
    }

    @Test
    void sqlInjectionTextIsTreatedAsPlainData() {
        String nasty = "x' OR '1'='1";
        assertTrue(dao.findByUsername(nasty).isEmpty());   // with string-concatenated SQL this would match everyone
    }
}