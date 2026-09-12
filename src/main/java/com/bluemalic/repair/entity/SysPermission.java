package com.bluemalic.repair.entity;


import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_permission")
public class SysPermission {

    private Long id;

    private String code;

    private String name;

    private Integer type;

    private Long parentId;

    private Integer sort;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
