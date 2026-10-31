package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.Asset;
import org.apache.ibatis.annotations.Mapper;

/** 设备台账 Mapper。asset 不在数据权限拦截器范围内，租户条件由 Service 显式写。 */
@Mapper
public interface AssetMapper extends BaseMapper<Asset> {
}
