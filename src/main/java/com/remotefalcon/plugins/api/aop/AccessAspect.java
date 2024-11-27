package com.remotefalcon.plugins.api.aop;

import com.remotefalcon.plugins.api.util.AuthUtil;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
    return null;
  }
}
