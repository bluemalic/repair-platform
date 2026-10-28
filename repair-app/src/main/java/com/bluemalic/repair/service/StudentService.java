package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.StudentCreateDTO;
import com.bluemalic.repair.dto.StudentImportDTO;
import com.bluemalic.repair.dto.StudentUpdateDTO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.StudentImportVO;
import com.bluemalic.repair.vo.StudentVO;

/**
 * 学生账号管理（后勤在管理端用）。
 *
 * <p><b>为什么需要它</b>：在这之前学生账号只能靠插库或初始化脚本预置——学校要新增一个学生，
 * 或者一届新生入学，管理员在界面上没有任何入口。这不是"少一个便利功能"，而是
 * **平台无法独立运行**：真实交付给一所学校时，账号必须能由校方自己维护。
 *
 * <p>建出来的账号**首次登录必须改密**（{@code must_change_password = 1}）。理由是学生这边的
 * 初始口令只能统一发放：口令要写在纸上发给一个班，一人一个反而没人记得住；而学号在班级里
 * 是公开信息，统一口令意味着同学之间可以互相登录。所以它必须是一次性的。
 */
public interface StudentService {

    PageResult<StudentVO> page(long pageNum, long pageSize, Integer status, String keyword);

    StudentVO create(StudentCreateDTO dto);

    /** 改姓名 / 手机号 / 状态；{@code password} 留空即不改，填了就是管理员帮他重置。 */
    void update(long id, StudentUpdateDTO dto);

    /** 重置口令（单独入口，前端"一键重置"用）。重置后仍需首次登录改密。 */
    void resetPassword(long id, String rawPassword);

    /** 批量导入（粘贴学号名单）。**幂等**：已存在的学号跳过而不是报错。 */
    StudentImportVO importStudents(StudentImportDTO dto);
}
