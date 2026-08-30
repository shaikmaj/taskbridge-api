package com.taskbridge.common;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class TenantContextResolver {
  public TenantContext resolve(HttpServletRequest request) {
    try {
      UUID userId=UUID.fromString(required(request,"X-User-Id"));
      UUID organisationId=UUID.fromString(required(request,"X-Organisation-Id"));
      return new TenantContext(userId,organisationId,clientIp(request));
    } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Missing or invalid tenant identity headers"); }
  }
  private String required(HttpServletRequest r,String h){ String v=r.getHeader(h); if(v==null||v.isBlank()) throw new IllegalArgumentException(h+" is required"); return v; }
  private String clientIp(HttpServletRequest r){ String forwarded=r.getHeader("X-Forwarded-For"); return forwarded==null||forwarded.isBlank()?r.getRemoteAddr():forwarded.split(",")[0].trim(); }
}