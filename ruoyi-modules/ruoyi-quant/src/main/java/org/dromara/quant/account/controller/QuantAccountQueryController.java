package org.dromara.quant.account.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.domain.vo.QuantTradeVo;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.config.domain.QuantCredential;
import org.dromara.config.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 账户、持仓、委托实时查询门面，以及本地成交账本同步入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant")
public class QuantAccountQueryController {
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * 成交 Mapper。
     */
    private final QuantTradeMapper tradeMapper;

    /**
     * 查询指定凭证的账户资金快照。
     */
    @SaCheckPermission("quant:account:list")
    @GetMapping("/account/{credentialId}")
    public R<ExchangeGateway.AccountSnapshot> account(@PathVariable Long credentialId) {
        return R.ok(gateway.queryAccount(credential(credentialId)));
    }

    /**
     * 查询指定凭证的当前持仓。
     */
    @SaCheckPermission("quant:position:list")
    @GetMapping("/position/{credentialId}")
    public R<List<ExchangeGateway.PositionSnapshot>> positions(@PathVariable Long credentialId, @RequestParam(required = false) String symbol) {
        return R.ok(gateway.queryPositions(credential(credentialId), symbol));
    }

    /**
     * 查询指定凭证的当前/历史订单。
     */
    @SaCheckPermission("quant:order:list")
    @GetMapping("/order/{credentialId}")
    public R<List<ExchangeGateway.OrderSnapshot>> orders(@PathVariable Long credentialId, @RequestParam(required = false) String symbol) {
        return R.ok(gateway.queryOrders(credential(credentialId), symbol));
    }

    /**
     * 只读查询交易所当前条件单，包含止盈止损，复用账户访问权限校验。
     */
    @SaCheckPermission("quant:order:list")
    @GetMapping("/order/{credentialId}/protection")
    public R<List<ExchangeGateway.ProtectionOrderSnapshot>> protectionOrders(@PathVariable Long credentialId, @RequestParam(required = false) String symbol) {
        return R.ok(gateway.queryProtectionOrders(credential(credentialId), symbol));
    }

    /**
     * 分页查询指定凭证的成交记录。
     */
    @SaCheckPermission("quant:trade:list")
    @GetMapping("/trade/{credentialId}/page")
    public R<PageResult<QuantTradeVo>> tradePage(@PathVariable Long credentialId, @RequestParam(required = false) String symbol, @RequestParam(required = false) String side,
                                                 @RequestParam(required = false) String from, @RequestParam(required = false) String to, PageQuery pageQuery) {
        QuantCredential credential = credentialEntity(credentialId);
        LambdaQueryWrapper<QuantTrade> lqw = new LambdaQueryWrapper<QuantTrade>();
        lqw.eq(QuantTrade::getCredentialId, credentialId);
        lqw.eq(StringUtils.isNotBlank(symbol), QuantTrade::getSymbol, symbol);
        lqw.eq(StringUtils.isNotBlank(side), QuantTrade::getSide, side);
        lqw.ge(StringUtils.isNotBlank(from), QuantTrade::getTradeTime, StringUtils.isBlank(from) ? null : LocalDate.parse(from).atStartOfDay());
        lqw.lt(StringUtils.isNotBlank(to), QuantTrade::getTradeTime, StringUtils.isBlank(to) ? null : LocalDate.parse(to).plusDays(1).atStartOfDay());
        lqw.orderByDesc(QuantTrade::getTradeTime).orderByDesc(QuantTrade::getId);
        Page<QuantTrade> page = tradeMapper.selectPage(pageQuery.build(), lqw);
        List<QuantTradeVo> rows = page.getRecords().stream().map(item -> toVo(item, credential)).toList();
        return R.ok(PageResult.build(rows, page.getTotal()));
    }

    /**
     * 同步交易所成交事实。
     */
    @SaCheckPermission("quant:trade:sync")
    @PostMapping("/trade/{credentialId}/sync")
    public R<Integer> syncTrades(@PathVariable Long credentialId, @RequestParam String symbol) {
        QuantCredential credential = credentialEntity(credentialId);
        List<ExchangeGateway.TradeSnapshot> snapshots = gateway.queryTrades(credential(credentialId), symbol, new ExchangeGateway.TimeRange(null, null));
        int count = 0;
        for (ExchangeGateway.TradeSnapshot snapshot : snapshots) {
            QuantTrade entity = tradeMapper.selectOne(new LambdaQueryWrapper<QuantTrade>().eq(QuantTrade::getCredentialId, credentialId).eq(QuantTrade::getExchangeTradeId,
                snapshot.tradeId()));
            if (entity == null) {
                entity = new QuantTrade();
                entity.setCredentialId(credentialId);
                entity.setExchangeTradeId(snapshot.tradeId());
            }
            entity.setSymbol(snapshot.symbol());
            entity.setExchangeOrderId(snapshot.orderId());
            entity.setSide(snapshot.side());
            entity.setQuantity(snapshot.quantity());
            entity.setPrice(snapshot.price());
            entity.setCommission(snapshot.commission());
            entity.setRealizedPnl(snapshot.realizedPnl());
            entity.setFundingFee(java.math.BigDecimal.ZERO);
            entity.setTradeTime(LocalDateTime.ofInstant(snapshot.time(), ZoneOffset.UTC));
            if (entity.getId() == null) {
                tradeMapper.insert(entity);
            } else {
                tradeMapper.updateById(entity);
            }
            count++;
        }
        return R.ok(count);
    }

    private ExchangeGateway.Credential credential(Long id) {
        QuantCredential entity = credentialEntity(id);
        return new ExchangeGateway.Credential(entity.getApiKey(), entity.getSecretKey(), entity.getSignatureType(), entity.getRunMode());
    }

    private QuantCredential credentialEntity(Long id) {
        QuantCredential entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("凭证不存在");
        }
        if (!LoginHelper.isSuperAdmin(LoginHelper.getUserId()) && !LoginHelper.getUserId().equals(entity.getUserId())) {
            throw new ServiceException("无权访问该交易账户");
        }
        return entity;
    }

    private QuantTradeVo toVo(QuantTrade entity, QuantCredential credential) {
        QuantTradeVo vo = new QuantTradeVo();
        vo.setId(entity.getId());
        vo.setCredentialId(entity.getCredentialId());
        vo.setCustomerName(credential.getAccountName());
        vo.setUserId(credential.getUserId());
        vo.setSymbol(entity.getSymbol());
        vo.setSide(entity.getSide());
        vo.setQuantity(entity.getQuantity());
        vo.setPrice(entity.getPrice());
        vo.setCommission(entity.getCommission());
        vo.setRealizedPnl(entity.getRealizedPnl());
        vo.setTradeTime(entity.getTradeTime());
        vo.setExchangeTradeId(entity.getExchangeTradeId());
        vo.setExchangeOrderId(entity.getExchangeOrderId());
        return vo;
    }
}
