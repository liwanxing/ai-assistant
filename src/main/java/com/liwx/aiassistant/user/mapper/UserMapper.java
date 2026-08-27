package com.liwx.aiassistant.user.mapper;

import com.liwx.aiassistant.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    List<User> selectAll();

    User selectById(@Param("id") Long id);

    /**
     * 根据用户名查询用户（登录时用）
     */
    User selectByUsername(@Param("username") String username);

    int insert(User user);

    int update(User user);

    int deleteById(@Param("id") Long id);
}
