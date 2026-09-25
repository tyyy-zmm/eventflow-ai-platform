package com.hmdp.upgrade;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem p) {
        var response=ResponseEntity.status(p.status);
        if(p.status==429 || p.status==503) response.header("Retry-After","1");
        return response.body(Map.of("error",p.code));
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> unavailable(DataAccessException e) {
        return ResponseEntity.status(503).header("Retry-After","1").body(Map.of("error","DATABASE_UNAVAILABLE"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error","INVALID_BODY"));
    }
}
