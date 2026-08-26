package com.notelab.dao;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.InviteCode;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

/**
 * 邀请码域 DAO（C 端注册邀请码）：invite_codes 的访问。
 * 静态门面 + Map 出口（RowUtil 键序），风格对齐 CUserDao / PermDao。
 */
public final class InviteCodeDao {

    private InviteCodeDao() {}

    /** invite_codes 全列序 */
    private static final String[] ALL_COLS = {"id", "code", "max_uses", "used_count", "revoked", "remark", "created_by", "created_at"};

    /** 新增一个邀请码；唯一键冲突返回 false（由调用方重试换码） */
    public static boolean create(String code, int maxUses, String remark, Long createdBy) {
        InviteCode c = new InviteCode();
        c.setCode(code);
        c.setMaxUses(maxUses);
        c.setUsedCount(0);
        c.setRevoked(0);
        c.setRemark(remark == null ? "" : remark);
        c.setCreatedBy(createdBy);
        try {
            DaoSupport.inviteCode().insert(c);
        } catch (DuplicateKeyException e) {
            return false;
        }
        return true;
    }

    public static Map<String, Object> getByCode(String code) {
        return RowUtil.row(DaoSupport.inviteCode().selectOne(
                Wrappers.lambdaQuery(InviteCode.class).eq(InviteCode::getCode, code)), ALL_COLS);
    }

    public static Map<String, Object> getById(long id) {
        return RowUtil.row(DaoSupport.inviteCode().selectById(id), ALL_COLS);
    }

    /** 分页 + 可选关键字（码/备注模糊）；按 id 倒序（新码在前） */
    public static List<Map<String, Object>> listPaged(String q, int limit, long offset) {
        QueryWrapper<InviteCode> w = new QueryWrapper<>();
        appendFilter(w, q);
        w.orderByDesc("id");
        Page<InviteCode> page = new Page<>(offset / limit + 1, limit, false);
        return RowUtil.rows(DaoSupport.inviteCode().selectPage(page, w).getRecords(), ALL_COLS);
    }

    public static long countFiltered(String q) {
        QueryWrapper<InviteCode> w = new QueryWrapper<>();
        appendFilter(w, q);
        Long n = DaoSupport.inviteCode().selectCount(w);
        return n == null ? 0 : n;
    }

    private static void appendFilter(QueryWrapper<InviteCode> w, String q) {
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            w.and(x -> x.like("code", kw).or().like("remark", kw));
        }
    }

    /**
     * 原子核销一次：仅当未作废且未达上限时 used_count+1，返回受影响行数（1=成功）。
     * 并发下也能保证不超过 max_uses。
     */
    public static int consumeOne(String code) {
        return DaoSupport.inviteCode().update(null, Wrappers.lambdaUpdate(InviteCode.class)
                .setSql("used_count = used_count + 1")
                .eq(InviteCode::getCode, code)
                .eq(InviteCode::getRevoked, 0)
                .apply("used_count < max_uses"));
    }

    /** 注册最终失败（如用户名冲突）时把核销的一次退回 */
    public static int releaseOne(String code) {
        return DaoSupport.inviteCode().update(null, Wrappers.lambdaUpdate(InviteCode.class)
                .setSql("used_count = used_count - 1")
                .eq(InviteCode::getCode, code)
                .apply("used_count > 0"));
    }

    /** 作废：已核销次数不变，剩余次数不可再用 */
    public static void revoke(long id) {
        DaoSupport.inviteCode().update(null, Wrappers.lambdaUpdate(InviteCode.class)
                .eq(InviteCode::getId, id)
                .set(InviteCode::getRevoked, 1));
    }
}
