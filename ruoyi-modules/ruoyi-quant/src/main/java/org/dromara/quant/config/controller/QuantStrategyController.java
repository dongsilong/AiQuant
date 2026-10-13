package org.dromara.quant.config.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.quant.config.domain.bo.QuantStrategyBo;
import org.dromara.quant.config.domain.vo.QuantStrategyVo;
import org.dromara.quant.config.service.impl.QuantStrategyServiceImpl;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 策略接口控制器。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/strategy")
public class QuantStrategyController {
    /**
     * 当前领域服务。
     */
    private final QuantStrategyServiceImpl service;

    /**
     * 查询列表数据。
     */
    @SaCheckPermission("quant:strategy:list")
    @GetMapping("/list")
    public R<PageResult<QuantStrategyVo>> list(PageQuery pageQuery) {
        return R.ok(service.queryPage(pageQuery));
    }

    /**
     * 获取对应业务数据。
     */
    @SaCheckPermission("quant:strategy:query")
    @GetMapping("/{id}")
    public R<QuantStrategyVo> get(@NotNull @PathVariable Long id) {
        return R.ok(service.get(id));
    }

    /**
     * 新增策略。
     */
    @SaCheckPermission("quant:strategy:add")
    @PostMapping
    public R<QuantStrategyVo> add(@RequestBody QuantStrategyBo strategy) {
        return R.ok(service.insert(strategy));
    }

    /**
     * 修改策略。
     */
    @SaCheckPermission("quant:strategy:edit")
    @PutMapping
    public R<QuantStrategyVo> edit(@RequestBody QuantStrategyBo strategy) {
        return R.ok(service.update(strategy));
    }

    /**
     * 删除策略记录。
     */
    @SaCheckPermission("quant:strategy:remove")
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        service.delete(List.of(ids));
        return R.ok();
    }

    /**
     * 启动组件或底层订阅。
     */
    @SaCheckPermission("quant:strategy:edit")
    @PostMapping("/{id}/start")
    public R<Boolean> start(@PathVariable Long id) {
        return R.ok(service.start(id));
    }

    /**
     * 停止组件或订阅并释放资源。
     */
    @SaCheckPermission("quant:strategy:edit")
    @PostMapping("/{id}/stop")
    public R<Boolean> stop(@PathVariable Long id) {
        return R.ok(service.stop(id));
    }

    /**
     * 启动前检查账户、交易对和最终运行参数。
     */
    //    @SaCheckPermission("quant:strategy:query")
    //    @GetMapping("/{id}/preflight")
    //    public R<StrategyPreflightResult> preflight(@PathVariable Long id) {
    //        return R.ok(service.preflight(id));
    //    }
}
