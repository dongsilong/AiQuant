package org.dromara.quant;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.config.domain.QuantSymbol;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.config.domain.QuantCredential;
import org.dromara.config.mapper.QuantCredentialMapper;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.config.mapper.QuantSymbolMapper;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.mapper.QuantStrategyPositionStateMapper;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyMapper;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.dromara.config.service.impl.QuantStrategyService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/quant")
public class QuantAdminController {
    private final QuantCredentialMapper credentialMapper;
    private final QuantSymbolMapper symbolMapper;
    private final QuantStrategyMapper strategyMapper;
    private final QuantStrategySymbolMapper strategySymbolMapper;
    private final QuantStrategyInstanceMapper instanceMapper;
    private final QuantStrategyPositionStateMapper positionStateMapper;
    private final QuantPositionMapper positionMapper;
    private final QuantOrderMapper orderMapper;
    private final QuantTradeMapper tradeMapper;
    private final QuantExecutionRecordMapper executionMapper;
    private final QuantStrategyService strategyService;

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    @SaCheckPermission("quant:strategy:list")
    @GetMapping("/strategy/instances")
    public R<PageResult<Map<String, Object>>> strategyInstances(PageQuery query, @RequestParam(required = false) Long credentialId, @RequestParam(required = false) String status) {
        List<Long> ids = scopedIds(credentialId);
        if (ids.isEmpty()) {
            return R.ok(emptyPage());
        }
        List<Long> strategyIds = strategyService.accessibleStrategyIds();
        if (strategyIds != null && strategyIds.isEmpty()) return R.ok(emptyPage());
        LambdaQueryWrapper<QuantStrategySymbol> symbolQuery = new LambdaQueryWrapper<QuantStrategySymbol>().eq(QuantStrategySymbol::getStatus, "0");
        symbolQuery.in(strategyIds != null, QuantStrategySymbol::getStrategyId, strategyIds);
        List<Long> activeStrategySymbolIds = strategySymbolMapper.selectList(symbolQuery).stream().map(QuantStrategySymbol::getId).toList();
        if (activeStrategySymbolIds.isEmpty()) return R.ok(emptyPage());
        Page<QuantStrategyInstance> page = instanceMapper.selectPage(query.build(), new LambdaQueryWrapper<QuantStrategyInstance>().in(QuantStrategyInstance::getCredentialId,
            ids).in(strategyIds != null, QuantStrategyInstance::getStrategyId, strategyIds).in(QuantStrategyInstance::getStrategySymbolId, activeStrategySymbolIds)
            .eq(status != null && !status.isBlank(), QuantStrategyInstance::getStatus, status).orderByDesc(QuantStrategyInstance::getStrategyId,
                QuantStrategyInstance::getCredentialId, QuantStrategyInstance::getStatus, QuantStrategyInstance::getStrategySymbolId, QuantStrategyInstance::getLastSignalAt));
        Map<Long, QuantCredential> credentials = credentials(ids);
        Map<Long, QuantStrategy> strategies = strategies(page.getRecords().stream().map(QuantStrategyInstance::getStrategyId).toList());
        Map<Long, QuantStrategySymbol> strategySymbols = strategySymbols(page.getRecords().stream().map(QuantStrategyInstance::getStrategySymbolId).toList());
        Map<Long, QuantSymbol> symbols = symbols(strategySymbols.values().stream().map(QuantStrategySymbol::getSymbolId).toList());
        List<Map<String, Object>> rows = page.getRecords().stream().map(item -> {
            Map<String, Object> row = map();
            row.put("id", item.getId());
            row.put("strategyId", item.getStrategyId());
            row.put("strategyName", strategies.get(item.getStrategyId()) == null ? null : strategies.get(item.getStrategyId()).getStrategyName());
            row.put("credentialId", item.getCredentialId());
            row.put("accountName", credentials.get(item.getCredentialId()) == null ? null : credentials.get(item.getCredentialId()).getAccountName());
            QuantStrategySymbol binding = strategySymbols.get(item.getStrategySymbolId());
            row.put("symbol", binding == null || symbols.get(binding.getSymbolId()) == null ? null : symbols.get(binding.getSymbolId()).getSymbol());
            row.put("status", item.getStatus());
            row.put("lastSignalAt", item.getLastSignalAt());
            row.put("lastError", item.getLastError());
            return row;
        }).toList();
        return R.ok(PageResult.build(rows, page.getTotal()));
    }

