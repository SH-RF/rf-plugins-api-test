package com.remotefalcon.plugins.api.util;

import com.remotefalcon.plugins.api.repository.ShowRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthUtil {
  private final ShowRepository showRepository;

  public String showToken;
  public String getShowTokenFromHeader() {
    HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    String showToken =  request.getHeader("remotetoken") != null ? request.getHeader("remotetoken") : request.getHeader("showtoken");
    this.showToken = showToken;
    return showToken;
  }
}
