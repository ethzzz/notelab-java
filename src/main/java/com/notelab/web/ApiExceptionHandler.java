package com.notelab.web;

import com.notelab.JsonUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 未匹配路径/方法、非法请求体等，输出与 FastAPI 一致风格的 {"detail": ...}。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Not Found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("detail", "Method Not Allowed"));
    }

    /** 请求体缺失/非法 JSON：对齐 FastAPI 的 422 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badBody(HttpMessageNotReadableException e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "value_error");
        detail.put("loc", List.of("body"));
        detail.put("msg", "Invalid request body");
        return ResponseEntity.status(422).body(Map.of("detail", List.of(detail)));
    }

    /** 路径参数类型不匹配（如 /api/conversations/abc）：对齐 FastAPI 的 422 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "value_error");
        detail.put("loc", List.of("path", e.getName()));
        detail.put("msg", "value is not a valid integer");
        return ResponseEntity.status(422).body(Map.of("detail", List.of(detail)));
    }
}
