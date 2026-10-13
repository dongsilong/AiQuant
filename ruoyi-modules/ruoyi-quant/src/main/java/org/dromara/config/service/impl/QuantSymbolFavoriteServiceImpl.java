package org.dromara.config.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.config.domain.QuantSymbolFavorite;
import org.dromara.config.mapper.QuantSymbolFavoriteMapper;
import org.dromara.config.service.IQuantSymbolFavoriteService;
import org.springframework.stereotype.Service;

/**
 * QuantSymbolService 的默认服务实现。
 */
@Service
@RequiredArgsConstructor
public class QuantSymbolFavoriteServiceImpl implements IQuantSymbolFavoriteService {

    /**
     * 交易对收藏 Mapper。
     */
    private final QuantSymbolFavoriteMapper favoriteMapper;

    @Override
    public void favorite(Long id, Boolean favorite) {
        LambdaQueryWrapper<QuantSymbolFavorite> lqw = new LambdaQueryWrapper<QuantSymbolFavorite>();
        lqw.eq(QuantSymbolFavorite::getSymbolId, id).eq(QuantSymbolFavorite::getUserId, LoginHelper.getUserId());
        if (Boolean.TRUE.equals(favorite)) {
            if (favoriteMapper.selectCount(lqw) == 0) {
                QuantSymbolFavorite item = new QuantSymbolFavorite();
                item.setSymbolId(id);
                item.setUserId(LoginHelper.getUserId());
                favoriteMapper.insert(item);
            }
        } else {
            favoriteMapper.delete(lqw);
        }
    }
}
