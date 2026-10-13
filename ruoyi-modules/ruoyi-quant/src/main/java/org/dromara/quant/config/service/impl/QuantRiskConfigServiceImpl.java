package org.dromara.quant.config.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.RequiredArgsConstructor;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.config.domain.QuantCredential;
import org.dromara.quant.config.domain.QuantRiskConfig;
import org.dromara.quant.config.domain.bo.QuantRiskConfigBo;
import org.dromara.quant.config.domain.vo.QuantRiskConfigVo;
import org.dromara.quant.config.mapper.QuantCredentialMapper;
import org.dromara.quant.config.mapper.QuantRiskConfigMapper;
import org.dromara.quant.config.service.IQuantRiskConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * QuantRiskConfigService 的默认服务实现。
 */
@Service
@RequiredArgsConstructor
public class QuantRiskConfigServiceImpl implements IQuantRiskConfigService {
    /**
     * 当前领域 Mapper。
     */
    private final QuantRiskConfigMapper mapper;
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;

    /**
     * 按条件分页查询。
     */
    @Override
    public PageResult<QuantRiskConfigVo> queryPageList(QuantRiskConfigBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<QuantRiskConfig> lqw = new LambdaQueryWrapper<QuantRiskConfig>();
        lqw.eq(bo.getCredentialId() != null, QuantRiskConfig::getCredentialId, bo.getCredentialId());
        lqw.orderByDesc(QuantRiskConfig::getCreateTime);
        Page<QuantRiskConfigVo> page = mapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 按主键查询。
     */
    @Override
    public QuantRiskConfigVo queryById(Long id) {
        return mapper.selectVoById(id);
    }

    /**
     * 根据业务入参新增记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuantRiskConfigVo insertByBo(QuantRiskConfigBo bo) {
        validateCredentialAccess(bo.getCredentialId());
        QuantRiskConfig entity = MapstructUtils.convert(bo, QuantRiskConfig.class);
        defaults(entity);
        mapper.insert(entity);
        return MapstructUtils.convert(entity, QuantRiskConfigVo.class);
    }

    /**
     * 根据业务入参更新记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuantRiskConfigVo updateByBo(QuantRiskConfigBo bo) {
        if (mapper.selectById(bo.getId()) == null) {
            throw new ServiceException("风控配置不存在");
        }
        validateCredentialAccess(bo.getCredentialId());
        QuantRiskConfig entity = MapstructUtils.convert(bo, QuantRiskConfig.class);
        defaults(entity);
        mapper.updateById(entity);
        return MapstructUtils.convert(entity, QuantRiskConfigVo.class);
    }

    /**
     * 业务校验后批量删除记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        return mapper.deleteByIds(ids) > 0;
    }

    /**
     * 为风险配置补充缺省值。
     */
    private void defaults(QuantRiskConfig e) {
        if (e.getLiveEnabled() == null) {
            e.setLiveEnabled(false);
        }
        if (e.getMaxOrderNotional() == null) {
            e.setMaxOrderNotional(BigDecimal.ZERO);
        }
        if (e.getDailyLossLimit() == null) {
            e.setDailyLossLimit(BigDecimal.ZERO);
        }
        if (e.getMaxOpenPositions() == null) {
            e.setMaxOpenPositions(1);
        }
        if (e.getMaxOrdersPerMinute() == null) {
            e.setMaxOrdersPerMinute(1);
        }
        if (e.getWebsocketDisconnectAction() == null) {
            e.setWebsocketDisconnectAction("ALERT");
        }
        if (e.getPositionMismatchAction() == null) {
            e.setPositionMismatchAction("HALT");
        }
        if (e.getProtectionFailureAction() == null) {
            e.setProtectionFailureAction("HALT");
        }
        if (e.getWhitelist() == null) {
            e.setWhitelist(false);
        }
    }

    /**
     * 校验对应业务参数和状态。
     */
    private void validateCredentialAccess(Long credentialId) {
        if (credentialId == null) {
            throw new ServiceException("账户不能为空");
        }
        QuantCredential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            throw new ServiceException("凭证不存在");
        }
        Long userId = LoginHelper.getUserId();
        if (!LoginHelper.isSuperAdmin(userId) && !userId.equals(credential.getUserId())) {
            throw new ServiceException("无权配置该交易账户的风控");
        }
    }

}
