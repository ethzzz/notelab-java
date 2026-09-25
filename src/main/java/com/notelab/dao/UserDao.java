package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.User;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

/** 用户域 DAO：users 表的账户/角色相关访问（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class UserDao {

    private UserDao() {}

    /** 原 SELECT * 列序（现网 users 表列序：email/role 为后置补列） */
    private static final String[] ALL_COLS = {"id", "username", "password_hash", "created_at", "email", "role"};
    /** 原列表/分页 SQL 投影列序（不含 password_hash） */
    private static final String[] LIST_COLS = {"id", "username", "email", "role", "created_at"};

    public static long createUser(String username, String passwordHash, String email) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordHash);
        u.setEmail(email);
        try {
            DaoSupport.user().insert(u);
        } catch (DuplicateKeyException e) {
            throw new Db.UniqueViolation(e);
        }
        return u.getId() == null ? -1 : u.getId();
    }

    public static Map<String, Object> getUserByUsername(String username) {
        return RowUtil.row(DaoSupport.user().selectOne(
                Wrappers.lambdaQuery(User.class).eq(User::getUsername, username)), ALL_COLS);
    }

    public static Map<String, Object> getUserByEmail(String email) {
        return RowUtil.row(DaoSupport.user().selectOne(
                Wrappers.lambdaQuery(User.class).eq(User::getEmail, email)), ALL_COLS);
    }

    public static Map<String, Object> getUserById(long id) {
        return RowUtil.row(DaoSupport.user().selectById(id), ALL_COLS);
    }

    public static List<Map<String, Object>> listUsersForPerm() {
        return RowUtil.rows(DaoSupport.user().selectList(
                Wrappers.lambdaQuery(User.class).orderByAsc(User::getId)), LIST_COLS);
    }

    /** 按 id 批量取账户：批量改角色前一次性核对「哪些存在、哪些当前是超管」，避免逐条查询的 N+1 */
    public static List<Map<String, Object>> listUsersByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return RowUtil.rows(DaoSupport.user().selectList(
                Wrappers.lambdaQuery(User.class).in(User::getId, ids).orderByAsc(User::getId)), LIST_COLS);
    }

    /** 分页 + 可选关键字（用户名/邮箱模糊）与角色筛选（selectMaps 改走 Page + select 指定列，输出 key 集合与原 SQL 一致） */
    public static List<Map<String, Object>> listUsersPaged(String q, String role, int limit, long offset) {
        QueryWrapper<User> w = new QueryWrapper<>();
        w.select("id", "username", "email", "role", "created_at");
        appendUserFilter(w, q, role);
        w.orderByAsc("id");
        // 调用方 offset 恒为 (page-1)*limit；Page 不执行额外 COUNT（count 由 countUsersFiltered 单独提供）
        Page<User> page = new Page<>(offset / limit + 1, limit, false);
        return RowUtil.rows(DaoSupport.user().selectPage(page, w).getRecords(), LIST_COLS);
    }

    public static long countUsersFiltered(String q, String role) {
        QueryWrapper<User> w = new QueryWrapper<>();
        appendUserFilter(w, q, role);
        Long n = DaoSupport.user().selectCount(w);
        return n == null ? 0 : n;
    }

    private static void appendUserFilter(QueryWrapper<User> w, String q, String role) {
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            w.and(x -> x.like("username", kw).or().like("email", kw));
        }
        if (role != null && !role.isBlank()) {
            w.eq("role", role.trim());
        }
    }

    public static void setUserRole(long uid, String role) {
        DaoSupport.user().update(Wrappers.lambdaUpdate(User.class)
                .eq(User::getId, uid)
                .set(User::getRole, role));
    }

    /** 批量把账户的角色组设为同一值（一条 UPDATE ... WHERE id IN (...)，供「批量加入用户组」用） */
    public static void setUsersRole(List<Long> ids, String role) {
        if (ids == null || ids.isEmpty()) return;
        DaoSupport.user().update(Wrappers.lambdaUpdate(User.class)
                .in(User::getId, ids)
                .set(User::getRole, role));
    }

    public static void setUserPassword(long uid, String passwordHash) {
        DaoSupport.user().update(Wrappers.lambdaUpdate(User.class)
                .eq(User::getId, uid)
                .set(User::getPasswordHash, passwordHash));
    }

    public static long countSuperAdmins() {
        try {
            return DaoSupport.user().selectCount(
                    Wrappers.lambdaQuery(User.class).eq(User::getRole, "super_admin"));
        } catch (DataAccessException e) {
            return 0;
        }
    }

    /** 首次自举：若尚无超级管理员，把最早注册的用户提升为超级管理员（原 SQL 由 Mapper 注解保留） */
    public static void promoteFirstUserToAdmin() {
        DaoSupport.user().promoteFirstUserToAdmin();
    }

    public static long createUserWithRole(String username, String passwordHash, String email, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordHash);
        u.setEmail(email);
        u.setRole(role);
        try {
            DaoSupport.user().insert(u);
        } catch (DuplicateKeyException e) {
            throw new Db.UniqueViolation(e);
        }
        return u.getId() == null ? -1 : u.getId();
    }

    public static long countUsersByRole(String role) {
        Long n = DaoSupport.user().selectCount(
                Wrappers.lambdaQuery(User.class).eq(User::getRole, role));
        return n == null ? 0 : n;
    }

    public static void migrateUsersToRole(String fromRole, String toRole) {
        DaoSupport.user().update(Wrappers.lambdaUpdate(User.class)
                .eq(User::getRole, fromRole)
                .set(User::getRole, toRole));
    }

    public static void updateUserInfo(long id, String username, String email) {
        DaoSupport.user().update(Wrappers.lambdaUpdate(User.class)
                .eq(User::getId, id)
                .set(User::getUsername, username)
                .set(User::getEmail, email));
    }

    public static void deleteUser(long id) {
        DaoSupport.user().deleteById(id);
    }
}
