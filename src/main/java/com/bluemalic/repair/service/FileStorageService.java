package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

/** 图片上传（工单现场图与维修后图）。存储用 MinIO，走 S3 协议，不绑厂商。 */
public interface FileStorageService {

    FileUploadVO upload(MultipartFile file);

    /**
     * 清空某个租户在对象存储里的**全部**图片，返回删掉的对象数。
     *
     * <p>只给演示重置用：演示站每天重建工单，旧图片就没有引用方了，不清就是纯占磁盘
     * （磁盘是 2C2G 机器上最经不起耗的资源）。对象键的前缀就是租户 ID（见 {@code objectKey()}），
     * 所以按前缀清理即可，不必回数据库反查引用。
     *
     * <p><b>破坏性操作</b>：删掉的 URL 立刻不可用，正在看页面的访客可能拿到 404。
     * 只在重置流程里调用，而且清理失败不抛异常——图片没清掉最多占点空间，不该让整次重置失败。
     */
    int purgeTenant(long tenantId);
}
