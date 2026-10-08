package com.notelab.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS：等价 Python 版（allow_credentials=True, allow_methods=["*"], allow_headers=["*"]）。
 * 允许源：旧 dev 端口 *:3000，以及生产域名 haolo.cloud（含 www 与服务器 IP 直访）。
 * ⚠️ 生产域名为 https://haolo.cloud（443），绝不能只留 *:3000 —— 否则浏览器/带 Origin 的请求会被
 *    Spring CORS 拒成 403 "Invalid CORS request"（登录页正是因此点不了登录）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // ⚠️ 刻意**不用**通配符源（如 http://*:3000）：与 allowCredentials(true) 组合后，
                // 任何能控制「某台主机的 3000 端口」的人都能让受害浏览器带着 Cookie 发跨域请求。
                // SameSite=Lax 已经挡住这类请求的绝大多数，但配置本身不该把门留着 —— 本地开发
                // 只需要 localhost / 127.0.0.1，显式列出即可。
                .allowedOriginPatterns(
                        "http://localhost:3000", "https://localhost:3000",
                        "http://127.0.0.1:3000", "https://127.0.0.1:3000",
                        "https://haolo.cloud", "https://www.haolo.cloud",
                        "http://117.72.32.87", "https://117.72.32.87")
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
