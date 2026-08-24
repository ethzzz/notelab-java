package com.notelab.controller;

import com.notelab.common.Passwords;
import com.notelab.dao.CUserDao;
import com.notelab.dao.Db;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B 端管理 C 用户（B/C 拆分阶段1）：前缀 /api/c-admin。
 * 全部要求 B 端登录（AuthUtil）；路由由 PermService 启动自动登记进 perm_routes。
 * 输出统一 Map 出口（RowUtil 键序，日期 yyyy-MM-dd HH:mm:ss）。
 */
@RestController
@RequestMapping("/api/c-admin")
public class CAdminController {

    private static final Pattern USERNAME_RE = Pattern.compile("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}");
    private static final Pattern GROUP_CODE_RE = Pattern.compile("[a-z0-9_]{2,30}");
    private static final Set<String> VALID_STATUS = Set.of("active", "disabled");

    public static class CreateCUserReq { public String username; public String password; public String nickname = ""; public String group_code = "default"; }
    public static class UpdateCUserReq { public String nickname; public String group_code; public String status; }
    public static class PasswordReq { public String password; }
    public static class GroupReq { public String code; public String name; }
    public static class GroupRenameReq { public String name; }

    // ================= C 用户 =================

    /** 列表：q 模糊匹配 username/nickname，group_code 筛选，limit/offset 分页 + total（风格对齐 /api/perm/users） */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(@RequestParam(required = false) String q,
                                                         @RequestParam(name = "group_code", required = false) String groupCode,
                                                         @RequestParam(defaultValue = "20") int limit,
                                                         @RequestParam(defaultValue = "0") long offset,
                                                         HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        int l = Math.min(Math.max(limit, 1), 100);
        long off = Math.max(offset, 0);
        long total = CUserDao.countCUsersFiltered(q, groupCode);
        List<Map<String, Object>> items = CUserDao.listCUsersPaged(q, groupCode, l, off);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("limit", l);
        body.put("offset", off);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody(required = false) CreateCUserReq req,
                                                          HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (req == null || req.username == null || req.password == null) {
            return ResponseEntity.status(400).body(Map.of("error", "用户名和密码不能为空"));
        }
        String username = req.username.trim();
        if (!USERNAME_RE.matcher(username).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "用户名需为 2-20 位字母/数字/下划线/中文"));
        }
        if (req.password.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "密码至少 6 位"));
        }
        String groupCode = req.group_code == null || req.group_code.isBlank() ? "default" : req.group_code.trim();
        if (CUserDao.getGroup(groupCode) == null) {
            return ResponseEntity.status(400).body(Map.of("error", "用户组不存在"));
        }
        if (CUserDao.getCUserByUsername(username) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名已存在"));
        }
        String nickname = req.nickname == null ? "" : req.nickname.trim();
        long uid;
        try {
            uid = CUserDao.createCUser(username, Passwords.hash(req.password), nickname, groupCode);
        } catch (Db.UniqueViolation e) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名已存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "id", uid));
    }

    /** 修改 nickname / group_code / status（字段缺省即不改） */
    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable long id,
                                                          @RequestBody(required = false) UpdateCUserReq req,
                                                          HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        Map<String, Object> target = CUserDao.getCUserById(id);
        if (target == null) return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        String nickname = null;
        String groupCode = null;
        String status = null;
        if (req != null) {
            if (req.nickname != null) nickname = req.nickname.trim();
            if (req.group_code != null && !req.group_code.isBlank()) {
                groupCode = req.group_code.trim();
                if (CUserDao.getGroup(groupCode) == null) {
                    return ResponseEntity.status(400).body(Map.of("error", "用户组不存在"));
                }
            }
            if (req.status != null && !req.status.isBlank()) {
                status = req.status.trim();
                if (!VALID_STATUS.contains(status)) {
                    return ResponseEntity.status(400).body(Map.of("error", "status 仅支持 active / disabled"));
                }
            }
        }
        if (nickname == null && groupCode == null && status == null) {
            return ResponseEntity.status(400).body(Map.of("error", "没有需要修改的字段"));
        }
        CUserDao.updateCUserFields(id, nickname, groupCode, status);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable long id,
                                                             @RequestBody(required = false) PasswordReq req,
                                                             HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (req == null || req.password == null || req.password.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "密码至少 6 位"));
        }
        Map<String, Object> target = CUserDao.getCUserById(id);
        if (target == null) return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        CUserDao.setCUserPassword(id, Passwords.hash(req.password));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        Map<String, Object> target = CUserDao.getCUserById(id);
        if (target == null) return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        CUserDao.deleteCUser(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 用户组 =================

    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> listGroups(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        List<Map<String, Object>> items = CUserDao.listGroupsWithCount();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody(required = false) GroupReq req,
                                                           HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (req == null || req.code == null || req.name == null) {
            return ResponseEntity.status(400).body(Map.of("error", "用户组编码和名称不能为空"));
        }
        String code = req.code.trim().toLowerCase();
        String name = req.name.trim();
        if (!GROUP_CODE_RE.matcher(code).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "用户组编码需为 2-30 位小写字母/数字/下划线"));
        }
        if (name.isEmpty() || name.length() > 20) {
            return ResponseEntity.status(400).body(Map.of("error", "用户组名称需为 1-20 字"));
        }
        if (!CUserDao.createGroup(code, name)) {
            return ResponseEntity.status(409).body(Map.of("error", "用户组编码已存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "code", code));
    }

    @PutMapping("/groups/{code}")
    public ResponseEntity<Map<String, Object>> renameGroup(@PathVariable String code,
                                                           @RequestBody(required = false) GroupRenameReq req,
                                                           HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (CUserDao.getGroup(code) == null) return ResponseEntity.status(404).body(Map.of("error", "用户组不存在"));
        if (req == null || req.name == null || req.name.trim().isEmpty() || req.name.trim().length() > 20) {
            return ResponseEntity.status(400).body(Map.of("error", "用户组名称需为 1-20 字"));
        }
        CUserDao.updateGroupName(code, req.name.trim());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/groups/{code}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable String code, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if ("default".equals(code)) {
            return ResponseEntity.status(400).body(Map.of("error", "默认组 default 不可删除"));
        }
        if (CUserDao.getGroup(code) == null) return ResponseEntity.status(404).body(Map.of("error", "用户组不存在"));
        long n = CUserDao.countCUsersByGroup(code);
        if (n > 0) {
            return ResponseEntity.status(409).body(Map.of("error", "该用户组下仍有 " + n + " 名成员，不可删除"));
        }
        CUserDao.deleteGroup(code);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
