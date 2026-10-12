package com.notelab.controller;

import com.notelab.common.Passwords;
import com.notelab.dao.CUserDao;
import com.notelab.dao.GameSaveDao;
import com.notelab.service.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端个人中心的自助操作（PRD-P5）：{@code /api/c/me} 下的「我的存档 / 改昵称 / 改密码」。
 *
 * <p>为什么不放进 {@link CAuthController}：那个类的语义是<b>认证</b>（登录/注册/登出/我是谁），
 * 而本类承载的是<b>已登录之后的自助操作</b>。混在一起会让「认证」类变成杂物间。
 *
 * <p>鉴权口径与 {@link GameSaveController} 完全一致：{@link CAuthUtil#user} 为 null 即 401。
 * 路由前缀 {@code /api/c/} 对 B 端 PermGuard 豁免（与既有 C 端接口同款），
 * 启动时会被自动 upsert 进 perm_routes(side='c') 进分配树，无需手工登记。
 *
 * <p>⚠️ 改昵称/改密码**复用现有 DAO**（{@code CUserDao.updateCUserFields} / {@code setCUserPassword}）
 * —— 这两个方法原本只有 B 端超管路径在用，本类只是给用户开了一条改自己的路，<b>不动超管路径</b>。
 */
@RestController
@RequestMapping("/api/c/me")
public class CMeController {

    /** 昵称长度上限（与 c_users.nickname VARCHAR(50) 留足余量；按「字符」而非字节计） */
    private static final int NICKNAME_MAX = 20;
    /** 新密码最短长度（对齐 CAuthController.register 的口径） */
    private static final int PASSWORD_MIN = 6;

    public static class ProfileReq {
        public String nickname;
    }

    public static class PasswordReq {
        public String old_password;
        public String new_password;
    }

    /**
     * 我的云端存档摘要：{@code {"saves":[{game_code,updated_at}]}}。
     *
     * <p>⚠️ 只含**用通用 KV 存档的游戏**（摸金 / 吸血鬼），不含 TRPG / 地牢 / 爬塔 / 雷霆
     * —— 前端卡片标题必须写「云端存档」，不得写成「游玩记录」。见 GameSaveDao#listByUser。
     */
    @GetMapping("/saves")
    public ResponseEntity<Map<String, Object>> saves(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        List<Map<String, Object>> rows = GameSaveDao.listByUser(CAuthUtil.userId(user));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saves", rows);
        return ResponseEntity.ok(body);
    }

    /**
     * 改昵称：{@code {"nickname":"新名"}}。空串是**合法**输入（前端回落到用户名显示），
     * 长度按 trim 后的字符数算，超限 400。
     */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody(required = false) ProfileReq req,
                                                             HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        // 字段缺失（非 null 空串）与「传了空串」必须区分：前者是调用方出错，后者是用户主动清空昵称
        if (req == null || req.nickname == null) {
            return ResponseEntity.status(400).body(Map.of("error", "缺少 nickname 字段"));
        }
        String nickname = req.nickname.trim();
        if (nickname.length() > NICKNAME_MAX) {
            return ResponseEntity.status(400).body(Map.of("error", "昵称最长 " + NICKNAME_MAX + " 个字符"));
        }
        CUserDao.updateCUserFields(CAuthUtil.userId(user), nickname, null, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("nickname", nickname);
        return ResponseEntity.ok(body);
    }

    /**
     * 改密码：{@code {"old_password","new_password"}}。
     *
     * <p>三条规则：原密码必须验证通过（否则 400）、新密码 ≥ 6 位、**改完不吊销当前会话**
     * —— 用户刚改完密码不该被踢出去（单人多设备低频场景，吊销属过度设计）。
     *
     * <p>限流 5 次 / 300s（键按 uid 而非 IP）：这个端点是**唯一能用旧密码爆破**的入口，
     * 不给它限流等于把爆破面开在已登录区域。桶与登录限流独立，互不影响。
     */
    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> updatePassword(@RequestBody(required = false) PasswordReq req,
                                                              HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);
        if (!RateLimit.rateOk("c-pwd:" + uid, 5, 300)) {
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请 5 分钟后再试"));
        }
        if (req == null || req.old_password == null || req.new_password == null) {
            return ResponseEntity.status(400).body(Map.of("error", "缺少 old_password 或 new_password 字段"));
        }
        if (req.new_password.length() < PASSWORD_MIN) {
            return ResponseEntity.status(400).body(Map.of("error", "新密码至少 " + PASSWORD_MIN + " 位"));
        }
        // CAuthUtil.user 返回的是 CUserDao.getCUserById 的全列行（含 password_hash），直接复用不再查库
        Object hash = user.get("password_hash");
        if (hash == null || !Passwords.verify(req.old_password, (String) hash)) {
            return ResponseEntity.status(400).body(Map.of("error", "原密码不正确"));
        }
        CUserDao.setCUserPassword(uid, Passwords.hash(req.new_password));
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
