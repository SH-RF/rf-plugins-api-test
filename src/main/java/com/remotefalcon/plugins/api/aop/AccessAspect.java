package com.remotefalcon.plugins.api.aop;

import com.remotefalcon.plugins.api.util.AuthUtil;
import com.remotefalcon.library.enums.StatusResponse;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Aspect
@Component
public class AccessAspect {
  @Autowired
  private AuthUtil authUtil;

  @Around("@annotation(com.remotefalcon.plugins.api.aop.RequiresAccess)")
  public Object isJwtValid(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
    String remoteToken = this.authUtil.getShowTokenFromHeader();
    if(!StringUtils.isEmpty(remoteToken)) {
      return proceedingJoinPoint.proceed();
    }
    throw new RuntimeException(StatusResponse.INVALID_JWT.name());
  }
}
