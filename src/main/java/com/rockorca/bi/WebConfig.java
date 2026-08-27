package com.rockorca.bi;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;
  private final ToolPermissionInterceptor toolPermissionInterceptor;

  public WebConfig(
      AuthInterceptor authInterceptor, ToolPermissionInterceptor toolPermissionInterceptor) {
    this.authInterceptor = authInterceptor;
    this.toolPermissionInterceptor = toolPermissionInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // 页面和静态资源不进入后端，只保护 JSON API。
    registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    registry.addInterceptor(toolPermissionInterceptor).addPathPatterns("/api/**");
  }
}
