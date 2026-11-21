package org.dromara.quant.credential.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.domain.vo.QuantCredentialVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 凭证数据访问。
 */
public interface QuantCredentialMapper extends BaseMapperPlus<QuantCredential, QuantCredentialVo> {
    @Update("UPDATE quant_credential SET credential_runtime_version = credential_runtime_version + 1 WHERE id = #{id} AND del_flag = 0")
    int incrementRuntimeVersion(@Param("id") Long id);
}
