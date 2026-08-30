package com.taskbridge.notifications.controller;
import com.taskbridge.common.*;
import com.taskbridge.notifications.dto.NotificationResponse;
import com.taskbridge.notifications.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/notifications")
public class NotificationController {
 private final NotificationService service; private final TenantContextResolver resolver;
 public NotificationController(NotificationService service,TenantContextResolver resolver){this.service=service;this.resolver=resolver;}
 @GetMapping("/{userId}") List<NotificationResponse> unread(@PathVariable UUID userId,HttpServletRequest req){return service.unread(userId,resolver.resolve(req));}
 @PatchMapping("/{id}/read") NotificationResponse markRead(@PathVariable UUID id,HttpServletRequest req){return service.markRead(id,resolver.resolve(req));}
}