package com.liwx.learning.service;

import com.liwx.learning.entity.User;
import com.liwx.learning.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<User> getAllUsers() {
        return userMapper.selectAll();
    }

    public User getUserById(Long id) {
        return userMapper.selectById(id);
    }

    public User createUser(User user) {
        userMapper.insert(user);
        return user;
    }

    public User updateUser(Long id, User user) {
        User existing = userMapper.selectById(id);
        if (existing == null) {
            return null;
        }
        user.setId(id);
        userMapper.update(user);
        return userMapper.selectById(id);
    }

    public boolean deleteUser(Long id) {
        return userMapper.deleteById(id) > 0;
    }
}
