package org.dromara.quant.risk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.RequiredArgsConstructor;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.dromara.quant.risk.domain.bo.QuantRiskConfigBo;
import org.dromara.quant.risk.domain.vo.QuantRiskConfigVo;
import org.dromara.quant.risk.service.IQuantRiskConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * QuantRiskConfigController 接口控制器。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/risk")
public class QuantRiskConfigController extends BaseController {
    /**
     * 当前领域服务。
     */
    private final IQuantRiskConfigService service;

    /**
     * 查询列表数据。
     */
    @SaCheckPermission("quant:risk:list")
    @GetMapping("/list")
    public R<PageResult<QuantRiskConfigVo>> list(QuantRiskConfigBo bo, PageQuery pageQuery) {
        return R.ok(service.queryPageList(bo, pageQuery));
    }

    /**
     * 按主键查询风险配置详情。
     */
    @SaCheckPermission("quant:risk:query")
    @GetMapping("/{id}")
    public R<QuantRiskConfigVo> getInfo(@NotNull @PathVariable Long id) {
        return R.ok(service.queryById(id));
    }

    /**
     * 新增风险配置。
     */
    @SaCheckPermission("quant:risk:add")
    @Log(title = "量化风控", businessType = BusinessType.INSERT)
    @PostMapping
    public R<QuantRiskConfigVo> add(@Validated(AddGroup.class) @RequestBody QuantRiskConfigBo bo) {
        return R.ok(service.insertByBo(bo));
    }

    /**
     * 修改风险配置。
     */
    @SaCheckPermission("quant:risk:edit")
    @Log(title = "量化风控", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<QuantRiskConfigVo> edit(@Validated(EditGroup.class) @RequestBody QuantRiskConfigBo bo) {
        return R.ok(service.updateByBo(bo));
    }

    /**
     * 删除风险配置记录。
     */
    @SaCheckPermission("quant:risk:remove")
    @Log(title = "量化风控", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(service.deleteWithValidByIds(List.of(ids)));
    }
}