    @SaCheckPermission("quant:strategy:list")
    @GetMapping("/strategy/position-states")
    public R<PageResult<Map<String, Object>>> strategyPositionStates(PageQuery query, @RequestParam(required = false) Long credentialId,
                                                                     @RequestParam(required = false) String status) {
        List<Long> ids = scopedIds(credentialId);
        if (ids.isEmpty()) {
            return R.ok(emptyPage());
        }
        List<Long> strategyIds = strategyService.accessibleStrategyIds();
        if (strategyIds != null && strategyIds.isEmpty()) return R.ok(emptyPage());
        List<QuantStrategyInstance> instances = instanceMapper.selectList(new LambdaQueryWrapper<QuantStrategyInstance>().in(QuantStrategyInstance::getCredentialId, ids)
            .in(strategyIds != null, QuantStrategyInstance::getStrategyId, strategyIds));
        if (instances.isEmpty()) {
            return R.ok(emptyPage());
        }
        List<Long> activePositionIds = positionMapper.selectList(new LambdaQueryWrapper<org.dromara.quant.position.domain.QuantPosition>()
            .in(org.dromara.quant.position.domain.QuantPosition::getCredentialId, ids)
            .gt(org.dromara.quant.position.domain.QuantPosition::getQuantity, java.math.BigDecimal.ZERO)).stream()
            .map(org.dromara.quant.position.domain.QuantPosition::getId).toList();
        if (activePositionIds.isEmpty()) {
            return R.ok(emptyPage());
        }
        Map<Long, QuantStrategyInstance> instanceMap = instances.stream().collect(java.util.stream.Collectors.toMap(QuantStrategyInstance::getId, v -> v));
        Page<QuantStrategyPositionState> page = positionStateMapper.selectPage(query.build(),
            new LambdaQueryWrapper<QuantStrategyPositionState>().in(QuantStrategyPositionState::getInstanceId, instanceMap.keySet()).in(QuantStrategyPositionState::getPositionId, activePositionIds).eq(status != null && !status.isBlank(),
                QuantStrategyPositionState::getStatus, status).in(status == null || status.isBlank(), QuantStrategyPositionState::getStatus,
                List.of("OPEN", "OPEN_PENDING", "EXIT_PENDING")).orderByDesc(QuantStrategyPositionState::getUpdatedAt));
        Map<Long, QuantStrategy> strategyMap = strategies(instanceMap.values().stream().map(QuantStrategyInstance::getStrategyId).toList());
        Map<Long, QuantStrategySymbol> strategySymbolMap = strategySymbols(instanceMap.values().stream().map(QuantStrategyInstance::getStrategySymbolId).toList());
        Map<Long, QuantSymbol> positionSymbols = symbols(strategySymbolMap.values().stream().map(QuantStrategySymbol::getSymbolId).toList());
        List<Map<String, Object>> rows = page.getRecords().stream().map(item -> {
            Map<String, Object> row = map();
            QuantStrategyInstance instance = instanceMap.get(item.getInstanceId());
            QuantStrategy strategy = instance == null ? null : strategyMap.get(instance.getStrategyId());
            QuantStrategySymbol binding = instance == null ? null : strategySymbolMap.get(instance.getStrategySymbolId());
            QuantSymbol symbol = binding == null ? null : positionSymbols.get(binding.getSymbolId());
            row.put("id", item.getId());
            row.put("instanceId", item.getInstanceId());
            row.put("instanceName", (strategy == null ? "策略" : strategy.getStrategyName()) + (symbol == null ? "" : " / " + symbol.getSymbol()));
            row.put("credentialId", instance == null ? null : instance.getCredentialId());
            row.put("status", item.getStatus());
            row.put("statusLabel", positionStatusLabel(item.getStatus()));
            row.put("direction", item.getDirection());
            row.put("directionLabel", directionLabel(item.getDirection()));
            row.put("entryPrice", item.getEntryPrice());
            row.put("positionQty", item.getPositionQty());
            row.put("initialPositionQty", item.getInitialPositionQty());
            row.put("highestStep", item.getHighestStep());
            row.put("firstTakeProfitTriggered", item.getFirstTakeProfitTriggered());
            row.put("pendingAction", item.getPendingAction());
            row.put("recoveryRequired", item.getRecoveryRequired());
            row.put("recoveryRequiredLabel", Boolean.TRUE.equals(item.getRecoveryRequired()) ? "是" : "否");
            row.put("unsafeRecovery", item.getUnsafeRecovery());
            row.put("updatedAt", item.getUpdatedAt());
            return row;
        }).toList();
        return R.ok(PageResult.build(rows, page.getTotal()));
    }

