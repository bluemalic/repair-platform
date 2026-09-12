package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 楼栋。租户内的字典数据，字段与 building 表一一对应。 */
@Data
@TableName("building")
public class Building {

    private Long id;

    private Long tenantId;

    private String name;

    private String area;

    private Integer sort;

    private Integer status;

    /** 逻辑删除 0否 1是。名字必须叫 deleted，全局配置按这个名字识别，改名会导致逻辑删除失效 */
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
