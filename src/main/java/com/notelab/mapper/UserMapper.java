package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.User;
import org.apache.ibatis.annotations.Update;

/** users 表 Mapper。 */
public interface UserMapper extends BaseMapper<User> {

    /** 首次自举：把最早注册的用户提升为超级管理员（子查询原样保留，MySQL 不允许 UPDATE 同表直接子查询） */
    @Update("UPDATE users SET role='super_admin' WHERE id=(SELECT id FROM (SELECT MIN(id) AS id FROM users) t)")
    int promoteFirstUserToAdmin();
}
