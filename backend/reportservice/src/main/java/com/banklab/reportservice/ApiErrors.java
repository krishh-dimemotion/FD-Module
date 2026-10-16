package com.banklab.reportservice;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException error) { return ResponseEntity.status(error.status).body(Map.of("code",error.code,"message",error.getMessage())); }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception error) { return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_ERROR","message","Missing or invalid query parameter")); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable(DataAccessException error) { return ResponseEntity.status(503).body(Map.of("code","REPORT_DATA_UNAVAILABLE","message","Reporting database or business clock is unavailable")); }
}
