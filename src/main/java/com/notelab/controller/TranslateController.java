package com.notelab.controller;

import com.notelab.dao.TranslateDao;
import com.notelab.service.TranslateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端「每日英语翻译练习」：前缀 /api/translate，全部经 CAuthUtil（notelab_c_session）。
 *  - GET  /today                 当天激活组 + 分阶句子 + 当前用户既有提交（回填）
 *  - POST /submit                逐句提交英文译文 → 大模型判分 → upsert（同人同日同句覆盖）
 *  - GET  /history?date=YYYY-MM-DD 当前用户某天提交列表
 * 判分/上游异常一律优雅降级为明确 error（不冒泡 500）。
 */
@RestController
@RequestMapping("/api/translate")
public class TranslateController {

    /** 用户译文长度上限 */
    private static final int MAX_EN_LEN = 2000;

    public static class SubmitReq {
        public Long sentence_id;
        public String en_text;
    }

    // ================= 当日内容 =================

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        try {
            return ResponseEntity.ok(TranslateService.todayPayload(CAuthUtil.userId(user), LocalDate.now()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "读取今日练习失败，请稍后重试"));
        }
    }

    // ================= 逐句提交 + 判分 =================

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody(required = false) SubmitReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        if (req == null || req.sentence_id == null) {
            return ResponseEntity.status(422).body(Map.of("error", "sentence_id 必填"));
        }
        String en = req.en_text == null ? "" : req.en_text.trim();
        if (en.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "译文不能为空"));
        if (en.length() > MAX_EN_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "译文最多 " + MAX_EN_LEN + " 字"));
        }
        LocalDate today = LocalDate.now();
        // 句子必须存在且属于当天激活组（否则视为非法提交）
        Map<String, Object> group = TranslateDao.getActivatedGroup(today);
        if (group == null) return ResponseEntity.status(400).body(Map.of("error", "今日暂无练习内容"));
        Map<String, Object> sentence = TranslateDao.getSentence(req.sentence_id);
        if (sentence == null) return ResponseEntity.status(400).body(Map.of("error", "句子不存在"));
        long gid = ((Number) group.get("id")).longValue();
        Object sGroup = sentence.get("group_id");
        if (sGroup == null || ((Number) sGroup).longValue() != gid) {
            return ResponseEntity.status(400).body(Map.of("error", "该句子不属于今日练习"));
        }
        String zh = String.valueOf(sentence.get("zh_text"));
        String ref = sentence.get("ref_en") == null ? "" : String.valueOf(sentence.get("ref_en"));

        TranslateService.Grade grade;
        try {
            grade = TranslateService.grade(zh, en, ref);
        } catch (TranslateService.GradeException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "判分失败，请重试"));
        }

        long uid = CAuthUtil.userId(user);
        try {
            // upsert：靠 uk_user_sentence_date 唯一键，重复提交同句覆盖旧判分结果
            TranslateDao.upsertSubmission(uid, req.sentence_id, gid, today, en,
                    grade.accurate ? 1 : 0, grade.score, grade.corrected, grade.explanation,
                    grade.errorsJson, grade.model);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存判分结果失败，请稍后重试"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sentence_id", req.sentence_id);
        body.put("date", today.toString());
        body.put("en_text", en);
        body.put("accurate", grade.accurate);
        body.put("score", grade.score);
        body.put("corrected", grade.corrected);
        body.put("explanation", grade.explanation);
        body.put("errors", grade.errors);
        body.put("model", grade.model);
        return ResponseEntity.ok(body);
    }

    // ================= 历史 =================

    /** 某天提交列表（date 缺省为今天；非法日期 400） */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam(required = false) String date,
                                                       HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(400).body(Map.of("error", "日期格式应为 YYYY-MM-DD"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            for (Map<String, Object> sub : TranslateDao.listSubmissionsByDate(CAuthUtil.userId(user), day)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sub.get("id"));
                m.put("sentence_id", sub.get("sentence_id"));
                m.put("group_id", sub.get("group_id"));
                m.put("group_title", sub.get("group_title"));
                m.put("tier", sub.get("tier"));
                m.put("sort_order", sub.get("sort_order"));
                m.put("zh_text", sub.get("zh_text"));
                m.put("en_text", sub.get("en_text"));
                Object accurate = sub.get("accurate");
                m.put("accurate", accurate == null ? null : ((Number) accurate).intValue() == 1);
                m.put("score", sub.get("score"));
                m.put("corrected", sub.get("corrected"));
                m.put("explanation", sub.get("explanation"));
                m.put("errors", TranslateService.parseErrorsArray(
                        sub.get("errors_json") == null ? null : String.valueOf(sub.get("errors_json"))));
                m.put("updated_at", sub.get("updated_at"));
                items.add(m);
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "读取历史提交失败，请稍后重试"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", day.toString());
        body.put("items", items);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }
}
