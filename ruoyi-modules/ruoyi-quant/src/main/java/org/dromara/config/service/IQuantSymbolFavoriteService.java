package org.dromara.config.service;


/**
 * IQuantSymbolFavoriteService，封装对应领域业务。
 */
public interface IQuantSymbolFavoriteService {

    void favorite(Long id, Boolean favorite);

}
