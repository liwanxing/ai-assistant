package com.liwx.learning.service;

import com.liwx.learning.entity.User;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UserService {

    private final List<User> users = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public UserService() {
        // 初始化两条假数据
        users.add(new User(idGenerator.getAndIncrement(), "liwanxing", "liwanxing@example.com"));
        users.add(new User(idGenerator.getAndIncrement(), "zhangsan", "zhangsan@example.com"));
    }

    public List<User> getAllUsers() {
        return users;
    }

    public User getUserById(Long id) {
        return users.stream()
                .filter(u -> u.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public User createUser(User user) {
        user.setId(idGenerator.getAndIncrement());
        users.add(user);
        return user;
    }

    public User updateUser(Long id, User user) {
        User existing = getUserById(id);
        if (existing == null) {
            return null;
        }
        existing.setUsername(user.getUsername());
        existing.setEmail(user.getEmail());
        return existing;
    }

    public boolean deleteUser(Long id) {
        return users.removeIf(u -> u.getId().equals(id));
    }
}
