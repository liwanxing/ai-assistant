package com.liwx.learning.mapper;

import com.liwx.learning.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    List<User> selectAll();

    User selectById(@Param("id") Long id);

    int insert(User user);

    int update(User user);

    int deleteById(@Param("id") Long id);
}
