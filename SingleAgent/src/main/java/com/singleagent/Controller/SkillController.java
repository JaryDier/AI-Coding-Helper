package com.singleagent.Controller;

import com.esotericsoftware.kryo.io.ByteBufferOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/skill")
@RequiredArgsConstructor
@Slf4j
public class SkillController {

    @Value("${skills.upload.path}")
    private String skillRootPath;

    //skill上传
    @PostMapping("/upload")
    public String uploadSkill(@RequestParam(name = "file") MultipartFile file) {
        //1、文件判空
        if(file.isEmpty()) {
            log.error("File is empty");
            return "File is empty";
        }

        //2、获取原始文件名
        String originalFilename  = file.getOriginalFilename();
        if(StringUtils.isBlank(originalFilename )) {
            log.error("originalFilename  is empty");
            return "originalFilename  is empty";
        }
        int lastIndex = originalFilename.lastIndexOf(".");
        if(lastIndex == -1) {
            log.error("lastIndexOf is empty");
            return "lastIndexOf is empty";
        }
        String fileName = originalFilename.substring(0, lastIndex);
        //3、创建文件目录
        //3.1、检查目录是否存在
        File skillDirectory = new File(skillRootPath + fileName);
        if (!skillDirectory.exists()) {
            skillDirectory.mkdir();
        }

        //3.2 解压缩 并写入
        unZip(skillDirectory,file);
        return "success";
    }

    //多文件上传
    @PostMapping("/upload/mulitFile")
    private String uploadMulitFile(@RequestParam(name = "files") MultipartFile[] multipartFiles) {
        if (multipartFiles == null || multipartFiles.length == 0) {
            log.error("multipartFiles is empty");
            return "multipartFiles is empty";
        }
        for (MultipartFile multipartFile : multipartFiles) {
            String originalFilename = multipartFile.getOriginalFilename();
            if(StringUtils.isBlank(originalFilename)) {
                log.error("originalFilename is empty");
                return "originalFilename is empty";
            }
            int lastIndex = originalFilename.lastIndexOf(".");
            if(lastIndex == -1) {
                log.error("lastIndexOf is empty");
            }
            String fileName = originalFilename.substring(0, lastIndex);
            //得到当前skill的文件夹路径
            File skillDirectory = new File(skillRootPath + fileName);
            if (!skillDirectory.exists()) {
                skillDirectory.mkdir();
            }
            //解压缩写入
            unZip(skillDirectory,multipartFile);
        }
        return "success";
    }


    public boolean unZip(File skillDirectory,MultipartFile file) {
        if (!skillDirectory.exists()) {
            skillDirectory.mkdir();
        }

        //zip文件输入流
        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            //循环得到zip中的文件夹或者文件
            while ((entry = zipInputStream.getNextEntry()) != null) {
                log.info("entry.getName() = " + entry.getName());
                //将根目录和文件/文件夹拼接
                File outFile = new File(skillDirectory, entry.getName());

                File parentDir = outFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                //目录
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }

                try (BufferedOutputStream bos = new BufferedOutputStream(new  FileOutputStream(outFile))) {
                    byte[] bytes = new byte[2048];
                    int len;

                    while ((len = zipInputStream.read(bytes)) != -1) {
                        bos.write(bytes, 0, len);
                    }
                }
                zipInputStream.closeEntry();
            }
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
