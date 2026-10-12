package org.dromara.quant.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.paper.domain.QuantPaperAccount;
import org.dromara.quant.paper.mapper.QuantPaperAccountMapper;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PAPER 虚拟撮合账本。PAPER 不访问交易所私有账户，也不发送真实订单。
 */
@Slf4j
@Service
public class PaperTradingService {
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    /**
     * 账户相关 Mapper。
     */
    private final QuantPaperAccountMapper accountMapper;
    /**
     * 持仓 Mapper。
     */
    private final QuantPositionMapper positionMapper;
    /**
     * 执行记录 Mapper。
     */
    private final QuantExecutionRecordMapper executionRecordMapper;
    /**
     * 用于执行需要数据库原子语义的 SQL 操作。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 PaperTradingService 实例并初始化其依赖。
     */
    public PaperTradingService(QuantProperties properties, QuantPaperAccountMapper accountMapper, QuantPositionMapper positionMapper,
                               QuantExecutionRecordMapper executionRecordMapper, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.executionRecordMapper = executionRecordMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 返回正数值；非法时使用默认值。
     */
    private static BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    /**
     * 确保 PAPER 账户存在。
     */
    public QuantPaperAccount ensure(Long credentialId) {
        if (!properties.getPaper().isEnabled()) {
            throw new IllegalStateException("PAPER 模式已禁用");
        }
        QuantPaperAccount account = find(credentialId);
        if (account != null) {
            return account;
        }
        QuantPaperAccount entity = new QuantPaperAccount();
        entity.setCredentialId(credentialId);
        entity.setQuoteAsset(properties.getPaper().getQuoteAsset());
        BigDecimal initial = positive(properties.getPaper().getInitialBalance(), new BigDecimal("1000"));
        entity.setInitialBalance(initial);
        entity.setBalance(initial);
        entity.setAvailableBalance(initial);
        entity.setRealizedPnl(BigDecimal.ZERO);
        entity.setUnrealizedPnl(BigDecimal.ZERO);
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            accountMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException ex) {
            return find(credentialId);
        }
    }

    /**
     * 查询符合条件的记录。
     */
    public QuantPaperAccount find(Long credentialId) {
        return accountMapper.selectOne(new LambdaQueryWrapper<QuantPaperAccount>().eq(QuantPaperAccount::getCredentialId, credentialId));
    }

    /**
     * 查询 PAPER 账户可用余额。
     */
    public BigDecimal availableBalance(Long credentialId) {
        QuantPaperAccount account = ensure(credentialId);
        return account.getAvailableBalance() == null ? BigDecimal.ZERO : account.getAvailableBalance();
    }

    /**
     * 在 PAPER 开仓前预留保证金。
     */
    @Transactional
    public void reserveMargin(Long credentialId, BigDecimal margin) {
        if (margin == null || margin.signum() <= 0) {
            throw new IllegalArgumentException("PAPER 保证金必须大于零");
        }
        ensure(credentialId);
        QuantPaperAccount account = jdbcTemplate.queryForObject("SELECT * FROM quant_paper_account WHERE credential_id=? FOR UPDATE", (rs, rowNum) -> {
            QuantPaperAccount a = new QuantPaperAccount();
            a.setId(rs.getLong("id"));
            a.setCredentialId(rs.getLong("credential_id"));
            a.setAvailableBalance(rs.getBigDecimal("available_balance"));
            a.setBalance(rs.getBigDecimal("balance"));
            a.setRealizedPnl(rs.getBigDecimal("realized_pnl"));
            a.setUnrealizedPnl(rs.getBigDecimal("unrealized_pnl"));
            return a;
        }, credentialId);
        if (account.getAvailableBalance().compareTo(margin) < 0) {
            throw new IllegalStateException("PAPER 可用余额不足");
        }
        jdbcTemplate.update("UPDATE quant_paper_account SET available_balance=available_balance-?, updated_at=NOW() WHERE id=?", margin, account.getId());
    }