    private static String directionLabel(String direction) {
        if ("LONG".equalsIgnoreCase(direction)) return "做多";
        if ("SHORT".equalsIgnoreCase(direction)) return "做空";
        return direction == null || direction.isBlank() ? "-" : direction;
    }

    private static String positionStatusLabel(String status) {
        if ("OPEN".equalsIgnoreCase(status)) return "持仓中";
        if ("OPEN_PENDING".equalsIgnoreCase(status)) return "开仓中";
        if ("EXIT_PENDING".equalsIgnoreCase(status)) return "平仓中";
        if ("CLOSED".equalsIgnoreCase(status)) return "已平仓";
        return status == null || status.isBlank() ? "-" : status;
    }

    @SaCheckPermission("quant:execution:list")
    @GetMapping("/execution/page")
    public R<PageResult<Map<String, Object>>> execution(PageQuery query, @RequestParam(required = false) Long credentialId,
                                                        @RequestParam(required = false) String executionStatus,
                                                        @RequestParam(required = false) String symbol,
                                                        @RequestParam(required = false) String exchangeOrderStatus,
                                                        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") java.time.LocalDateTime startTime,
                                                        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") java.time.LocalDateTime endTime) {
        var until = endTime == null ? java.time.LocalDateTime.now() : endTime;
        var from = startTime == null ? until.minusHours(24) : startTime;
        if (from.isAfter(until)) { throw new ServiceException("开始时间不能晚于结束时间"); }
        List<Long> ids = scopedIds(credentialId);
        if (ids.isEmpty()) {
            return R.ok(emptyPage());
        }
        Page<QuantExecutionRecord> page = executionMapper.selectPage(query.build(),
            new LambdaQueryWrapper<QuantExecutionRecord>().in(QuantExecutionRecord::getCredentialId, ids).eq(executionStatus != null && !executionStatus.isBlank(),
                QuantExecutionRecord::getExecutionStatus, executionStatus)
                .eq(symbol != null && !symbol.isBlank(), QuantExecutionRecord::getSymbol, symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT))
                .eq(exchangeOrderStatus != null && !exchangeOrderStatus.isBlank(), QuantExecutionRecord::getExchangeOrderStatus,
                    exchangeOrderStatus == null ? null : exchangeOrderStatus.trim().toUpperCase(Locale.ROOT))
                .ge(QuantExecutionRecord::getCreatedAt, from).le(QuantExecutionRecord::getCreatedAt, until)
                .orderByDesc(QuantExecutionRecord::getCreatedAt));
        Map<Long, QuantCredential> credentialMap = credentials(ids);
        List<Long> orderIds = page.getRecords().stream().map(QuantExecutionRecord::getExchangeOrderId)
            .filter(Objects::nonNull).distinct().toList();
        Map<String, BigDecimal> fillQuantities = new HashMap<>();
        Map<String, BigDecimal> commissions = new HashMap<>();
        if (!orderIds.isEmpty()) {
            tradeMapper.selectList(new LambdaQueryWrapper<QuantTrade>().in(QuantTrade::getCredentialId, ids)
                .in(QuantTrade::getExchangeOrderId, orderIds)).forEach(trade -> {
                    String key = trade.getCredentialId() + ":" + trade.getExchangeOrderId();
                    if (trade.getQuantity() != null) fillQuantities.merge(key, trade.getQuantity(), BigDecimal::add);
                    if (trade.getCommission() != null) commissions.merge(key, trade.getCommission(), BigDecimal::add);
                });
        }
        List<Map<String, Object>> rows = page.getRecords().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String orderKey = item.getCredentialId() + ":" + item.getExchangeOrderId();
            row.put("id", item.getId());
            row.put("credentialId", item.getCredentialId());
            row.put("accountName", credentialMap.get(item.getCredentialId()) == null ? null : credentialMap.get(item.getCredentialId()).getAccountName());
            row.put("symbol", item.getSymbol());
            row.put("executionStatus", item.getExecutionStatus());
            row.put("exchangeOrderStatus", item.getExchangeOrderStatus());
            row.put("exchangeOrderId", item.getExchangeOrderId());
            row.put("triggerReason", item.getTriggerReason());
            row.put("fillQuantity", fillQuantities.get(orderKey));
            row.put("commission", commissions.get(orderKey));
            row.put("errorMessage", org.dromara.quant.execution.ExecutionErrorSanitizer.safeReason(
                new ServiceException(item.getErrorMessage() == null ? "" : item.getErrorMessage())));
            row.put("operationType", item.getOperationType());
            row.put("idempotencyKey", item.getIdempotencyKey());
            row.put("createdAt", item.getCreatedAt());
            row.put("updatedAt", item.getUpdatedAt());
            return row;
        }).toList();
        return R.ok(PageResult.build(rows, page.getTotal()));
    }

    private List<Long> accessibleCredentialIds() {
        Long userId = LoginHelper.getUserId();
        if (LoginHelper.isSuperAdmin(userId)) {
            return credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>()).stream().map(QuantCredential::getId).toList();
        }
        return credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getUserId, userId)).stream().map(QuantCredential::getId).toList();
    }

    private List<Long> scopedIds(Long credentialId) {
        List<Long> ids = accessibleCredentialIds();
        if (credentialId == null) {
            return ids;
        }
        if (!ids.contains(credentialId)) {
            throw new ServiceException("无权访问该交易账户");
        }
        return List.of(credentialId);
    }


    private Map<Long, QuantCredential> credentials(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>().in(QuantCredential::getId, ids)).stream().collect(java.util.stream.Collectors.toMap(QuantCredential::getId, v -> v));
    }

    private Map<Long, QuantStrategy> strategies(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return strategyMapper.selectList(new LambdaQueryWrapper<QuantStrategy>().in(QuantStrategy::getId, ids)).stream().collect(java.util.stream.Collectors.toMap(QuantStrategy::getId, v -> v));
    }

    private Map<Long, QuantStrategySymbol> strategySymbols(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return strategySymbolMapper.selectList(new LambdaQueryWrapper<QuantStrategySymbol>().in(QuantStrategySymbol::getId, ids)).stream().collect(java.util.stream.Collectors.toMap(QuantStrategySymbol::getId, v -> v));
    }

    private Map<Long, QuantSymbol> symbols(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return symbolMapper.selectList(new LambdaQueryWrapper<QuantSymbol>().in(QuantSymbol::getId, ids)).stream().collect(java.util.stream.Collectors.toMap(QuantSymbol::getId,
            v -> v));
    }


    private <T> PageResult<T> emptyPage() {
        return PageResult.build(new ArrayList<>(), 0);
    }
}
