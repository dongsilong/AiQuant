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
import org.dromara.common.web.core.BaseController;
import org.dromara.quant.config.domain.bo.QuantSymbolBo;
import org.dromara.quant.config.domain.vo.QuantSymbolVo;
import org.dromara.quant.config.service.IQuantSymbolFavoriteService;
import org.dromara.quant.config.service.IQuantSymbolService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 交易对接口控制器。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/symbol")
public class QuantSymbolController extends BaseController {
    /**
     * 当前领域服务。
     */
    private final IQuantSymbolService service;
    /**
     * 交易对收藏 Mapper。
     */
    private final IQuantSymbolFavoriteService favoriteService;

    /**
     * 查询列表数据。
     */
    @SaCheckPermission("quant:symbol:list")
    @GetMapping("/list")
    public R<PageResult<QuantSymbolVo>> list(QuantSymbolBo bo, PageQuery q) {
        return R.ok(service.queryPageList(bo, q));
    }

    /**
     * 按主键查询交易对详情。
     */
    @SaCheckPermission("quant:symbol:query")
    @GetMapping("/{id}")
    public R<QuantSymbolVo> getInfo(@NotNull @PathVariable Long id) {
        return R.ok(service.queryById(id));
    }

    /**
     * 新增交易对。
     */
    @SaCheckPermission("quant:symbol:add")
    @Log(title = "量化交易对", businessType = BusinessType.INSERT)
    @PostMapping
    public R<QuantSymbolVo> add(@Validated(AddGroup.class) @RequestBody QuantSymbolBo bo) {
        return R.ok(service.insertByBo(bo));
    }

    /**
     * 修改交易对。
     */
    @SaCheckPermission("quant:symbol:edit")
    @Log(title = "量化交易对", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<QuantSymbolVo> edit(@Validated(EditGroup.class) @RequestBody QuantSymbolBo bo) {
        return R.ok(service.updateByBo(bo));
    }

    /**
     * 删除交易对记录。
     */
    @SaCheckPermission("quant:symbol:remove")
    @Log(title = "量化交易对", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty @PathVariable Long[] ids) {
        return toAjax(service.deleteWithValidByIds(List.of(ids)));
    }

    /**
     * 触发数据同步。
     */
    @SaCheckPermission("quant:symbol:sync")
    @Log(title = "量化交易对", businessType = BusinessType.OTHER)
    @PostMapping("/sync")
    public R<Integer> sync() {
        return R.ok(service.syncFromExchange());
    }

    /**
     * 新增或取消交易对收藏。
     */
    @SaCheckPermission("quant:symbol:list")
    @PostMapping("/{id}/favorite")
    public R<Void> favorite(@PathVariable Long id, @RequestBody Boolean favorite) {
        favoriteService.favorite(id, favorite);
        return R.ok();
    }

    /**
     * FavoriteRequest API 请求对象。
     *
     * @param favorite 当前用户是否收藏该交易对
     */
    private record FavoriteRequest(Boolean favorite) {
    }
}