    /**
     * 处理 PAPER 开仓失败并回滚预留保证金。
     */
    @Transactional
    public boolean failPendingOpen(Long credentialId, String idempotencyKey, BigDecimal reservedMargin, String message) {
        QuantExecutionRecord record = lockExecution(credentialId, idempotencyKey);
        if (!"PENDING".equalsIgnoreCase(record.getExecutionStatus())) {
            return false;
        }
        ensure(credentialId);
        QuantPaperAccount account = jdbcTemplate.queryForObject("SELECT * FROM quant_paper_account WHERE credential_id=? FOR UPDATE", (rs, rowNum) -> {
            QuantPaperAccount a = new QuantPaperAccount();
            a.setId(rs.getLong("id"));
            a.setCredentialId(rs.getLong("credential_id"));
            a.setAvailableBalance(rs.getBigDecimal("available_balance"));
            return a;
        }, credentialId);
        BigDecimal refund = reservedMargin == null || reservedMargin.signum() <= 0 ? BigDecimal.ZERO : reservedMargin;
        if (refund.signum() > 0) {
            jdbcTemplate.update("UPDATE quant_paper_account SET available_balance=available_balance+?, updated_at=NOW() WHERE id=?", refund, account.getId());
        }
        record.setRecoveryAttempts((record.getRecoveryAttempts() == null ? 0 : record.getRecoveryAttempts()) + 1);
        record.setLastRecoveryAt(LocalDateTime.now());
        record.setExecutionStatus("FAILED");
        record.setErrorMessage(message == null ? "PAPER 执行未确认" : message);
        record.setUpdatedAt(record.getLastRecoveryAt());
        executionRecordMapper.updateById(record);
        return true;
    }

