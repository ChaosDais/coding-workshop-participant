package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.Role;
import com.acme.models.User;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for the PostgreSQL repository, with the same uniqueness and archiving behaviour. */
public class InMemoryUserRepository implements UserRepository {
    private final Map<String, Credentials> users = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(User user, String passwordHash) {
        if (users.containsKey(user.id()) || findByEmail(user.email()).isPresent()) {
            throw new ConflictException("A user with email " + user.email() + " or id " + user.id()
                    + " already exists");
        }
        users.put(user.id(), new Credentials(user, passwordHash));
    }

    @Override
    public synchronized boolean insertIfEmpty(User user, String passwordHash) {
        if (!users.isEmpty()) {
            return false;
        }
        insert(user, passwordHash);
        return true;
    }

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(users.get(id)).map(Credentials::user);
    }

    @Override
    public Optional<Credentials> findByEmail(String email) {
        return users.values().stream().filter(stored -> stored.user().email().equals(email)).findFirst();
    }

    @Override
    public List<User> findAll(boolean archived) {
        return users.values().stream().map(Credentials::user)
                .filter(user -> user.archived() == archived)
                .sorted(Comparator.comparing(User::name).thenComparing(User::email))
                .toList();
    }

    @Override
    public synchronized void update(User user) {
        Optional<Credentials> sameEmail = findByEmail(user.email());
        if (sameEmail.isPresent() && !sameEmail.get().user().id().equals(user.id())) {
            throw new ConflictException("A user with email " + user.email() + " or id " + user.id()
                    + " already exists");
        }
        Credentials stored = users.get(user.id());
        users.put(user.id(), new Credentials(user.withArchived(stored.user().archived()), stored.passwordHash()));
    }

    @Override
    public void setPasswordHash(String id, String passwordHash) {
        users.computeIfPresent(id, (key, stored) -> new Credentials(stored.user(), passwordHash));
    }

    @Override
    public boolean archive(String id) {
        return setArchived(id, true);
    }

    @Override
    public boolean restore(String id) {
        return setArchived(id, false);
    }

    @Override
    public int countActive(Role role) {
        return (int) findAll(false).stream().filter(user -> user.role() == role).count();
    }

    @Override
    public int countActiveEmployeesOf(String managerId) {
        return (int) findAll(false).stream().filter(user -> managerId.equals(user.managerId())).count();
    }

    private synchronized boolean setArchived(String id, boolean archived) {
        Credentials stored = users.get(id);
        if (stored == null || stored.user().archived() == archived) {
            return false;
        }
        users.put(id, new Credentials(stored.user().withArchived(archived), stored.passwordHash()));
        return true;
    }
}
