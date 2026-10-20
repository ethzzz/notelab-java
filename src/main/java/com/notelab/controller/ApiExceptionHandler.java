package com.notelab.controller;

import com.notelab.common.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
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

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * 兜底：任何未处理异常都要在日志里留下带 rid 的堆栈。
     *
     * <p>没有这一层时，Spring 会把异常交给容器打印，日志格式与业务日志不一致，
     * 且响应体是框架默认形态，前端拿不到统一的 {@code {"detail": ...}}。
     *
     * <p>带状态的框架异常（{@link ErrorResponse}，如 404/405/413）保持原状态码返回，
     * 只有真正的未知异常才记 ERROR 并返回 500 —— 避免把「用户传错参数」刷成错误日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unhandled(Exception e) {
        if (e instanceof ErrorResponse er) {
            log.warn("request failed status={} msg={}", er.getStatusCode().value(), e.getMessage());
            String msg = e.getMessage() == null ? "error" : e.getMessage();
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("detail", msg));
        }
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("detail", "Internal Server Error"));
    }
}
