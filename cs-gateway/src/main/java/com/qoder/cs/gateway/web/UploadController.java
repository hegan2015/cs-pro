package com.qoder.cs.gateway.web;

import com.qoder.cs.common.result.Result;
import com.qoder.cs.core.biz.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** 图片附件上传（对话/工单共用）：白名单校验后落 data/uploads，经 /uploads/** 访问。 */
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploads;

    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String url = uploads.save(file.getOriginalFilename(), file.getBytes());
        return Result.ok(Map.of("url", url));
    }
}