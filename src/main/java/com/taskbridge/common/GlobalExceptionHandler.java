package com.taskbridge.common;
import java.time.Instant;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(NotFoundException.class) ResponseEntity<ApiError> notFound(NotFoundException ex){ return error(HttpStatus.NOT_FOUND,"NOT_FOUND",ex.getMessage(),List.of()); }
  @ExceptionHandler(ForbiddenException.class) ResponseEntity<ApiError> forbidden(ForbiddenException ex){ return error(HttpStatus.FORBIDDEN,"FORBIDDEN",ex.getMessage(),List.of()); }
  @ExceptionHandler(ConflictException.class) ResponseEntity<ApiError> conflict(ConflictException ex){ return error(HttpStatus.CONFLICT,"CONFLICT",ex.getMessage(),List.of()); }
  @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> invalid(MethodArgumentNotValidException ex){
    var details=ex.getBindingResult().getFieldErrors().stream().map(e->e.getField()+": "+e.getDefaultMessage()).toList();
    return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Request validation failed",details);
  }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiError> illegal(IllegalArgumentException ex){ return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST",ex.getMessage(),List.of()); }
  private ResponseEntity<ApiError> error(HttpStatus s,String c,String m,List<String>d){ return ResponseEntity.status(s).body(new ApiError(Instant.now(),s.value(),c,m,d)); }
}