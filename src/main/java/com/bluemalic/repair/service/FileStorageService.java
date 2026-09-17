package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

/** 图片上传（工单现场图与维修后图）。存储用 MinIO，走 S3 协议，不绑厂商。 */
public interface FileStorageService {

    FileUploadVO upload(MultipartFile file);
}
