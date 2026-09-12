package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 SysUser 实体与 sys_user 表是否真的对得上。
 *
 * <p>为什么不写"查一下有没有数据"：建表脚本刻意不初始化任何用户（密码需要 BCrypt 加密，
 * 等登录功能做完再插），查询结果必然为空，证明不了字段映射正确。所以要自己插入再读回来。
 *
 * <p>{@code @Transactional} 让测试结束后自动回滚，不会往库里留脏数据。
 */
@SpringBootTest
@Transactional
class SysUserMapperTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void insertThenSelectBack() {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        // 测试数据统一用 test- 前缀：本地执行过 docs/dev-seed.sql（演示账号 admin / worker01 /
        // 20260001）之后，若测试也用真实风格的用户名就会撞 sys_user 的租户内唯一键
        user.setUsername("test-sysuser");
        user.setPassword("$2a$10$placeholder-not-a-real-hash");
        user.setRealName("张三");
        user.setPhone("13800000000");
        user.setUserType(1);
        user.setStatus(1);

        assertThat(sysUserMapper.insert(user)).isEqualTo(1);

        // 雪花 ID 是 MyBatis-Plus 在 insert 时回填到实体上的；为 null 说明主键策略没生效
        assertThat(user.getId()).isNotNull();

        SysUser loaded = sysUserMapper.selectById(user.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo("test-sysuser");
        assertThat(loaded.getRealName()).isEqualTo("张三");
        assertThat(loaded.getPhone()).isEqualTo("13800000000");
        assertThat(loaded.getUserType()).isEqualTo(1);
        assertThat(loaded.getStatus()).isEqualTo(1);

        // deleted 由数据库默认值填 0，说明"代码不赋值、交给数据库"这条约定成立
        assertThat(loaded.getDeleted()).isEqualTo(0);
        // create_time 同理，能读出来非空就证明数据库默认值生效了
        assertThat(loaded.getCreateTime()).isNotNull();
    }
}
