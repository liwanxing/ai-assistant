package com.liwx.learning.user.service;

import com.liwx.learning.common.Assert;
import com.liwx.learning.common.ResultCode;
import com.liwx.learning.user.dto.UserCreateDTO;
import com.liwx.learning.user.dto.UserUpdateDTO;
import com.liwx.learning.user.entity.User;
import com.liwx.learning.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

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
        // 密码加密存储：存的是 BCrypt 哈希值，不是明文
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
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
