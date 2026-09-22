package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.vo.StudentVO;

/** 学生账号的实体 → VO。 */
public final class StudentConverter {

    private StudentConverter() {
    }

    public static StudentVO toVO(SysUser user) {
        StudentVO vo = new StudentVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        // 库里的 1/0 在接口上就是 true/false：前端拿它决定是否跳转强制改密，
        // 让前端去判 "1" 这种魔法值只会多一处可能写错的地方
        vo.setMustChangePassword(Integer.valueOf(1).equals(user.getMustChangePassword()));
        return vo;
    }
}
