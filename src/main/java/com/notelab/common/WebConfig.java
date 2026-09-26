package com.notelab.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS：等价 Python 版
 * allow_origin_regex=r"https?://.*:3000", allow_credentials=True, allow_methods=["*"], allow_headers=["*"]
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://*:3000", "https://*:3000")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 接口级权限拦截器（默认拒绝），只作用于 /api/**。
     * 注意：这是一层**门禁**，各 Controller 自己的登录/isSuperAdmin 判断全部保留（双保险）；
     * C 端接口（/api/c/**）与未登录请求在 ApiPermInterceptor 内部另有豁免。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiPermInterceptor())
                .addPathPatterns("/api/**");
    }
}