    /**
     * 记录 PAPER 开仓成交和持仓。
     */
    @Transactional
    public QuantPosition recordOpenFill(Long credentialId, String idempotencyKey, String symbol, String direction, BigDecimal quantity, BigDecimal entryPrice, int leverage) {
        QuantExecutionRecord record = lockExecution(credentialId, idempotencyKey);
        if (Boolean.TRUE.equals(record.getPaperSettled())) {
            return positionMapper.selectOne(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credentialId).eq(QuantPosition::getSymbol, symbol).eq(QuantPosition::getPositionSide, direction));
        }
        QuantPosition position = positionMapper.selectOne(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credentialId).eq(QuantPosition::getSymbol,
            symbol).eq(QuantPosition::getPositionSide, direction));
        BigDecimal margin = entryPrice.multiply(quantity).divide(BigDecimal.valueOf(Math.max(1, leverage)), 16, RoundingMode.HALF_UP);
        if (position == null) {
            position = new QuantPosition();
            position.setCredentialId(credentialId);
            position.setSymbol(symbol);
            position.setPositionSide(direction);
            position.setQuantity(quantity);
            position.setEntryPrice(entryPrice);
            position.setMarkPrice(entryPrice);
            position.setMargin(margin);
            position.setLeverage(leverage);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setUpdatedAt(LocalDateTime.now());
            positionMapper.insert(position);
        }
        record.setPaperSettled(true);
        record.setExecutionStatus("CONFIRMED");
        record.setExchangeOrderStatus("FILLED");
        record.setExchangeOrderId(record.getExchangeOrderId());
        record.setUpdatedAt(LocalDateTime.now());
        executionRecordMapper.updateById(record);
        return position;
    }

    /**
     * 结算 PAPER 平仓并返回结算结果。
     */
    @Transactional
    public PaperCloseSettlement settlePaperExecution(Long credentialId, String idempotencyKey, Long positionId, BigDecimal quantity, BigDecimal fillPrice) {
        QuantExecutionRecord record = lockExecution(credentialId, idempotencyKey);
        if (Boolean.TRUE.equals(record.getPaperSettled())) {
            QuantPosition current = positionId == null ? null : positionMapper.selectById(positionId);
            return new PaperCloseSettlement(current, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (positionId == null || quantity == null || quantity.signum() <= 0 || fillPrice == null || fillPrice.signum() <= 0) {
            throw new IllegalArgumentException("PAPER 平仓参数无效");
        }
        jdbcTemplate.queryForObject("SELECT id FROM quant_position WHERE id=? FOR UPDATE", (rs, rowNum) -> rs.getLong(1), positionId);
        QuantPosition position = positionMapper.selectById(positionId);
        if (position == null || position.getQuantity() == null || position.getQuantity().signum() <= 0) {
            throw new IllegalStateException("PAPER 持仓不存在");
        }
        BigDecimal closeQty = quantity.min(position.getQuantity());
        BigDecimal oldUnrealizedPnl = position.getUnrealizedPnl() == null ? BigDecimal.ZERO : position.getUnrealizedPnl();
        BigDecimal delta = fillPrice.subtract(position.getEntryPrice());
        if ("SHORT".equalsIgnoreCase(position.getPositionSide())) {
            delta = delta.negate();
        }
        BigDecimal realized = delta.multiply(closeQty);
        BigDecimal oldQty = position.getQuantity();
        BigDecimal releasedMargin = (position.getMargin() == null ? BigDecimal.ZERO : position.getMargin()).multiply(closeQty).divide(oldQty, 16, RoundingMode.HALF_UP);
        BigDecimal remain = oldQty.subtract(closeQty).max(BigDecimal.ZERO);
        ensure(credentialId);
        QuantPaperAccount lockedAccount = jdbcTemplate.queryForObject("SELECT * FROM quant_paper_account WHERE credential_id=? FOR UPDATE", (rs, rowNum) -> {
            QuantPaperAccount a = new QuantPaperAccount();
            a.setId(rs.getLong("id"));
            a.setBalance(rs.getBigDecimal("balance"));
            a.setAvailableBalance(rs.getBigDecimal("available_balance"));
            a.setRealizedPnl(rs.getBigDecimal("realized_pnl"));
            return a;
        }, credentialId);
        if (remain.signum() == 0) {
            positionMapper.deleteById(positionId);
        } else {
            position.setQuantity(remain);
            position.setMargin((position.getMargin() == null ? BigDecimal.ZERO : position.getMargin()).subtract(releasedMargin).max(BigDecimal.ZERO));
            position.setMarkPrice(fillPrice);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setUpdatedAt(LocalDateTime.now());
            positionMapper.updateById(position);
        }
        jdbcTemplate.update("UPDATE quant_paper_account SET balance=balance+?, available_balance=available_balance+?+?, realized_pnl=realized_pnl+?, " + "unrealized_pnl" +
            "=unrealized_pnl-?, updated_at=NOW() WHERE id=?", realized, releasedMargin, realized, realized, oldUnrealizedPnl, lockedAccount.getId());
        record.setPaperSettled(true);
        record.setExecutionStatus("CONFIRMED");
        record.setExchangeOrderStatus("FILLED");
        record.setUpdatedAt(LocalDateTime.now());
        executionRecordMapper.updateById(record);
        QuantPosition current = remain.signum() == 0 ? null : positionMapper.selectById(positionId);
        return new PaperCloseSettlement(current, releasedMargin, realized);
    }

    /**
     * 根据最新价格刷新 PAPER 未实现盈亏。
     */
    @Transactional
    public void refreshUnrealizedPnl(Long credentialId) {
        ensure(credentialId);
        List<QuantPosition> positions =
            positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credentialId).gt(QuantPosition::getQuantity, BigDecimal.ZERO));
        BigDecimal total = BigDecimal.ZERO;
        for (QuantPosition p : positions) {
            BigDecimal mark = p.getMarkPrice() == null ? p.getEntryPrice() : p.getMarkPrice();
            BigDecimal pnl = mark.subtract(p.getEntryPrice()).multiply(p.getQuantity());
            if ("SHORT".equalsIgnoreCase(p.getPositionSide())) {
                pnl = pnl.negate();
            }
            p.setUnrealizedPnl(pnl);
            p.setUpdatedAt(LocalDateTime.now());
            positionMapper.updateById(p);
            total = total.add(pnl);
        }
        jdbcTemplate.update("UPDATE quant_paper_account SET unrealized_pnl=?, updated_at=NOW() WHERE credential_id=?", total, credentialId);
    }

    /**
     * 更新 PAPER 持仓标记价格。
     */
    @Transactional
    public void updateMarkPrice(QuantPosition position, BigDecimal markPrice) {
        if (position == null || position.getId() == null || markPrice == null || markPrice.signum() <= 0) {
            return;
        }
        BigDecimal oldPnl = position.getUnrealizedPnl() == null ? BigDecimal.ZERO : position.getUnrealizedPnl();
        BigDecimal pnl = markPrice.subtract(position.getEntryPrice()).multiply(position.getQuantity());
        if ("SHORT".equalsIgnoreCase(position.getPositionSide())) {
            pnl = pnl.negate();
        }
        position.setMarkPrice(markPrice);
        position.setUnrealizedPnl(pnl);
        position.setUpdatedAt(LocalDateTime.now());
        positionMapper.updateById(position);
        jdbcTemplate.update("UPDATE quant_paper_account SET unrealized_pnl=unrealized_pnl+?-?, updated_at=NOW() WHERE credential_id=?", pnl, oldPnl, position.getCredentialId());
    }

    /**
     * 对 PAPER 账户加数据库锁，串行化关键结算。
     */
    private QuantExecutionRecord lockExecution(Long credentialId, String idempotencyKey) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM quant_execution_record WHERE credential_id=? AND idempotency_key=? FOR UPDATE", Long.class, credentialId,
            idempotencyKey);
        QuantExecutionRecord record = executionRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalStateException("PAPER 执行记录不存在: " + idempotencyKey);
        }
        return record;
    }

    /**
     * PAPER 平仓结算结果。
     *
     * @param position       结算后的本地持仓
     * @param releasedMargin PAPER 平仓释放的保证金
     * @param realizedPnl    已实现盈亏
     */
    public record PaperCloseSettlement(QuantPosition position, BigDecimal releasedMargin, BigDecimal realizedPnl) {
    }
}
