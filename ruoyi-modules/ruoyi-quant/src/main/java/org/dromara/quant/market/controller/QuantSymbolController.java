package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

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
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.quant.market.domain.QuantSymbolFavorite;
import org.dromara.quant.market.domain.bo.QuantSymbolBo;
import org.dromara.quant.market.domain.vo.QuantSymbolVo;
import org.dromara.quant.market.mapper.QuantSymbolFavoriteMapper;
import org.dromara.quant.market.service.IQuantSymbolService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * QuantSymbolController 接口控制器。
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
    private final QuantSymbolFavoriteMapper favoriteMapper;

    /**
     * 查询列表数据。
     */
    @SaCheckPermission(value = {"quant:symbol:list", "quant:terminal:list"}, mode = SaMode.OR)
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
    @SaCheckPermission("quant:symbol:edit")
    @PostMapping("/{id}/favorite")
    public R<Void> favorite(@PathVariable Long id, @RequestBody FavoriteRequest request) {
        LambdaQueryWrapper<QuantSymbolFavorite> wrapper =
            new LambdaQueryWrapper<QuantSymbolFavorite>().eq(QuantSymbolFavorite::getSymbolId, id).eq(QuantSymbolFavorite::getUserId, LoginHelper.getUserId());
        if (Boolean.TRUE.equals(request.favorite())) {
            if (favoriteMapper.selectCount(wrapper) == 0) {
                QuantSymbolFavorite item = new QuantSymbolFavorite();
                item.setSymbolId(id);
                item.setUserId(LoginHelper.getUserId());
                favoriteMapper.insert(item);
            }
        } else {
            favoriteMapper.delete(wrapper);
        }
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
