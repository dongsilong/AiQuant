package org.dromara.common.satoken.core.service;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * sa-token 权限管理实现类。
 */
public class SaPermissionImpl implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return resolvePermissionList(loginId, LoginUser::getMenuPermission, PermissionService::getMenuPermission);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return resolvePermissionList(loginId, LoginUser::getRolePermission, PermissionService::getRolePermission);
    }

    /**
     * 优先使用当前会话中与目标登录ID匹配的权限快照；否则按登录ID查询权限。
     * 无法解析登录ID或缺少权限服务时失败关闭，不返回任何隐式授权。
     */
    private List<String> resolvePermissionList(
        Object loginId,
        Function<LoginUser, Collection<String>> localPermissionExtractor,
        BiFunction<PermissionService, Long, Collection<String>> remotePermissionExtractor
    ) {
        String normalizedLoginId = normalizeLoginId(loginId);
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser != null && Objects.equals(loginUser.getLoginId(), normalizedLoginId)) {
            Collection<String> permissionList = localPermissionExtractor.apply(loginUser);
            return CollUtil.isEmpty(permissionList) ? new ArrayList<>() : new ArrayList<>(permissionList);
        }

        PermissionService permissionService = getPermissionService();
        if (permissionService == null) {
            throw new ServiceException("PermissionService 实现类不存在");
        }
        Collection<String> permissions = remotePermissionExtractor.apply(permissionService, resolveUserId(normalizedLoginId));
        return CollUtil.isEmpty(permissions) ? new ArrayList<>() : new ArrayList<>(permissions);
    }

    private PermissionService getPermissionService() {
        try {
            return SpringUtils.getBean(PermissionService.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 兼容历史冒号格式，并拒绝空登录ID。
     */
    private String normalizeLoginId(Object loginId) {
        if (loginId == null) {
            throw new ServiceException("登录ID不能为空");
        }
        String loginIdStr = loginId.toString().trim();
        if (loginIdStr.isEmpty()) {
            throw new ServiceException("登录ID不能为空");
        }
        int separatorIndex = loginIdStr.indexOf(':');
        if (separatorIndex < 0) {
            return loginIdStr;
        }
        return loginIdStr.substring(0, separatorIndex) + LoginUser.LOGIN_ID_SEPARATOR + loginIdStr.substring(separatorIndex + 1);
    }

    /**
     * 从规范化登录ID提取用户ID，并将格式错误转换为明确的业务异常。
     */
    private Long resolveUserId(String loginId) {
        int separatorIndex = loginId.lastIndexOf(LoginUser.LOGIN_ID_SEPARATOR);
        if (separatorIndex < 0 || separatorIndex == loginId.length() - 1) {
            throw new ServiceException("登录ID格式错误");
        }
        try {
            return Long.parseLong(loginId.substring(separatorIndex + 1));
        } catch (NumberFormatException e) {
            throw new ServiceException("登录ID格式错误");
        }
    }
}
