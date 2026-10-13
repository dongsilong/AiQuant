package org.dromara.quant.config.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.quant.config.strategy.StrategyPluginRegistry;
import org.dromara.quant.config.strategy.metadata.StrategyDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 已注册策略插件查询接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/strategy")
public class QuantStrategyPluginController {
    /**
     * 策略插件注册表。
     */
    private final StrategyPluginRegistry registry;

    /**
     * 查询已注册策略插件。
     */
    @SaCheckPermission("quant:strategy:list")
    @GetMapping("/plugins")
    public R<List<StrategyDescriptor>> plugins() {
        return R.ok(registry.descriptors());
    }
}
