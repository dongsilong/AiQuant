package org.dromara.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 量化模块自动配置：注册模块配置属性、启用运行时调度，并提供行情共享使用的轻量调度线程池。
 */
@Configuration
@EnableScheduling
public class QuantAutoConfiguration {

    /**
     * 注册量化模块配置属性，并固定 Bean 名称为 quantProperties。
     * <p>部分定时任务通过 SpEL 直接引用该名称，因此不能依赖
     * {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
     * 的默认 Bean 命名规则。
     */
    @Bean(name = "quantProperties")
    @ConfigurationProperties(prefix = "quant")
    public QuantProperties quantProperties() {
        return new QuantProperties();
    }

    /**
     * 创建量化模块共享调度线程池。
     * <p>由 Spring 容器统一管理生命周期，关闭应用时调用 shutdown，
     * 避免后台定时线程阻止进程退出。调度池不承担订单执行幂等性或分布式租约职责。
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService quantScheduledExecutorService() {
        return Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "quant-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }
}
