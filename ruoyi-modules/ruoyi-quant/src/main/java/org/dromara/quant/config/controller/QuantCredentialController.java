package org.dromara.quant.config.controller;

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
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.quant.config.domain.bo.QuantCredentialBo;
import org.dromara.quant.config.domain.vo.QuantCredentialVo;
import org.dromara.quant.config.service.IQuantCredentialService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 凭证管理接口。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/credential")
public class QuantCredentialController extends BaseController {
    /**
     * 交易凭证服务。
     */
    private final IQuantCredentialService credentialService;

    /**
     * 查询列表数据。
     */
    @SaCheckPermission("quant:credential:list")
    @GetMapping("/list")
    public R<PageResult<QuantCredentialVo>> list(QuantCredentialBo bo, PageQuery pageQuery) {
        return R.ok(credentialService.queryPageList(bo, pageQuery));
    }

    /**
     * 按主键查询交易凭证详情。
     */
    @SaCheckPermission("quant:credential:query")
    @GetMapping("/{id}")
    public R<QuantCredentialVo> getInfo(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return R.ok(credentialService.queryById(id));
    }

    /**
     * 新增交易凭证。
     */
    @SaCheckPermission("quant:credential:add")
    @Log(title = "量化凭证", businessType = BusinessType.INSERT, excludeParamNames = {"apiKey", "secretKey"})
    @RepeatSubmit
    @PostMapping
    public R<QuantCredentialVo> add(@Validated(AddGroup.class) @RequestBody QuantCredentialBo bo) {
        return R.ok(credentialService.insertByBo(bo));
    }

    /**
     * 修改交易凭证。
     */
    @SaCheckPermission("quant:credential:edit")
    @Log(title = "量化凭证", businessType = BusinessType.UPDATE, excludeParamNames = {"apiKey", "secretKey"})
    @RepeatSubmit
    @PutMapping
    public R<QuantCredentialVo> edit(@Validated(EditGroup.class) @RequestBody QuantCredentialBo bo) {
        return R.ok(credentialService.updateByBo(bo));
    }

    /**
     * 删除交易凭证记录。
     */
    @SaCheckPermission("quant:credential:remove")
    @Log(title = "量化凭证", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空") @PathVariable Long[] ids) {
        return toAjax(credentialService.deleteWithValidByIds(List.of(ids)));
    }

    /**
     * 修改启停状态。
     */
    @SaCheckPermission("quant:credential:edit")
    @Log(title = "量化凭证", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/changeStatus")
    public R<Void> changeStatus(@RequestParam("id") Long id, @RequestParam("status") String status) {
        credentialService.changeStatus(id, status);
        return R.ok();
    }

    /**
     * 测试交易所连通性。
     */
    @SaCheckPermission("quant:credential:test")
    @Log(title = "量化凭证", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/test")
    public R<Void> test(@NotNull @PathVariable Long id) {
        credentialService.testConnectivity(id);
        return R.ok();
    }
}
