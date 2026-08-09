package com.liwx.learning.service;

import com.liwx.learning.common.Assert;
import com.liwx.learning.common.ResultCode;
import com.liwx.learning.dto.UserCreateDTO;
import com.liwx.learning.dto.UserUpdateDTO;
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
        User user = userMapper.selectById(id);
        Assert.notNull(user, ResultCode.NOT_FOUND);
        return user;
    }

    public User createUser(UserCreateDTO dto) {
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(dto.getPassword());
        user.setNickname(dto.getNickname());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        userMapper.insert(user);
        return user;
    }

    public User updateUser(Long id, UserUpdateDTO dto) {
        User existing = userMapper.selectById(id);
        Assert.notNull(existing, ResultCode.NOT_FOUND);
        existing.setNickname(dto.getNickname());
        existing.setEmail(dto.getEmail());
        existing.setPhone(dto.getPhone());
        userMapper.update(existing);
        return userMapper.selectById(id);
    }

    public boolean deleteUser(Long id) {
        User existing = userMapper.selectById(id);
        Assert.notNull(existing, ResultCode.NOT_FOUND);
        return userMapper.deleteById(id) > 0;
    }
}
