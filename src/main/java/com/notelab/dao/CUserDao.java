package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.CUser;
import com.notelab.model.entity.CUserGroup;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

/**
 * C 端用户域 DAO（B/C 拆分阶段1）：c_users / c_user_groups 的访问。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 UserDao / PermDao。
 */
public final class CUserDao {

    private CUserDao() {}

    /** c_users 全列序 */
    private static final String[] ALL_COLS = {"id", "username", "password_hash", "nickname", "group_code", "status", "created_at"};
    /** 列表/管理接口投影列序（不含 password_hash） */
    private static final String[] LIST_COLS = {"id", "username", "nickname", "group_code", "status", "created_at"};
    /** c_user_groups 列序 */
    private static final String[] GROUP_COLS = {"code", "name", "created_at"};
    /** 用户组 + 成员数 列序 */
    private static final String[] GROUP_COUNT_COLS = {"code", "name", "created_at", "member_count"};

    // ================= c_users =================

    public static long createCUser(String username, String passwordHash, String nickname, String groupCode) {
        CUser u = new CUser();
        u.setUsername(username);
        u.setPasswordHash(passwordHash);
        u.setNickname(nickname == null ? "" : nickname);
        u.setGroupCode(groupCode == null || groupCode.isBlank() ? "default" : groupCode);
        u.setStatus("active");
        try {
            DaoSupport.cUser().insert(u);
        } catch (DuplicateKeyException e) {
            throw new Db.UniqueViolation(e);
        }
        return u.getId() == null ? -1 : u.getId();
    }

    public static Map<String, Object> getCUserByUsername(String username) {
        return RowUtil.row(DaoSupport.cUser().selectOne(
                Wrappers.lambdaQuery(CUser.class).eq(CUser::getUsername, username)), ALL_COLS);
    }

    public static Map<String, Object> getCUserById(long id) {
        return RowUtil.row(DaoSupport.cUser().selectById(id), ALL_COLS);
    }

    /** 分页 + 可选关键字（用户名/昵称模糊）与用户组筛选；列序同 LIST_COLS */
    public static List<Map<String, Object>> listCUsersPaged(String q, String groupCode, int limit, long offset) {
        QueryWrapper<CUser> w = new QueryWrapper<>();
        w.select("id", "username", "nickname", "group_code", "status", "created_at");
        appendCUserFilter(w, q, groupCode);
        w.orderByAsc("id");
        Page<CUser> page = new Page<>(offset / limit + 1, limit, false);
        return RowUtil.rows(DaoSupport.cUser().selectPage(page, w).getRecords(), LIST_COLS);
    }

    public static long countCUsersFiltered(String q, String groupCode) {
        QueryWrapper<CUser> w = new QueryWrapper<>();
        appendCUserFilter(w, q, groupCode);
        Long n = DaoSupport.cUser().selectCount(w);
        return n == null ? 0 : n;
    }

    private static void appendCUserFilter(QueryWrapper<CUser> w, String q, String groupCode) {
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            w.and(x -> x.like("username", kw).or().like("nickname", kw));
        }
        if (groupCode != null && !groupCode.isBlank()) {
            w.eq("group_code", groupCode.trim());
        }
    }

    /** 部分字段更新：null 表示不修改 */
    public static void updateCUserFields(long id, String nickname, String groupCode, String status) {
        DaoSupport.cUser().update(Wrappers.lambdaUpdate(CUser.class)
                .eq(CUser::getId, id)
                .set(nickname != null, CUser::getNickname, nickname)
                .set(groupCode != null, CUser::getGroupCode, groupCode)
                .set(status != null, CUser::getStatus, status));
    }

    public static void setCUserPassword(long id, String passwordHash) {
        DaoSupport.cUser().update(Wrappers.lambdaUpdate(CUser.class)
                .eq(CUser::getId, id)
                .set(CUser::getPasswordHash, passwordHash));
    }

    public static void deleteCUser(long id) {
        DaoSupport.cUser().deleteById(id);
    }

    public static long countCUsersByGroup(String groupCode) {
        Long n = DaoSupport.cUser().selectCount(
                Wrappers.lambdaQuery(CUser.class).eq(CUser::getGroupCode, groupCode));
        return n == null ? 0 : n;
    }

    // ================= c_user_groups =================

    public static List<Map<String, Object>> listGroupsWithCount() {
        return RowUtil.norms(DaoSupport.cUserGroup().selectGroupsWithCount(), GROUP_COUNT_COLS);
    }

    public static Map<String, Object> getGroup(String code) {
        return RowUtil.row(DaoSupport.cUserGroup().selectById(code), GROUP_COLS);
    }

    /** 唯一键冲突返回 false（对齐 PermDao.createRole 语义） */
    public static boolean createGroup(String code, String name) {
        CUserGroup g = new CUserGroup();
        g.setCode(code);
        g.setName(name);
        try {
            DaoSupport.cUserGroup().insert(g);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public static void updateGroupName(String code, String name) {
        DaoSupport.cUserGroup().update(Wrappers.lambdaUpdate(CUserGroup.class)
                .eq(CUserGroup::getCode, code)
                .set(CUserGroup::getName, name));
    }

    public static void deleteGroup(String code) {
        DaoSupport.cUserGroup().deleteById(code);
    }
}
