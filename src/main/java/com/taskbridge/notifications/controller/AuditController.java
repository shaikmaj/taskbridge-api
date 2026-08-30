package com.taskbridge.notifications.controller;
import com.taskbridge.common.*;
import com.taskbridge.notifications.dto.*;
import com.taskbridge.notifications.model.EventType;
import com.taskbridge.notifications.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/audit")
public class AuditController {
 private final AuditService service; private final TenantContextResolver resolver;
 public AuditController(AuditService service,TenantContextResolver resolver){this.service=service;this.resolver=resolver;}
 @PostMapping ResponseEntity<AuditResponse> record(@Valid @RequestBody CreateAuditRequest body,HttpServletRequest req){return ResponseEntity.status(HttpStatus.CREATED).body(service.record(body,resolver.resolve(req)));}
 @GetMapping("/{projectId}") List<AuditResponse> history(@PathVariable UUID projectId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,@RequestParam(required=false) EventType eventType,HttpServletRequest req){return service.history(projectId,from,to,eventType,resolver.resolve(req));}
}